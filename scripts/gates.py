from fnmatch import fnmatchcase


def matches_any(path: str, patterns: list[str]) -> bool:
    return any(_matches(path, pattern) for pattern in patterns)


def _matches(path: str, pattern: str) -> bool:
    # gitignore rules: a slash at the start or in the middle anchors the pattern at the root;
    # a trailing slash matches directories only. fnmatch's `*` crosses `/`, which gives `**`.
    directory_only = pattern.endswith("/")
    body = pattern.strip("/")
    if pattern.startswith("/") or "/" in body:
        return fnmatchcase(path, body + "/*" if directory_only else body)
    parts = path.split("/")
    candidates = parts[:-1] if directory_only else parts
    return any(fnmatchcase(part, body) for part in candidates)


def approved_by(events: list[dict], label: str, approver: str | None) -> bool:
    """Whether the latest change to the label was the approver adding it."""
    changes = [
        event
        for event in events
        if event["event"] in ("labeled", "unlabeled") and event["label"]["name"] == label
    ]
    if not changes:
        return False
    latest = changes[-1]
    return latest["event"] == "labeled" and latest["actor"]["login"] == approver


def load_patterns(text: str) -> list[str]:
    lines = (line.strip() for line in text.splitlines())
    return [line for line in lines if line and not line.startswith("#")]
