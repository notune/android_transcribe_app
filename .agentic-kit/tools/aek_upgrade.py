#!/usr/bin/env python3
"""Conservative, manifest-based AEK upgrades with review and reversible backups.

No network access. Does not change application code, custom instructions,
OpenSpec, task evidence, or unregistered skills.
"""
import difflib
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import tempfile
from datetime import datetime, timezone
from uuid import uuid4

MANIFEST = ".agentic-kit/install-manifest.json"
HISTORY = ".agentic-kit/upgrade-history"
HEX = re.compile(r"^[a-f0-9]{64}$")


def helper(name):
    spec = importlib.util.spec_from_file_location("aek_upgrade_" + name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(obj):
    return (json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def safe_name(value, allow_agents=True):
    if not isinstance(value, str) or not value or "\\" in value:
        raise ValueError("Invalid managed path")
    p = PurePosixPath(value)
    if p.is_absolute() or any(x in ("..", ".") for x in value.split("/")) or p.as_posix() != value:
        raise ValueError("Unsafe managed path: " + value)
    permitted = (value.startswith(".agentic-kit/")
                 and not value.startswith(HISTORY + "/"))
    if allow_agents:
        parts = p.parts
        permitted = permitted or (len(parts) >= 4 and parts[:2] == (".agents", "skills")
                   and parts[2].startswith("aek-"))
    if not permitted:
        raise ValueError("Managed path outside AEK: " + value)
    return value


def safe_target(root, name, allow_agents=True):
    safe_name(name, allow_agents=allow_agents)
    p = root
    for segment in PurePosixPath(name).parts:
        p = p / segment
        if p.is_symlink():
            raise ValueError("Refusing symlinked path: " + str(p))
    if p.exists() and not p.is_file():
        raise ValueError("Expected regular file: " + str(p))
    return p


def read_manifest(root):
    if not root.is_dir():
        raise ValueError("Target project does not exist")
    p = safe_target(root, MANIFEST, allow_agents=False)
    if p.is_symlink() or (root / ".agentic-kit").is_symlink() or not p.is_file():
        raise ValueError("Missing or unsafe AEK installation manifest")
    contents = p.read_bytes()
    data = json.loads(contents)
    if not isinstance(data, dict) or not isinstance(data.get("kit_version"), str):
        raise ValueError("Invalid AEK installation manifest")
    if data.get("schema_version", 1) != 1:
        raise ValueError("Unsupported installation manifest schema")
    if "source_identity" in data:
        helper("aek_environment").validate_identity(data["source_identity"])
    if data.get("mode") not in ("new", "existing"):
        raise ValueError("Invalid AEK installation mode")
    for field in ("managed_files", "managed_skills"):
        entries = data.get(field, {})
        if not isinstance(entries, dict):
            raise ValueError("Invalid " + field)
        for name, digest in entries.items():
            if name == MANIFEST:
                raise ValueError("Manifest cannot own itself")
            if name == "AGENTS.md" and field == "managed_files":
                pass  # Legacy generated project entry point is preserved, never updated.
            else:
                safe_name(name, allow_agents=(field == "managed_skills"))
                if field == "managed_files" and not name.startswith(".agentic-kit/"):
                    raise ValueError("Invalid managed source path")
                if field == "managed_skills" and not name.startswith(".agents/skills/aek-"):
                    raise ValueError("Invalid registered skill path")
            if not isinstance(digest, str) or not HEX.fullmatch(digest):
                raise ValueError("Invalid checksum for " + name)
    profiles = data.get("active_profiles", [])
    if not isinstance(profiles, list) or any(not isinstance(x, str) for x in profiles):
        raise ValueError("Invalid active profiles")
    return data, contents


def source_map(source, source_files):
    if not source.is_dir():
        raise ValueError("AEK source directory is missing")
    items = {}
    for relative, content in source_files(source):
        p = source / relative
        if p.is_symlink() or not p.is_file():
            raise ValueError("Unsafe AEK source file: " + str(relative))
        dest = ".agentic-kit/" + relative.as_posix()
        safe_name(dest, allow_agents=False)
        if dest in items:
            raise ValueError("Duplicate source file: " + dest)
        items[dest] = content
    if ".agentic-kit/AGENTS.md" not in items or ".agentic-kit/VERSION" not in items:
        raise ValueError("AEK source is incomplete")
    return items


def skill_assets(kit, profile, portable_skill):
    if profile is None:
        directory = kit / "skills"
    else:
        directory = kit / "profiles" / profile / "skills"
    if directory.is_symlink() or not directory.is_dir():
        if profile is None:
            raise ValueError("Missing base skills")
        return {}
    items = {}
    for folder in sorted(directory.iterdir()):
        if folder.is_symlink():
            raise ValueError("Unsafe skill directory")
        if not folder.is_dir():
            continue
        name = folder.name if folder.name.startswith("aek-") else "aek-" + folder.name
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name) or len(name) > 64:
            raise ValueError("Invalid skill name: " + name)
        skill = folder / "SKILL.md"
        if skill.is_symlink() or not skill.is_file():
            raise ValueError("Missing skill: " + str(skill))
        base = ".agents/skills/" + name + "/"
        items[base + "SKILL.md"] = portable_skill(
            skill.read_text(encoding="utf-8"), name).encode("utf-8")
        for item in folder.rglob("*"):
            if item.is_symlink():
                raise ValueError("Unsafe skill asset: " + str(item))
            if item.is_file() and item != skill:
                rel = item.relative_to(folder).as_posix()
                dest = base + rel
                safe_name(dest)
                items[dest] = item.read_bytes()
    return items


def enabled_skills(source, target, manifest, portable_skill):
    old_kit = target / ".agentic-kit"
    core_old = skill_assets(old_kit, None, portable_skill)
    profiles_dir = old_kit / "profiles"
    old_profiles = {}
    if profiles_dir.is_symlink():
        raise ValueError("Symlinked profiles directory")
    if profiles_dir.is_dir():
        for p in sorted(profiles_dir.iterdir()):
            if p.is_symlink():
                raise ValueError("Symlinked profile directory")
            if p.is_dir() and (p / "skills").is_dir():
                old_profiles[p.name] = skill_assets(old_kit, p.name, portable_skill)
    tracked = manifest.get("managed_skills", {})
    core_active = (any((target / name).exists() for name in core_old)
                   or any(name.startswith(".agents/skills/aek-") and
                          name in core_old for name in tracked))
    active_profiles = set(manifest.get("active_profiles", []))
    for profile, assets in old_profiles.items():
        if any((target / name).exists() or name in tracked for name in assets):
            active_profiles.add(profile)
    new_kit = source
    new_assets = {}
    if core_active:
        new_assets.update(skill_assets(new_kit, None, portable_skill))
    for profile in sorted(active_profiles):
        assets = skill_assets(new_kit, profile, portable_skill)
        if not assets:
            raise ValueError("Previously active profile unavailable in new kit: " + profile)
        if set(new_assets).intersection(assets):
            raise ValueError("Duplicate AEK skill across profiles")
        new_assets.update(assets)

    # v1.6 and earlier have no manifest of activated copies. Reconstruct only
    # expected bytes from installed source, never guess about unknown skills.
    old_expected = dict(tracked)
    for name, content in core_old.items():
        if (target / name).exists() and name not in old_expected:
            old_expected[name] = sha(content)
    for profile in sorted(active_profiles):
        for name, content in old_profiles.get(profile, {}).items():
            if (target / name).exists() and name not in old_expected:
                old_expected[name] = sha(content)
    return new_assets, old_expected, sorted(active_profiles)


def diff_text(before, after, path):
    if before is None or after is None:
        return ""
    try:
        old_text = before.decode("utf-8").splitlines(keepends=True)
        new_text = after.decode("utf-8").splitlines(keepends=True)
    except UnicodeError:
        return ""
    return "".join(list(difflib.unified_diff(
        old_text, new_text, fromfile="installed/" + path, tofile="source/" + path))[:100])


def prepare(source, target, source_files, portable_skill, include_diff=False):
    source, target = source.resolve(), target.resolve()
    if source == target or source == target / ".agentic-kit" or source in target.parents:
        raise ValueError("Source must be a separate, reviewed AEK clone")
    try:
        source.relative_to(target)
    except ValueError:
        pass
    else:
        raise ValueError("Do not clone the AEK source inside the target project")
    manifest, manifest_contents = read_manifest(target)
    files = source_map(source, source_files)
    skills, old_skills, profiles = enabled_skills(source, target, manifest, portable_skill)
    old = manifest["managed_files"]
    desired = {**files, **skills}
    old_managed = {**{x: y for x, y in old.items()
                       if x != "AGENTS.md" and x != ".agentic-kit/ADOPTION.md"},
                   **old_skills}
    # Preserve the generated adoption report and user-owned root instructions.
    preserved = [x for x in ("AGENTS.md", ".agentic-kit/ADOPTION.md")
                 if x in old or (target / x).exists() and x == "AGENTS.md"]
    operations, conflicts, same = [], [], []
    for name in sorted(set(desired) | set(old_managed)):
        path = safe_target(target, name)
        content = path.read_bytes() if path.exists() else None
        previous_hash = old_managed.get(name)
        wanted = desired.get(name)
        if wanted is None:
            if content is None:
                continue
            if previous_hash is not None and sha(content) == previous_hash:
                action = "remove"
            else:
                conflicts.append({"path": name, "reason": "old managed file changed"})
                continue
        elif content is None:
            if previous_hash is not None:
                conflicts.append({"path": name, "reason": "previously managed file missing"})
                continue
            action = "add"
        elif content == wanted:
            same.append(name)
            continue
        elif previous_hash is None:
            conflicts.append({"path": name, "reason": "existing unowned file"})
            continue
        elif sha(content) != previous_hash:
            conflicts.append({"path": name, "reason": "local modifications since installation"})
            continue
        else:
            action = "update"
        record = {"path": name, "action": action,
                  "before": sha(content) if content is not None else None,
                  "after": sha(wanted) if wanted is not None else None}
        if include_diff and action == "update":
            record["diff"] = diff_text(content, wanted, name)
        operations.append(record)
    old_adoption = old.get(".agentic-kit/ADOPTION.md")
    new_manifest = {
        "schema_version": 1,
        "source_identity": helper("aek_environment").identity(source, source_files),
        "kit_version": (source / "VERSION").read_text(encoding="utf-8").strip(),
        "mode": manifest["mode"],
        "managed_files": {**{k: sha(v) for k, v in files.items()},
                          **({".agentic-kit/ADOPTION.md": old_adoption} if old_adoption else {})},
        "managed_skills": {k: sha(v) for k, v in skills.items()},
        "active_profiles": profiles,
    }
    new_manifest_bytes = json_bytes(new_manifest)
    if manifest_contents != new_manifest_bytes:
        operations.append({"path": MANIFEST, "action": "update",
                           "before": sha(manifest_contents), "after": sha(new_manifest_bytes)})
    payload = {
        "target": str(target),
        "from_version": manifest["kit_version"],
        "to_version": new_manifest["kit_version"],
        "active_profiles": profiles,
        "from_source_identity": manifest.get("source_identity"),
        "to_source_identity": new_manifest["source_identity"],
        "operations": [{k: item[k] for k in ("path", "action", "before", "after")}
                       for item in operations],
        "conflicts": conflicts,
    }
    plan_id = sha(json_bytes(payload))[:20]
    summary = {**payload, "plan_id": plan_id, "unchanged_count": len(same),
               "preserved_local": preserved,
               "safe_to_apply": not conflicts,
               "note": "Dry-run. Unregistered skills and product files are not touched."}
    if include_diff:
        summary["operations"] = operations
    desired[MANIFEST] = new_manifest_bytes
    return summary, desired


def assert_plan(expected, actual):
    if not expected or not re.fullmatch(r"[a-f0-9]{20}", expected) or expected != actual:
        raise ValueError("Plan changed: run preview again and supply its --plan-id")


def atomic_write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".aek-", delete=False) as f:
            tmp = Path(f.name)
            f.write(content)
        os.replace(tmp, path)
    finally:
        if tmp and tmp.exists():
            tmp.unlink()


def restore(target, operations, originals):
    errors = []
    for item in reversed(operations):
        name = item["path"]
        try:
            path = safe_target(target, name)
            prior = originals[name]
            if prior is None:
                if path.exists():
                    path.unlink()
            else:
                atomic_write(path, prior)
        except (OSError, ValueError) as e:
            errors.append(str(e))
    if errors:
        raise ValueError("Automatic restore incomplete: " + "; ".join(errors))


def history_dir(target):
    directory = target / HISTORY
    p = target
    for segment in PurePosixPath(HISTORY).parts:
        p = p / segment
        if p.is_symlink():
            raise ValueError("Unsafe upgrade history directory")
    if directory.exists() and not directory.is_dir():
        raise ValueError("Upgrade history is not a directory")
    return directory


def perform_upgrade(source, target, source_files, portable_skill, plan_id, include_diff=False):
    summary, desired = prepare(source, target, source_files, portable_skill, include_diff)
    assert_plan(plan_id, summary["plan_id"])
    if summary["conflicts"]:
        raise ValueError("Conflicting local changes; no files were written")
    ops = summary["operations"]
    if not ops:
        return {**summary, "applied": False, "reason": "already current"}
    target = target.resolve()
    backup_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:8]
    directory = history_dir(target) / backup_id
    directory.mkdir(parents=True, exist_ok=False)
    originals = {}
    for item in ops:
        name = item["path"]
        path = safe_target(target, name)
        before = path.read_bytes() if path.exists() else None
        if (sha(before) if before is not None else None) != item["before"]:
            raise ValueError("File changed after preview; no upgrade applied: " + name)
        originals[name] = before
        if before is not None:
            saved = directory / "before" / name
            saved.parent.mkdir(parents=True, exist_ok=True)
            saved.write_bytes(before)
    record = {"id": backup_id, "state": "prepared",
              "plan_id": summary["plan_id"], "operations": ops}
    (directory / "record.json").write_bytes(json_bytes(record))
    done = []
    try:
        for item in ops:
            name = item["path"]
            path = safe_target(target, name)
            if item["action"] == "remove":
                path.unlink()
            elif item["action"] == "add":
                path.parent.mkdir(parents=True, exist_ok=True)
                with path.open("xb") as f:
                    f.write(desired[name])
            else:
                atomic_write(path, desired[name])
            done.append(item)
        record["state"] = "applied"
        atomic_write(directory / "record.json", json_bytes(record))
    except Exception:
        restore(target, done, originals)
        raise
    return {**summary, "applied": True, "backup_id": backup_id}


def prepare_rollback(target, backup_id):
    if not isinstance(backup_id, str) or not re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{8}", backup_id):
        raise ValueError("Invalid backup ID")
    target = target.resolve()
    directory = history_dir(target) / backup_id
    if directory.is_symlink() or not (directory / "record.json").is_file():
        raise ValueError("Backup record not found")
    record_file = directory / "record.json"
    if record_file.is_symlink():
        raise ValueError("Unsafe backup record")
    record = json.loads(record_file.read_text(encoding="utf-8"))
    if record.get("state") != "applied" or record.get("id") != backup_id:
        raise ValueError("Backup is not a completed upgrade")
    ops = record.get("operations")
    if not isinstance(ops, list) or not ops:
        raise ValueError("Invalid backup record")
    conflicts = []
    for item in ops:
        name = item["path"]
        if name != MANIFEST:
            safe_name(name)
        path = safe_target(target, name)
        content = path.read_bytes() if path.exists() else None
        if (sha(content) if content is not None else None) != item["after"]:
            conflicts.append({"path": name, "reason": "changed after upgrade"})
        if item["before"] is not None:
            saved = directory / "before" / name
            if saved.is_symlink() or not saved.is_file() or sha(saved.read_bytes()) != item["before"]:
                raise ValueError("Invalid or missing backup content: " + name)
    payload = {"target": str(target), "backup_id": backup_id,
               "operations": ops, "conflicts": conflicts}
    return {**payload, "plan_id": sha(json_bytes(payload))[:20],
            "safe_to_apply": not conflicts, "note": "Rollback preview, no files changed."}


def perform_rollback(target, backup_id, plan_id):
    summary = prepare_rollback(target, backup_id)
    assert_plan(plan_id, summary["plan_id"])
    if summary["conflicts"]:
        raise ValueError("Files changed after upgrade; rollback blocked")
    target = target.resolve()
    directory = history_dir(target) / backup_id
    ops = summary["operations"]
    before_rollback = {}
    for item in ops:
        p = safe_target(target, item["path"])
        before_rollback[item["path"]] = p.read_bytes() if p.exists() else None
    completed = []
    try:
        for item in reversed(ops):
            name = item["path"]
            path = safe_target(target, name)
            if item["before"] is None:
                if path.exists():
                    path.unlink()
            else:
                atomic_write(path, (directory / "before" / name).read_bytes())
            completed.append(item)
        record = json.loads((directory / "record.json").read_text(encoding="utf-8"))
        record["state"] = "rolled_back"
        atomic_write(directory / "record.json", json_bytes(record))
    except Exception:
        restore(target, completed, before_rollback)
        raise
    return {**summary, "rolled_back": True}
