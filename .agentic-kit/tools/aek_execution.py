"""Bounded direct execution for reviewed checks and component adapters.

This is not a sandbox. Secret-looking inherited variables are withheld unless
explicitly allowed; known secret values are redacted from retained output.
Only POSIX process groups provide descendant termination on timeout.
"""
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import time

REDACTED = b"[REDACTED]"
SECRET_KEY = re.compile(r"TOKEN|PASSWORD|SECRET|PRIVATE_KEY|API_KEY|ACCESS_KEY|CREDENTIAL|OAUTH|AUTH_JSON", re.I)
ENV_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def environment(env_allow=()):
    if isinstance(env_allow, str) or not isinstance(env_allow, (list, tuple, set)):
        raise ValueError("env_allow must contain explicit variable names")
    if any(not isinstance(key, str) or not ENV_NAME.fullmatch(key) for key in env_allow):
        raise ValueError("Invalid allowed environment variable name")
    allowed = set(env_allow)
    sensitive = {key: value for key, value in os.environ.items() if SECRET_KEY.search(key)}
    env = {key: value for key, value in os.environ.items() if key not in sensitive or key in allowed}
    # Values never appear in policy metadata. Even explicitly allowed secrets
    # are redacted if a child writes them to its combined stdout/stderr stream.
    secrets = [value.encode("utf-8") for value in sensitive.values() if value]
    return env, secrets, {"mode": "filtered_inherited", "allowed_names": sorted(allowed),
                          "filtered_names": sorted(set(sensitive) - allowed)}


class Redactor:
    """Keep enough un-emitted tail to match secrets split across pipe reads."""
    def __init__(self, secrets):
        secrets = sorted(set(secrets), key=len, reverse=True)
        self.pattern = re.compile(b"|".join(re.escape(s) for s in secrets)) if secrets else None
        self.keep = max((len(s) for s in secrets), default=1) - 1
        self.pending = b""

    def feed(self, data, final=False):
        if not self.pattern:
            return data
        self.pending += data
        safe = len(self.pending) if final else max(0, len(self.pending) - self.keep)
        parts, last = [], 0
        for match in self.pattern.finditer(self.pending):
            if match.start() >= safe:
                break
            parts.extend((self.pending[last:match.start()], REDACTED))
            last = match.end()
        end = max(safe, last)
        parts.append(self.pending[last:end])
        self.pending = self.pending[end:]
        return b"".join(parts)


def _stop(process):
    try:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
    except ProcessLookupError:
        pass


def run(args, cwd, timeout=300, max_output=1024 * 1024, env_allow=(), output_path=None):
    """Run argv without a shell, retaining at most max_output redacted bytes.

    Excess output is drained and discarded until process completion or timeout.
    A descendant holding the output pipe open is subject to the same deadline.
    The caller owns output_path containment; this function never replaces files.
    """
    if (not isinstance(args, (list, tuple)) or not args
            or any(not isinstance(arg, str) or "\x00" in arg for arg in args)
            or not args[0]):
        raise ValueError("Expected executable and argv")
    if not isinstance(timeout, (int, float)) or not 0 < timeout <= 1800:
        raise ValueError("Timeout must be greater than zero and at most 1800 seconds")
    if isinstance(max_output, bool) or not isinstance(max_output, int) or max_output < 1:
        raise ValueError("Output limit must be a positive integer")
    env, secrets, policy = environment(env_allow)
    redactor = Redactor(secrets)
    retained = bytearray()
    truncated = False
    output = None
    process = None
    timed_out = False

    def emit(data):
        nonlocal truncated
        remaining = max_output - len(retained)
        if len(data) > remaining:
            truncated = True
        data = data[:remaining]
        retained.extend(data)
        if output:
            output.write(data)
            output.flush()

    try:
        if output_path is not None:
            fd = os.open(Path(output_path), os.O_WRONLY | os.O_CREAT | os.O_EXCL
                         | getattr(os, "O_NOFOLLOW", 0), 0o600)
            output = os.fdopen(fd, "wb")
        try:
            process = subprocess.Popen(args, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       start_new_session=(os.name == "posix"))
        except OSError as exc:
            emit(redactor.feed(("Cannot start command: " + str(exc) + "\n").encode(), final=True))
            code = 127
        else:
            deadline = time.monotonic() + timeout
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                while selector.get_map():
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        timed_out = True
                        _stop(process)
                        break
                    for key, _ in selector.select(min(remaining, 0.1)):
                        chunk = os.read(key.fd, 8192)
                        if not chunk:
                            selector.unregister(key.fileobj)
                        else:
                            emit(redactor.feed(chunk))
            if not timed_out:
                try:
                    process.wait(timeout=max(0.001, deadline - time.monotonic()))
                except subprocess.TimeoutExpired:
                    timed_out = True
                    _stop(process)
            process.wait()
            emit(redactor.feed(b"", final=True))
            if timed_out:
                emit(b"\nAEK: command timed out.\n")
            code = 124 if timed_out else process.returncode
    finally:
        if process is not None:
            if process.poll() is None:
                _stop(process)
                process.wait()
            process.stdout.close()
        if output:
            output.close()
    return {"exit_code": code, "output": bytes(retained).decode("utf-8", errors="replace"),
            "truncated": truncated, "timed_out": timed_out, "env_policy": policy}
