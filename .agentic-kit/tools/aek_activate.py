#!/usr/bin/env python3
"""Activate project-local AEK instructions and portable skills, without overwrites."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import sys

START = "<!-- AEK:START -->"
END = "<!-- AEK:END -->"
BLOCK = (START + "\n## Agentic Engineering Kit\n"
         "Before substantial work, read §.agentic-kit/AGENTS.md§, the assigned role "
         "and the current workflow evidence. Use the §aek-*§ skills registered "
         "under §.agents/skills/§.\n" + END + "\n").replace("§", chr(96))

def engine():
    spec = importlib.util.spec_from_file_location("aek_activation_install", Path(__file__).with_name("aek_install.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def safe_source(project):
    kit = project / ".agentic-kit"
    if kit.is_symlink() or not (kit / "AGENTS.md").is_file():
        raise ValueError("AEK is not installed")
    engine().validate_source(kit)
    if not (kit / "skills").is_dir():
        raise ValueError("Unsafe or missing AEK skills")
    return kit

def portable_skill(src, new_name):
    if not src.startswith("---\n"):
        raise ValueError("Missing skill frontmatter")
    lines = src.splitlines(keepends=True)
    try:
        end = next(i for i in range(1, len(lines)) if lines[i].strip() == "---")
    except StopIteration:
        raise ValueError("Unclosed skill frontmatter")
    if not any(x.startswith("description:") for x in lines[1:end]):
        raise ValueError("Missing skill description")
    found = False
    for i in range(1, end):
        if lines[i].startswith("name:"):
            lines[i] = "name: " + new_name + "\n"
            found = True
            break
    if not found:
        raise ValueError("Missing skill name")
    note = ("\n> Project AEK skill. Canonical instructions are in "
            "§.agentic-kit/skills/§. References to §docs/§ or §templates/§ "
            "refer to paths inside §.agentic-kit/§ at the project root.\n\n").replace("§", chr(96))
    return "".join(lines[:end+1]) + note + "".join(lines[end+1:])

def activation_skill_folders(kit, profiles):
    folders = list((kit / "skills").iterdir())
    for profile in sorted(set(profiles)):
        if profile not in ("web-development", "qa-playwright", "security-audit", "database-postgres", "ai-evaluation", "knowledge-base", "codebase-audit"):
            raise ValueError("Unknown AEK profile: " + profile)
        base = kit / "profiles" / profile / "skills"
        if base.is_symlink() or not base.is_dir():
            raise ValueError("Profile not installed or unsafe: " + profile)
        folders.extend(base.iterdir())
    return sorted(folders, key=lambda p: (p.name, str(p)))


def build(project, kit, profiles=(), root_bytes=None):
    safety = engine()
    project = safety.canonical_root(project)
    kit = safety.validate_source(kit)
    files = {}
    for folder in activation_skill_folders(kit, profiles):
        if folder.is_symlink():
            raise ValueError("Symlinked skill: " + folder.name)
        if not folder.is_dir():
            continue
        name = folder.name if folder.name.startswith("aek-") else "aek-" + folder.name
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name) or len(name) > 64:
            raise ValueError("Invalid portable skill name")
        skill = folder / "SKILL.md"
        if skill.is_symlink() or not skill.is_file():
            raise ValueError("Missing skill file: " + folder.name)
        destination = Path(".agents/skills") / name / "SKILL.md"
        if destination in files:
            raise ValueError("Duplicate skill registration: " + name)
        files[destination] = portable_skill(
            skill.read_text(encoding="utf-8"), name).encode("utf-8")
        for p in folder.rglob("*"):
            if p.is_symlink():
                raise ValueError("Symlink in skill: " + str(p))
            if p.is_file() and p != skill:
                files[Path(".agents/skills") / name / p.relative_to(folder)] = p.read_bytes()
    root = safety.safe_path(project, "AGENTS.md")
    old = (root_bytes.decode("utf-8") if root_bytes is not None
           else root.read_text(encoding="utf-8") if root.exists() else "")
    if (START in old) != (END in old):
        raise ValueError("Partial activation block")
    if START in old:
        if BLOCK not in old:
            raise ValueError("Activation block changed; manual merge required")
        instruction = "unchanged"
        new_root = old
    elif ".agentic-kit/AGENTS.md" in old:
        instruction = "already_referenced"
        new_root = old
    else:
        instruction = "append" if old else "create"
        new_root = old + ("" if not old or old.endswith("\n\n") else "\n") + BLOCK
    return files, new_root, instruction


def plan(project, profiles=()):
    safety = engine()
    project = safety.canonical_root(project)
    kit = safe_source(project)
    files, new_root, instruction = build(project, kit, profiles)
    desired = {str(k): v for k, v in files.items()}
    desired["AGENTS.md"] = new_root.encode("utf-8")
    manifest_path = safety.safe_path(project, safety.MANIFEST)
    if not manifest_path.is_file():
        raise ValueError("Missing installation manifest")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    old_skills = manifest.get("managed_skills", {})
    if not isinstance(old_skills, dict):
        raise ValueError("Invalid managed skills registry")
    manifest["managed_skills"] = {**old_skills, **{str(k): safety.sha(v) for k, v in files.items()}}
    old_profiles = manifest.get("active_profiles", [])
    if not isinstance(old_profiles, list) or any(not isinstance(x, str) for x in old_profiles):
        raise ValueError("Invalid active profiles registry")
    manifest["active_profiles"] = sorted(set(old_profiles) | set(profiles))
    desired[safety.MANIFEST] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    context = {"action": "activation", "profiles": sorted(set(profiles))}
    result, desired, _ = safety.files_plan(project, desired,
        replace=("AGENTS.md", safety.MANIFEST), context=context)
    result.update({"root_instructions": instruction,
                  "skills_total": sum(x.name == "SKILL.md" for x in files),
                  "provider_visibility": "NOT VERIFIED: inspect a new provider session"})
    return result, desired, new_root


def command(project, action, profiles=(), plan_id=None):
    if action not in ("plan", "apply", "check"):
        raise ValueError("Unknown activation action")
    safety = engine()
    project = safety.canonical_root(project)
    result, files, root_text = plan(project, profiles)
    if action == "apply":
        expected = {name: safety.observe(project, name) for name in files}
        safety.transact(project, result, files, expected, plan_id)
    result["applied"] = action == "apply"
    result["project_files_ready"] = (not result["conflicts"] and not result["add"]
        and not result["update"]
        and result["root_instructions"] in ("unchanged", "already_referenced")) if action == "check" else None
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if action == "check":
        return 0 if result["project_files_ready"] else 2
    return 0 if not result["conflicts"] else 2

def main():
    parser = argparse.ArgumentParser(description="Register AEK skills and activate project guidance")
    parser.add_argument("action", choices=("plan", "apply", "check"))
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--profile", action="append", choices=("web-development", "qa-playwright", "security-audit", "database-postgres", "ai-evaluation", "knowledge-base", "codebase-audit"), default=[],
                        help="Opt-in skill profile (repeatable); never installs external packages")
    parser.add_argument("--plan-id", help="Exact ID from activation plan; required for apply")
    args = parser.parse_args()
    try:
        return command(args.target, args.action, args.profile, args.plan_id)
    except (OSError, ValueError, UnicodeError) as exc:
        print("AEK activation error:", exc, file=sys.stderr)
        return 2

if __name__ == "__main__":
    sys.exit(main())
