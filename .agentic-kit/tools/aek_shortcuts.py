#!/usr/bin/env python3
"""Register, update and roll back owned AEK commands. Standard library only.

The registry records file ownership, not provider visibility or authenticated
approval. Plans bind source bytes, destination bytes, provider set and root.
"""
import argparse
import base64
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from uuid import uuid4

ACTIONS = ("greenfield", "brownfield", "update", "change", "fix", "status",
           "verify", "resume", "audit", "doctor", "rollback", "help")
NAMES = tuple("aek-" + name for name in ACTIONS)
PROVIDERS = ("claude", "codex", "opencode", "pi")
SOURCE = Path(__file__).resolve().parents[1]
MANIFEST = ".aek/shortcuts-manifest.json"
HISTORY = ".aek/shortcuts-history"
LOCK = ".aek/shortcuts.lock"
# Exact hashes emitted by v2.1.0-rc.1, never a prefix-based delete rule.
_LEGACY_SKILLS = dict(zip(("greenfield", "brownfield", "update"), (
    "56ad93882cc19d5df6cc37a9204f6f3bc5772a35a1ef9e37c115b40fdcfb7d5b",
    "5423cbc779e8c62237896f574a335a1908db44eeab3becb7ea16ce8eaf44cf43",
    "0571d4362c44e264fbe99026046c9fa285991680995cdc653a82401f608fe09a")))
_LEGACY_PROMPTS = dict(zip(("greenfield", "brownfield", "update"), (
    "f629f332e9b0d7347cf7d1d2899f1ca1c81c9cd0a65af14a178fa327cbda6ed0",
    "896aacca0e380a98402bd650180af1b317e3fe7ca7c453309f10ecd28cf3cfad",
    "dcb235836d9d4427b924b25236055230d7946edd6d40c042746a1d8aadf9c50f")))


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, indent=2) + "\n").encode()


def extract(source):
    if not source.startswith("---\n"):
        raise ValueError("Shortcut SKILL.md has no YAML frontmatter")
    chunks = source.split("---\n", 2)
    if len(chunks) != 3:
        raise ValueError("Shortcut SKILL.md has invalid frontmatter")
    fields = dict(line.split(":", 1) for line in chunks[1].splitlines() if ":" in line)
    description, hint = fields.get("description", "").strip(), fields.get("argument-hint", "").strip()
    if not description or not hint or not chunks[2].strip():
        raise ValueError("Shortcut frontmatter or body incomplete")
    return description, hint, chunks[2].strip() + "\n"


def render(source, name, provider):
    description, hint, body = extract(source)
    if name not in NAMES or provider not in PROVIDERS:
        raise ValueError("Unknown command or provider")
    if not re.search(r"^name:\s*" + re.escape(name) + r"$", source, re.M):
        raise ValueError("Incorrect shortcut skill name")
    if provider == "codex":
        return source.encode()
    invocation = "\ndisable-model-invocation: true" if provider == "claude" else ""
    return ("---\ndescription: " + description + "\nargument-hint: " + hint
            + invocation + "\n---\n\n" + body + "\nUser arguments: $ARGUMENTS\n").encode()


def relpath(scope, provider, name):
    if scope not in ("global", "project") or provider not in PROVIDERS or name not in NAMES:
        raise ValueError("Unknown scope, provider or shortcut")
    filename = name.replace("-", "_", 1) + ".md"
    return {
        "claude": Path(".claude/commands") / filename,
        "codex": Path(".agents/skills") / name / "SKILL.md",
        "opencode": Path(".config/opencode/commands" if scope == "global" else ".opencode/commands") / filename,
        "pi": Path(".pi/agent/prompts" if scope == "global" else ".pi/prompts") / filename,
    }[provider]


def legacy_paths(scope, provider):
    if provider in ("claude", "codex"):
        base = ".claude/skills" if provider == "claude" else ".agents/skills"
        return {str(Path(base) / name / "SKILL.md"): digest for name, digest in _LEGACY_SKILLS.items()}
    base = (".config/opencode/commands" if scope == "global" else ".opencode/commands") if provider == "opencode" else (".pi/agent/prompts" if scope == "global" else ".pi/prompts")
    return {str(Path(base) / (name + ".md")): digest for name, digest in _LEGACY_PROMPTS.items()}


def target_root(scope, target=None, home=None):
    if scope == "global":
        if target is not None:
            raise ValueError("--target is only for --scope project")
        base = Path(home) if home is not None else Path.home()
    elif scope == "project":
        if target is None or home is not None:
            raise ValueError("--scope project needs --target and cannot use --home")
        base = Path(target)
    else:
        raise ValueError("Unknown scope")
    if base.is_symlink() or not base.is_dir():
        raise ValueError("Shortcut root must be an existing real directory")
    return base.resolve()


def ensure_safe_path(root, relative):
    relative = Path(relative)
    if relative.is_absolute() or not relative.parts or ".." in relative.parts:
        raise ValueError("Unsafe shortcut path")
    current = root
    for part in relative.parts:
        current /= part
        if current.is_symlink():
            raise ValueError("Symlink in shortcut path: " + str(relative))
        if current != root / relative and current.exists() and not current.is_dir():
            raise ValueError("Non-directory shortcut parent: " + str(relative))
    if current.exists() and not current.is_file():
        raise ValueError("Non-file shortcut target: " + str(relative))
    return current


def read_file(root, relative):
    path = ensure_safe_path(root, relative)
    return path.read_bytes() if path.exists() else None


def read_manifest(root, scope):
    raw = read_file(root, MANIFEST)
    data = json.loads(raw) if raw is not None else {"schema_version": 1, "scope": scope, "providers": {}}
    if not isinstance(data, dict) or data.get("schema_version") != 1 or data.get("scope") != scope or not isinstance(data.get("providers"), dict):
        raise ValueError("Invalid shortcut manifest")
    for provider, record in data["providers"].items():
        if provider not in PROVIDERS or not isinstance(record, dict) or not isinstance(record.get("files"), dict) or not isinstance(record.get("kit_version"), str):
            raise ValueError("Invalid shortcut provider record")
        allowed = {str(relpath(scope, provider, name)) for name in NAMES}
        for name, digest in record["files"].items():
            if name not in allowed or not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError("Invalid owned shortcut path or checksum")
    return data, raw


def plan(scope, root, providers=None, source=None):
    root = target_root(scope, **({"home": root} if scope == "global" else {"target": root}))
    source = Path(source or SOURCE).resolve()
    providers = tuple(sorted(set(providers or PROVIDERS)))
    if any(provider not in PROVIDERS for provider in providers):
        raise ValueError("Unknown shortcut provider")
    manifest, raw_manifest = read_manifest(root, scope)
    version_bytes = read_file(source, "VERSION")
    if not version_bytes or not version_bytes.strip():
        raise ValueError("Missing source VERSION")
    version = version_bytes.decode().strip()
    files, observations, operations, unchanged, conflicts, unmanaged_legacy = {}, {}, [], [], [], []
    updated = json.loads(json.dumps(manifest))
    for provider in providers:
        old = manifest["providers"].get(provider, {}).get("files", {})
        owned = {}
        for name in NAMES:
            source_file = read_file(source, Path("shortcuts") / name / "SKILL.md")
            if source_file is None:
                raise ValueError("Missing shortcut source: " + name)
            rel = relpath(scope, provider, name)
            content = render(source_file.decode(), name, provider)
            files[rel] = content
            existing = read_file(root, rel)
            before = sha(existing) if existing is not None else None
            after = sha(content)
            observations[str(rel)] = {"before": before, "after": after}
            if before == after:
                unchanged.append(str(rel))
                if str(rel) in old:
                    owned[str(rel)] = after
            elif before is None and str(rel) not in old:
                operations.append({"path": str(rel), "action": "add", "before": None, "after": after})
                owned[str(rel)] = after
            elif before is not None and old.get(str(rel)) == before:
                operations.append({"path": str(rel), "action": "update", "before": before, "after": after})
                owned[str(rel)] = after
            else:
                conflicts.append(str(rel))
                if str(rel) in old:
                    owned[str(rel)] = old[str(rel)]
        for rel, digest in legacy_paths(scope, provider).items():
            existing = read_file(root, rel)
            before = sha(existing) if existing is not None else None
            observations[rel] = {"before": before, "after": None}
            if before == digest:
                operations.append({"path": rel, "action": "remove", "before": before, "after": None})
                files[Path(rel)] = None
            elif before is not None:
                unmanaged_legacy.append(rel)
        updated["providers"][provider] = {"kit_version": version, "files": owned}
    manifest_bytes = encoded(updated)
    files[Path(MANIFEST)] = manifest_bytes
    observations[MANIFEST] = {"before": sha(raw_manifest) if raw_manifest is not None else None, "after": sha(manifest_bytes)}
    if raw_manifest != manifest_bytes:
        operations.append({"path": MANIFEST, "action": "update" if raw_manifest else "add", **observations[MANIFEST]})
    material = {"scope": scope, "target": str(root), "providers": list(providers), "version": version, "files": observations}
    result = {**material, "plan_id": sha(encoded(material))[:24], "operations": operations,
              "commands": ["/aek_" + name for name in ACTIONS],
              "codex_skills": ["$" + name for name in NAMES],
              "add": [x["path"] for x in operations if x["action"] == "add" and x["path"] != MANIFEST],
              "update": [x["path"] for x in operations if x["action"] == "update" and x["path"] != MANIFEST],
              "remove": [x["path"] for x in operations if x["action"] == "remove"],
              "unchanged": unchanged, "conflicts": conflicts, "unmanaged_legacy": unmanaged_legacy,
              "safe_to_apply": not conflicts, "provider_visibility": "unverified"}
    return result, files


@contextmanager
def locked(root):
    path = ensure_safe_path(root, LOCK)
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError as exc:
        raise ValueError("Shortcut operation already running; inspect lock before manual recovery") from exc
    try:
        os.close(fd)
        yield
    finally:
        path.unlink()


def atomic_write(path, content, create=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".aek-", delete=False) as f:
            temp = Path(f.name)
            f.write(content)
        if create:
            os.link(temp, path)  # atomic publish without clobbering a new file
        else:
            os.replace(temp, path)
    finally:
        if temp and temp.exists():
            temp.unlink()


def transact(root, operations, files):
    originals, completed = {}, []
    for op in operations:
        content = read_file(root, op["path"])
        if (sha(content) if content is not None else None) != op["before"]:
            raise ValueError("File changed after plan: " + op["path"])
        originals[op["path"]] = content
    try:
        for op in operations:
            path = ensure_safe_path(root, op["path"])
            current = read_file(root, op["path"])
            if (sha(current) if current is not None else None) != op["before"]:
                raise ValueError("File changed during apply: " + op["path"])
            if op["after"] is None:
                path.unlink()
            else:
                atomic_write(path, files[Path(op["path"])], create=op["before"] is None)
            completed.append(op)
    except Exception as original_error:
        errors = []
        for op in reversed(completed):
            try:
                path = ensure_safe_path(root, op["path"])
                current = read_file(root, op["path"])
                if (sha(current) if current is not None else None) != op["after"]:
                    raise ValueError("Concurrent changes during recovery: " + op["path"])
                if originals[op["path"]] is None:
                    path.unlink()
                else:
                    atomic_write(path, originals[op["path"]], create=not path.exists())
            except (OSError, ValueError) as exc:
                errors.append(str(exc))
        if errors:
            raise ValueError("Recovery incomplete: " + "; ".join(errors)) from original_error
        raise
    return originals


def apply_plan(scope, root, providers, plan_id, source=None):
    root = target_root(scope, **({"home": root} if scope == "global" else {"target": root}))
    with locked(root):
        result, files = plan(scope, root, providers, source)
        if not plan_id or result["plan_id"] != plan_id:
            raise ValueError("Missing or stale plan_id; review a fresh plan")
        if result["conflicts"]:
            raise ValueError("Conflicting shortcuts; no files written: " + ", ".join(result["conflicts"]))
        if not result["operations"]:
            return {**result, "applied": False, "reason": "already current"}
        backup_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:8]
        backup_path = Path(HISTORY) / backup_id / "record.json"
        record = {"id": backup_id, "scope": scope, "state": "prepared", "operations": result["operations"], "before": {}}
        for op in result["operations"]:
            content = read_file(root, op["path"])
            record["before"][op["path"]] = base64.b64encode(content).decode() if content is not None else None
        atomic_write(ensure_safe_path(root, backup_path), encoded(record), create=True)
        originals = transact(root, result["operations"], files)
        try:
            record["state"] = "applied"
            atomic_write(ensure_safe_path(root, backup_path), encoded(record))
        except Exception:
            reverse = [{"path": x["path"], "before": x["after"], "after": x["before"]} for x in reversed(result["operations"])]
            transact(root, reverse, {Path(k): v for k, v in originals.items()})
            raise
        return {**result, "applied": True, "backup_id": backup_id}


def prepare_rollback(root, backup_id):
    root = Path(root).resolve()
    if not re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{8}", backup_id):
        raise ValueError("Invalid shortcut backup ID")
    raw = read_file(root, Path(HISTORY) / backup_id / "record.json")
    if raw is None:
        raise ValueError("Shortcut backup not found")
    record = json.loads(raw)
    if record.get("id") != backup_id or record.get("state") != "applied" or record.get("scope") not in ("global", "project"):
        raise ValueError("Shortcut backup is not an applied transaction")
    allowed = {MANIFEST}
    for p in PROVIDERS:
        allowed.update(str(relpath(record["scope"], p, name)) for name in NAMES)
        allowed.update(legacy_paths(record["scope"], p))
    observations, operations, conflicts = {}, [], []
    for op in reversed(record["operations"]):
        name = op["path"]
        if name not in allowed:
            raise ValueError("Unsafe shortcut backup path")
        saved = record["before"][name]
        old = base64.b64decode(saved, validate=True) if saved is not None else None
        if (sha(old) if old is not None else None) != op["before"]:
            raise ValueError("Damaged shortcut backup")
        current = read_file(root, name)
        observed = sha(current) if current is not None else None
        observations[name] = observed
        if observed != op["after"]:
            conflicts.append(name)
        operations.append({"path": name, "before": op["after"], "after": op["before"]})
    material = {"target": str(root), "backup_id": backup_id, "record_sha256": sha(raw), "observed": observations}
    return {**material, "plan_id": sha(encoded(material))[:24], "operations": operations,
            "conflicts": conflicts, "safe_to_apply": not conflicts}


def perform_rollback(root, backup_id, plan_id):
    root = Path(root).resolve()
    with locked(root):
        result = prepare_rollback(root, backup_id)
        if not plan_id or result["plan_id"] != plan_id:
            raise ValueError("Missing or stale rollback plan_id")
        if result["conflicts"]:
            raise ValueError("Shortcut files changed since update; rollback blocked")
        record_path = ensure_safe_path(root, Path(HISTORY) / backup_id / "record.json")
        record = json.loads(record_path.read_bytes())
        files = {Path(k): base64.b64decode(v, validate=True) if v is not None else None for k, v in record["before"].items()}
        original = transact(root, result["operations"], files)
        try:
            record["state"] = "rolled_back"
            atomic_write(record_path, encoded(record))
        except Exception:
            reverse = [{"path": x["path"], "before": x["after"], "after": x["before"]} for x in reversed(result["operations"])]
            transact(root, reverse, {Path(k): v for k, v in original.items()})
            raise
        return {**result, "rolled_back": True}


def command(scope, action, providers, target=None, home=None, plan_id=None, backup_id=None):
    root = target_root(scope, target, home)
    if action == "apply":
        result = apply_plan(scope, root, providers, plan_id)
    elif action == "rollback":
        if not backup_id:
            raise ValueError("Rollback requires --backup-id")
        result = perform_rollback(root, backup_id, plan_id) if plan_id else prepare_rollback(root, backup_id)
    else:
        result, _ = plan(scope, root, providers)
        if action == "check":
            result["ready"] = not result["conflicts"] and not result["operations"]
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 2 if result.get("conflicts") or result.get("ready") is False else 0


def main():
    parser = argparse.ArgumentParser(description="Manage prefixed AEK commands using reviewed plans")
    parser.add_argument("action", choices=("plan", "apply", "check", "rollback"))
    parser.add_argument("--scope", choices=("global", "project"), required=True)
    parser.add_argument("--provider", choices=PROVIDERS, action="append", default=[])
    parser.add_argument("--target", type=Path)
    parser.add_argument("--home", type=Path, help=argparse.SUPPRESS)
    parser.add_argument("--plan-id")
    parser.add_argument("--backup-id")
    a = parser.parse_args()
    try:
        return command(a.scope, a.action, a.provider, a.target, a.home, a.plan_id, a.backup_id)
    except (OSError, ValueError, KeyError, TypeError, UnicodeError) as exc:
        print("AEK shortcuts error:", exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
