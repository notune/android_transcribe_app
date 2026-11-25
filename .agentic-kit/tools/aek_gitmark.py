#!/usr/bin/env python3
"""Explicit, checksum-verified install of the pinned OntoShip GitMark CLI.

No network operations in plan/check. Never runs downloaded code automatically.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
from urllib.request import Request, urlopen
from urllib.parse import urlparse

PINNED_COMMIT = "cb5de9a4bb6b30654e04777fa2461bf39f9433db"
EXPECTED_GIT_BLOB = "2fac8f1b979bd5b50df73e5f6a9309b54204b9e1"
URL = ("https://raw.githubusercontent.com/vakovalskii/ontoship/" +
       PINNED_COMMIT + "/skills/kb-search/gitmark.py")
DEST = Path(".agentic-kit/profiles/knowledge-base/vendor/gitmark.py")
MAX_BYTES = 1_048_576


def git_blob_sha(content):
    return hashlib.sha1(
        b"blob " + str(len(content)).encode("ascii") + b"\0" + content
    ).hexdigest()


def destination(project):
    if not project.is_dir() or not (project / ".agentic-kit/AGENTS.md").is_file():
        raise ValueError("AEK is not installed in this project")
    profile = project / ".agentic-kit/profiles/knowledge-base/manifest.json"
    if profile.is_symlink() or not profile.is_file():
        raise ValueError("Knowledge Base profile is not installed")
    metadata = json.loads(profile.read_text(encoding="utf-8"))
    if (metadata.get("upstream", {}).get("commit") != PINNED_COMMIT
            or metadata.get("upstream", {}).get("blob_sha1") != EXPECTED_GIT_BLOB):
        raise ValueError("Profile pin mismatch; stop and review the AEK version")
    current = project
    for segment in DEST.parts:
        current = current / segment
        if current.is_symlink():
            raise ValueError("Refusing symlinked destination path: " + str(current))
    return project / DEST


def inspect(project):
    project = project.resolve()
    p = destination(project)
    status = "absent"
    if p.exists():
        if not p.is_file():
            raise ValueError("GitMark destination is not a regular file")
        status = ("verified" if git_blob_sha(p.read_bytes()) == EXPECTED_GIT_BLOB
                  else "conflict")
    return {
        "target": str(p), "status": status,
        "upstream": URL, "upstream_commit": PINNED_COMMIT,
        "expected_git_blob_sha1": EXPECTED_GIT_BLOB,
        "network_required_for_install": True,
        "changes_to_gitignore": False, "executes_code": False
    }


def download():
    req = Request(URL, headers={"User-Agent": "AEK-GitMark-pinned-install"})
    with urlopen(req, timeout=20) as response:
        end = urlparse(response.geturl())
        if end.scheme != "https" or end.hostname != "raw.githubusercontent.com":
            raise ValueError("Unexpected download host")
        content = response.read(MAX_BYTES + 1)
    if len(content) > MAX_BYTES:
        raise ValueError("GitMark source exceeds maximum accepted size")
    if git_blob_sha(content) != EXPECTED_GIT_BLOB:
        raise ValueError("Downloaded GitMark source does not match the pinned Git blob")
    return content


def command(project, mode, allow_network=False):
    state = inspect(project)
    if mode in ("plan", "check"):
        print(json.dumps(state, indent=2, ensure_ascii=False))
        return 0 if mode == "plan" or state["status"] == "verified" else 2
    if state["status"] == "verified":
        print(json.dumps(state, indent=2, ensure_ascii=False))
        return 0
    if state["status"] != "absent":
        raise ValueError("A different GitMark source already exists; do not overwrite")
    if not allow_network:
        raise ValueError("Installation requires explicit --allow-network consent")
    source = download()
    p = Path(state["target"])
    # Create under the private AEK directory, without touching app code.
    p.parent.mkdir(parents=True, exist_ok=True)
    temp_name = None
    try:
        with tempfile.NamedTemporaryFile(dir=p.parent, prefix=".gitmark-", delete=False) as out:
            temp_name = out.name
            out.write(source)
        # Atomic no-overwrite create; even a race cannot replace user files.
        os.link(temp_name, p)
    finally:
        if temp_name and os.path.exists(temp_name):
            os.unlink(temp_name)
    state["status"] = "verified"
    state["installed"] = True
    print(json.dumps(state, indent=2, ensure_ascii=False))
    return 0


def main():
    parser = argparse.ArgumentParser(description="Install pinned OntoShip GitMark by explicit permission")
    parser.add_argument("action", choices=("plan", "install", "check"))
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--allow-network", action="store_true")
    args = parser.parse_args()
    try:
        return command(args.target, args.action, args.allow_network)
    except (ValueError, OSError, json.JSONDecodeError) as e:
        print("AEK GitMark error:", e, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
