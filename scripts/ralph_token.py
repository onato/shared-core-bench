"""Installation tokens for the onato-agent GitHub App, so sessions work as onato-agent[bot]."""

import base64
import json
import os
import subprocess
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from typing import IO

Sign = Callable[[bytes], bytes]
Post = Callable[[str, str], dict]
REFRESH_WITHIN = 600


class TokenError(Exception):
    pass


@dataclass(frozen=True)
class App:
    app_id: str
    installation_id: str
    key: str = field(repr=False)


def load_app(config: str | None, read_key: Callable[[], str | None]) -> App:
    """The app from `.ralph/config` (`name=value` lines; None if absent) and its keychain key."""
    settings = dict(line.split("=", 1) for line in (config or "").splitlines() if "=" in line)
    ids = {name: settings.get(name, "").strip() for name in ("app_id", "installation_id")}
    key = read_key()
    missing = []
    if blank := [name for name, value in ids.items() if not value]:
        missing.append(f"{', '.join(blank)} in .ralph/config")
    if not key:
        missing.append("the onato-agent private key in the login keychain")
    if missing:
        raise TokenError(f"Ralph loop can't start: missing {', '.join(missing)}")
    return App(ids["app_id"], ids["installation_id"], key)


def keychain_key(subprocess_run: Callable[..., subprocess.CompletedProcess]) -> str | None:
    result = subprocess_run(
        ["security", "find-generic-password", "-s", "onato-agent", "-a", "private-key", "-w"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return None
    # `security -w` prints multi-line secrets hex-encoded.
    return bytes.fromhex(result.stdout.strip()).decode()


def openssl_signer(key: str, subprocess_run: Callable[..., subprocess.CompletedProcess]) -> Sign:
    def sign(data: bytes) -> bytes:
        read, write = os.pipe()
        os.write(write, key.encode())
        os.close(write)
        try:
            return subprocess_run(
                ["openssl", "dgst", "-sha256", "-sign", f"/dev/fd/{read}"],
                input=data,
                capture_output=True,
                check=True,
                pass_fds=(read,),
            ).stdout
        finally:
            os.close(read)

    return sign


def github_post(urlopen: Callable[[urllib.request.Request], IO[bytes]]) -> Post:
    def post(path: str, bearer: str) -> dict:
        request = urllib.request.Request(
            f"https://api.github.com{path}",
            method="POST",
            headers={
                "Authorization": f"Bearer {bearer}",
                "Accept": "application/vnd.github+json",
                "User-Agent": "shared-core-bench-ralph",
            },
        )
        try:
            with urlopen(request) as response:
                return json.load(response)
        except OSError as error:
            raise TokenError(f"token request failed: {error}") from error

    return post


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


class Tokens:
    def __init__(self, app_id: str, installation_id: str, sign: Sign, post: Post, clock):
        self.app_id = app_id
        self.installation_id = installation_id
        self.sign = sign
        self.post = post
        self.clock = clock
        self._token: str | None = None
        self._expires_at = 0.0

    def current(self) -> str:
        if self._token is None or self._expires_at - self.clock() < REFRESH_WITHIN:
            path = f"/app/installations/{self.installation_id}/access_tokens"
            reply = self.post(path, self._jwt())
            self._token = reply["token"]
            self._expires_at = datetime.fromisoformat(reply["expires_at"]).timestamp()
        return self._token

    def session_env(self, environ: dict[str, str]) -> dict[str, str]:
        """The session's environment: the token replaces any owner GH_TOKEN, in memory only."""
        return {**environ, "GH_TOKEN": self.current()}

    def _jwt(self) -> str:
        now = int(self.clock())
        # iat is backdated a minute for clock drift; GitHub caps exp at ten minutes ahead.
        claims = {"iat": now - 60, "exp": now + 540, "iss": self.app_id}
        header = b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
        signing_input = f"{header}.{b64(json.dumps(claims).encode())}"
        return f"{signing_input}.{b64(self.sign(signing_input.encode()))}"
