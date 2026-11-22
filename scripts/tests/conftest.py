import socket
import subprocess

import pytest


def refuse(what):
    def guard(*args, **kwargs):
        raise RuntimeError(f"tests must not touch the network: {what}")

    return guard


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    """Every test runs with `gh` and outbound sockets refused, so none can reach GitHub."""
    real_popen = subprocess.Popen

    def popen(args, *rest, **kwargs):
        if list(args)[:1] == ["gh"]:
            refuse("gh")()
        return real_popen(args, *rest, **kwargs)

    monkeypatch.setattr(subprocess, "Popen", popen)
    monkeypatch.setattr(socket.socket, "connect", refuse("socket"))
