import base64
import io
import json
import os
import subprocess
import urllib.error

import pytest
from ralph_token import (
    App,
    TokenError,
    Tokens,
    github_post,
    keychain_key,
    load_app,
    openssl_signer,
)


def unb64(text):
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


class FakeGitHubApps:
    """Hands out installation tokens in turn and records the JWT each request was made with."""

    def __init__(self, *tokens):
        self.tokens = list(tokens)
        self.requests = []

    def __call__(self, path, bearer):
        self.requests.append((path, bearer))
        value, expires_at = self.tokens.pop(0)
        return {"token": value, "expires_at": expires_at}


def tokens(github, now=1_795_000_000.0):
    return Tokens(
        "5872314", "184603282", sign=lambda data: b"signed:" + data, post=github, clock=lambda: now
    )


def test_an_app_jwt_signed_with_the_key_is_exchanged_for_an_installation_token():
    github = FakeGitHubApps(("ghs_first", "2026-11-23T12:00:00Z"))

    token = tokens(github).current()

    assert token == "ghs_first"
    path, jwt = github.requests[0]
    assert path == "/app/installations/184603282/access_tokens"
    header, payload, signature = jwt.split(".")
    assert json.loads(unb64(header)) == {"alg": "RS256", "typ": "JWT"}
    assert json.loads(unb64(payload)) == {
        "iat": 1_794_999_940,
        "exp": 1_795_000_540,
        "iss": "5872314",
    }
    assert unb64(signature) == f"signed:{header}.{payload}".encode()


def test_each_session_gets_a_newly_minted_token_even_while_the_last_is_valid():
    github = FakeGitHubApps(
        ("ghs_first", "2026-11-23T12:00:00Z"), ("ghs_second", "2026-11-23T12:20:00Z")
    )
    source = tokens(github, now=1_795_435_200.0 - 2400)

    first = source.session_env({})["GH_TOKEN"]
    second = source.session_env({})["GH_TOKEN"]

    assert (first, second) == ("ghs_first", "ghs_second")


def test_the_session_gets_the_token_as_gh_token_and_it_never_appears_when_printed():
    source = tokens(FakeGitHubApps(("ghs_secret", "2026-11-23T12:00:00Z")))

    env = source.session_env({"PATH": "/usr/bin", "GH_TOKEN": "owner-token"})

    assert env == {"PATH": "/usr/bin", "GH_TOKEN": "ghs_secret"}
    assert "ghs_secret" not in repr(source)
    assert "PRIVATE" not in repr(App("1", "2", "-----BEGIN RSA PRIVATE KEY-----"))


def completed(returncode=0, stdout=""):
    return subprocess.CompletedProcess([], returncode, stdout, "")


def test_the_keychain_key_is_decoded_from_the_hex_security_returns():
    key = "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----\n"
    calls = []

    def run(args, **kwargs):
        calls.append(args)
        return completed(stdout=key.encode().hex() + "\n")

    assert keychain_key(run) == key
    assert calls == [
        ["security", "find-generic-password", "-s", "onato-agent", "-a", "private-key", "-w"]
    ]
    assert keychain_key(lambda args, **kwargs: completed(returncode=44)) is None


def test_openssl_signs_with_the_key_passed_through_a_pipe_never_a_file():
    seen = {}

    def run(args, **kwargs):
        (fd,) = kwargs["pass_fds"]
        seen.update(args=args, fd=fd, key=os.read(fd, 100), input=kwargs["input"])
        return completed(stdout=b"signature")

    signature = openssl_signer("KEY\n", run)(b"header.payload")

    assert signature == b"signature"
    assert seen["args"] == ["openssl", "dgst", "-sha256", "-sign", f"/dev/fd/{seen['fd']}"]
    assert (seen["key"], seen["input"]) == (b"KEY\n", b"header.payload")


def test_the_token_request_posts_to_the_github_api_with_the_jwt_as_bearer():
    sent = []

    def urlopen(request):
        sent.append(request)
        return io.BytesIO(b'{"token": "ghs_x", "expires_at": "2026-11-23T12:00:00Z"}')

    reply = github_post(urlopen)("/app/installations/1/access_tokens", "jwt.value.sig")

    assert reply == {"token": "ghs_x", "expires_at": "2026-11-23T12:00:00Z"}
    (request,) = sent
    assert (request.method, request.full_url) == (
        "POST",
        "https://api.github.com/app/installations/1/access_tokens",
    )
    assert request.get_header("Authorization") == "Bearer jwt.value.sig"


def test_a_failed_token_request_stops_the_loop_with_the_reason():
    def urlopen(request):
        raise urllib.error.URLError("nodename nor servname provided")

    with pytest.raises(TokenError) as error:
        github_post(urlopen)("/app/installations/1/access_tokens", "jwt")

    assert str(error.value) == (
        "token request failed: <urlopen error nodename nor servname provided>"
    )


def test_the_app_comes_from_the_config_file_and_the_keychain():
    app = load_app(
        "# the onato-agent GitHub App\napp_id=5872314\ninstallation_id=184603282\n",
        read_key=lambda: "-----BEGIN RSA PRIVATE KEY-----\n",
    )

    assert app == App("5872314", "184603282", "-----BEGIN RSA PRIVATE KEY-----\n")


@pytest.mark.parametrize(
    ("config", "key", "missing"),
    [
        ("app_id=5872314\n", "KEY", "installation_id in .ralph/config"),
        (None, "KEY", "app_id, installation_id in .ralph/config"),
        (
            "app_id=1\ninstallation_id=\n",
            None,
            "installation_id in .ralph/config, the onato-agent private key in the login keychain",
        ),
    ],
)
def test_missing_settings_stop_the_loop_naming_each_one(config, key, missing):
    with pytest.raises(TokenError) as error:
        load_app(config, read_key=lambda: key)

    assert str(error.value) == f"Ralph loop can't start: missing {missing}"
