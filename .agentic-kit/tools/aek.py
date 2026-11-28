#!/usr/bin/env python3
"""AEK reference CLI: safe adoption, local workflow checks, swarm planning.
No third-party dependencies, network access, or credential inspection.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

PHASES = ("discovery", "design", "planning", "implementation", "verification", "release", "done")
FILES = ("AGENTS.md", "VERSION", "tools/aek.py", "tools/aek_activate.py", "tools/aek_gitmark.py", "tools/aek_upgrade.py", "tools/aek_evidence.py", "tools/aek_http_smoke.py", "tools/aek_shortcuts.py", "tools/aek_install.py", "tools/aek_components.py", "tools/aek_workflow.py", "tools/aek_state.py", "tools/aek_execution.py", "tools/aek_environment.py", "tools/aek_process.py", "tools/aek_reviews.py")
FOLDERS = ("docs", "roles", "skills", "templates", "harnesses", "profiles", "shortcuts", "workflows")

def sha(data):
    return hashlib.sha256(data).hexdigest()

def dump(obj):
    if isinstance(obj, dict):
        obj = dict(obj)
        obj.setdefault("schema_version", 1)
        obj.setdefault("ok", not (obj.get("passed") is False or obj.get("conflicts") or obj.get("state") in ("failed", "partial", "recovery_failed")))
    print(json.dumps(obj, indent=2, ensure_ascii=False))

def source_files(root):
    paths = [root / x for x in FILES]
    for folder in FOLDERS:
        if (root / folder).is_dir():
            paths += sorted(x for x in (root / folder).rglob("*") if x.is_file())
    return [(p.relative_to(root), p.read_bytes()) for p in paths if p.is_file()]

def inspect(root):
    """Read filenames and executable presence, not tokens or project content."""
    hints = {
        "python": ("pyproject.toml", "requirements.txt", "Pipfile"),
        "javascript": ("package.json", "pnpm-lock.yaml", "yarn.lock"),
        "rust": ("Cargo.toml",),
        "go": ("go.mod",),
        "java": ("pom.xml", "build.gradle"),
        "ios": ("Podfile",),
        "android": ("settings.gradle", "settings.gradle.kts"),
    }
    tools = ("git", "python3", "codex", "claude", "gemini", "opencode", "openspec", "dsh", "qwen", "pi")
    harness_aliases = {"codex": ("codex",), "opencode": ("opencode",), "dsh": ("dsh",), "qwen-code": ("qwen", "qwen-code"), "pi": ("pi",), "claude-code": ("claude",), "gemini-cli": ("gemini",)}
    return {
        "target": str(root),
        "exists": root.is_dir(),
        "git": (root / ".git").exists(),
        "existing_instructions": [x for x in ("AGENTS.md", "CLAUDE.md", "GEMINI.md") if (root / x).exists()],
        "existing_spec": (root / "openspec").exists(),
        "existing_ci": (root / ".github" / "workflows").exists(),
        "test_locations": [x for x in ("tests", "test", "spec", "e2e") if (root / x).exists()],
        "detected_stacks": {k: [x for x in names if (root / x).exists()] for k, names in hints.items() if any((root / x).exists() for x in names)},
        "cli_on_path": {t: bool(shutil.which(t)) for t in tools},
        "harness_cli_candidates": {h: [cmd for cmd in aliases if shutil.which(cmd)] for h, aliases in harness_aliases.items()},
        "model_availability": "UNVERIFIED: CLI presence does not establish available models, effort, authorization or permission to send code",
        "warning": "Filenames and PATH only; not a complete code, dependency or security audit."
    }

def adoption_note(root):
    current = inspect(root)
    return ("# Brownfield adoption — preliminary report\n\n"
        "Generated from filenames and local PATH only. No application code was modified.\n\n"
        "## Inventory\n\n```json\n" + json.dumps(current, indent=2, ensure_ascii=False)
        + "\n```\n\n"
        "## Required manual review before first change\n\n"
        "- [ ] Map modules, public API, data formats and migrations.\n"
        "- [ ] Establish existing test and CI baseline; record pre-existing failures.\n"
        "- [ ] Identify current UX components, design constraints and user journeys.\n"
        "- [ ] Review data classification, trust boundaries, secrets and dependency risks.\n"
        "- [ ] Inspect affected code for implicit state machines and duplicate implementations.\n"
        "- [ ] Check existing agent instructions, skills, MCP and genuine model access.\n"
        "- [ ] Agree scope, adoption plan and regression constraints with the user.\n"
        "- [ ] After review, manually reference .agentic-kit/AGENTS.md in existing AGENTS.md or equivalent.\n"
        "\nDo not automatically refactor or replace existing configurations.\n").replace("\\`", "`")

def install_plan(source, target, mode, activate=False, profiles=()):
    return load_helper("aek_install.py").prepare(
        source, target, mode, source_files, adoption_note,
        activate=activate, profiles=profiles)[0]


def install(source, target, mode, apply=False, plan_id=None, activate=False, profiles=()):
    result = load_helper("aek_install.py").install(
        source, target, mode, source_files, adoption_note,
        apply=apply, plan_id=plan_id, activate=activate, profiles=profiles)
    dump(result)
    return 2 if result.get("conflicts") else 0


NEW_PROJECT_GITIGNORE = """.env
.env.*
*.token
__pycache__/
*.py[cod]
.venv/
node_modules/
.DS_Store
"""

NEW_PROJECT_DOCS = """# Документация проекта

| Раздел | Назначение |
|---|---|
| `PRODUCT_BRIEF.md` | согласованный бриф: проблема, пользователи, границы MVP, критерии успеха |
| `evidence/` | свидетельства проверок, привязанные к коммиту |
| `decisions/` | значимые решения и их основания; создаётся по необходимости |

Шаблоны: `.agentic-kit/templates/`. Порядок работы: `.agentic-kit/docs/DAILY_WORKFLOW.md`.
"""

NEW_PROJECT_REPORTS = """# Отчёты

Один отчёт на операцию: `reports/<operation-id>/REPORT.md`. Полные ответы инструментов
сохраняются рядом в `raw/` без редактирования. Общий индекс операций, если он нужен,
ведётся в `reports/INDEX.md`.
"""

NEW_PROJECT_PROMPT = (
    "Новый проект «{title}» создан и подключён к AEK. Прочитай AGENTS.md и "
    ".agentic-kit/AGENTS.md, подтверди доступность навыков AEK в палитре команд и начни "
    "discovery по .agentic-kit/docs/DAILY_WORKFLOW.md: проблема, пользователи, границы MVP, "
    "критерии успеха. Существенную реализацию начинай после согласованного брифа и задачи "
    "new-task. Не меняй установленный комплект без отдельного поручения."
)


def project_scaffold(title):
    """Files a new project needs besides the kit itself. Content only, no writes."""
    readme = (
        "# " + title + "\n\n"
        "Краткое описание проекта.\n\n"
        "## Начало работы\n\n"
        "- Инструкции для агентов: `AGENTS.md`, комплект AEK: `.agentic-kit/AGENTS.md`.\n"
        "- Порядок работы: `.agentic-kit/docs/DAILY_WORKFLOW.md`.\n"
        "- Продуктовый бриф: `docs/PRODUCT_BRIEF.md`, появляется на стадии discovery.\n\n"
        "## Проверки\n\n"
        "```bash\n"
        "python3 .agentic-kit/tools/aek.py doctor --target .\n"
        "python3 .agentic-kit/tools/aek.py environment check --target .\n"
        "```\n"
    )
    return {
        "README.md": readme.encode("utf-8"),
        ".gitignore": NEW_PROJECT_GITIGNORE.encode("utf-8"),
        "docs/README.md": NEW_PROJECT_DOCS.encode("utf-8"),
        "docs/evidence/.gitkeep": b"",
        "reports/README.md": NEW_PROJECT_REPORTS.encode("utf-8"),
    }


def new_project_handoff(target, title):
    """Arguments for the harness that opens the project and the next thread."""
    return {
        "harness": "t3-code",
        "register_project": {
            "tool": "t3_project_create",
            "arguments": {"title": title, "workspaceRoot": str(target)},
            "verify_with": "t3_project_list",
            "note": "Уже зарегистрированный каталог отклоняется; используйте выданный projectId.",
        },
        "open_thread": {
            "tool": "t3_thread_launch",
            "arguments": {
                "projectId": "<projectId из register_project>",
                "title": title,
                "message": NEW_PROJECT_PROMPT.format(title=title),
                "runtimeMode": "full-access",
            },
            "verify_with": "t3_thread_list",
            "note": ("create_threads не открывает поток в проекте, зарегистрированном с "
                     "префиксом mcp:; он молча создаёт поток в текущем проекте; "
                     "t3_thread_launch открывает поток в каталоге проекта."),
        },
        "fallback": (
            "Если инструменты T3 недоступны, передайте эти аргументы сессии, которая умеет "
            "регистрировать проекты. Запись в локальный реестр регистрацию не заменяет."
        ),
        "provider_visibility": "unverified",
    }


def new_project_plan(source, target, title, providers=(), profiles=()):
    """Reviewed plan for a complete new-project bootstrap. Writes nothing."""
    install_helper = load_helper("aek_install.py")
    source = install_helper.validate_source(source)
    target = Path(target).resolve()
    if not target.is_dir():
        raise ValueError("Target directory must exist before planning: " + str(target))
    if not isinstance(title, str) or not title.strip() or "\n" in title:
        raise ValueError("Title must be one non-empty line")
    title = title.strip()
    allowed = load_helper("aek_shortcuts.py").PROVIDERS
    providers = tuple(sorted(set(providers)))
    unknown = [name for name in providers if name not in allowed]
    if unknown:
        raise ValueError("Unknown shortcut provider: " + ", ".join(unknown))
    install_summary = install_helper.prepare(
        source, target, "new", source_files, adoption_note,
        activate=True, profiles=profiles)[0]
    scaffold = project_scaffold(title)
    add, unchanged, conflicts = [], [], []
    for name, data in sorted(scaffold.items()):
        path = target / name
        if path.is_symlink():
            conflicts.append(name)
        elif not path.exists():
            add.append(name)
        elif path.is_file() and path.read_bytes() == data:
            unchanged.append(name)
        else:
            conflicts.append(name)
    git = git_bootstrap_plan(target)
    material = {
        "target": str(target),
        "title": title,
        "providers": list(providers),
        "profiles": list(profiles),
        "install_plan_id": install_summary["plan_id"],
        "scaffold": {name: sha(data) for name, data in sorted(scaffold.items())},
        "scaffold_add": add,
        "scaffold_unchanged": unchanged,
        "git": {key: git.get(key) for key in
                ("action", "repository", "nested_in", "layout", "problem", "index_entries",
                 "state_digest")},
    }
    combined = sorted(set(install_summary["conflicts"]) | set(conflicts))
    if git["action"] == "refuse":
        combined = sorted(set(combined) | {"git: " + git["problem"]})
    elif git["index_entries"] and git["action"] != "keep_history":
        combined = sorted(set(combined) | {
            "git: index already has staged entries that the bootstrap does not own ("
            + ", ".join(git["index_entries"]) + ")"})
    return {
        "target": str(target),
        "title": title,
        "dry_run": True,
        "writes": False,
        "providers": list(providers),
        "profiles": list(profiles),
        "version": install_summary.get("version"),
        "plan_id": sha(json.dumps(material, sort_keys=True).encode())[:24],
        "steps": [
            "install kit and activate skills",
            "write project scaffold",
            "apply provider commands",
            "pin environment lock",
            "run doctor",
            "initialize git and create the first commit",
            "register the project in the harness and open a thread",
        ],
        "install": {
            "plan_id": install_summary["plan_id"],
            "add": len(install_summary["add"]),
            "update": len(install_summary["update"]),
            "conflicts": install_summary["conflicts"],
            "safe_to_apply": install_summary["safe_to_apply"],
        },
        "scaffold": {"add": add, "unchanged": unchanged, "conflicts": conflicts},
        "git": git,
        "conflicts": combined,
        "safe_to_apply": install_summary["safe_to_apply"] and not conflicts and not (
            git["action"] == "refuse" or git["index_entries"]),
        "handoff": new_project_handoff(target, title),
        "note": "Plan writes nothing. Apply requires this plan_id and the same arguments.",
    }


NEW_PROJECT_GIT_PATHS = (
    ".gitignore", ".gitattributes", "AGENTS.md", "CLAUDE.md", "GEMINI.md", "README.md",
    "docs", "reports", ".agentic-kit", ".agents", ".aek",
    ".opencode/commands", ".claude/commands", ".pi/prompts",
)


GIT_ENV_KEEP = ("GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL", "GIT_AUTHOR_DATE",
                "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL", "GIT_COMMITTER_DATE")
GIT_SAFE_CONFIG = (
    ("core.hooksPath", os.devnull + "/aek-no-hooks"),
    ("core.fsmonitor", "false"),
    ("commit.gpgsign", "false"),
    ("core.attributesFile", os.devnull),
)


def _git_env():
    """Run Git without inherited redirection or injected configuration."""
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("GIT_") or key in GIT_ENV_KEEP}
    env["GIT_CONFIG_NOSYSTEM"] = "1"
    env["GIT_CONFIG_GLOBAL"] = os.devnull
    env["GIT_ATTR_NOSYSTEM"] = "1"
    return env


def _git(target, *args, env=None, config=()):
    pairs = list(GIT_SAFE_CONFIG) + list(config.items() if isinstance(config, dict) else config)
    overrides = [item for key, value in pairs for item in ("-c", key + "=" + value)]
    return subprocess.run(["git", "-C", str(target), *overrides, *args],
                          capture_output=True, text=True, timeout=120, env=env)


def git_identity(target):
    """Author identity and branch name read without executing Git extensions."""
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("GIT_") or key in GIT_ENV_KEEP}
    env["GIT_CEILING_DIRECTORIES"] = str(Path(target).resolve().parent)
    found = {}
    for key in ("user.name", "user.email", "init.defaultBranch"):
        probe = subprocess.run(["git", "-C", str(target), "config", "--get", key],
                               capture_output=True, text=True, timeout=60, env=env)
        value = probe.stdout.strip() if probe.returncode == 0 else ""
        if value and "\n" not in value:
            found[key] = value
    return found


def git_storage_problem(target, deep=True):
    """Anything unusual inside an existing .git that the bootstrap will not trust.

    A plain `git init` produces no symlinks, no gitfile, no worktree or common
    configuration and no external object store. Anything else is refused before
    the first write instead of being reasoned about path by path. The cheap
    shallow pass runs before history is inspected, because a damaged .git must
    never be mistaken for an absent one and written through.
    """
    target = Path(target).resolve()
    dot = target / ".git"
    if not dot.is_dir() or dot.is_symlink():
        return None
    for name in ("commondir", "config.worktree", "modules", "worktrees",
                 "objects/info/alternates", "info/grafts", "info/alternates"):
        path = dot / name
        if path.is_symlink() or path.exists():
            return (".git/" + name + " is present; bootstrap accepts only a plain "
                    "repository created by git init")
    promisor = dot / "objects" / "pack"
    if promisor.is_dir() and not promisor.is_symlink():
        try:
            markers = sorted(promisor.glob("*.promisor"))
        except OSError as exc:
            return ".git/objects/pack cannot be listed: " + str(exc)
        if markers:
            return (".git/objects/pack contains " + markers[0].name
                    + "; bootstrap requires locally complete history, not a partial clone")
    truncated = dot / "shallow"
    if truncated.is_symlink() or truncated.exists():
        return (".git/shallow is present; bootstrap requires locally complete history, "
                "not a shallow clone")
    if not deep:
        try:
            entries = sorted(dot.iterdir())
        except OSError as exc:
            return ".git cannot be listed: " + str(exc)
        for path in entries:
            problem = _entry_problem(target, path, shallow=True)
            if problem:
                return problem
        return _refs_problem(target)
    entries = 0
    refs_root = dot / "refs"
    for dirpath, dirnames, filenames in os.walk(dot, followlinks=False):
        for entry in list(dirnames) + filenames:
            entries += 1
            if entries > 50000:
                return ".git has more than 50000 entries; bootstrap cannot verify it"
            path = Path(dirpath) / entry
            problem = _entry_problem(target, path, shallow=False)
            if problem:
                return problem
            problem = _lock_problem(target, path, refs_root)
            if problem:
                return problem
            problem = _hardlink_problem(target, path)
            if problem:
                return problem
        dirnames[:] = [item for item in dirnames
                       if not (Path(dirpath) / item).is_symlink()]
    return None


def _entry_problem(target, path, shallow):
    """Symlink or non-regular entry Git must never be asked to read."""
    label = str(path.relative_to(target)) if path.exists() or path.is_symlink() else str(path)
    if path.is_symlink():
        return (label + " is a symlink; bootstrap accepts only a plain repository "
                "created by git init")
    if shallow and path.is_dir():
        return None
    if not path.is_dir() and not path.is_file():
        return (label + " is not a regular file or directory; bootstrap accepts only a "
                "plain repository created by git init")
    return None


def _hardlink_problem(target, path):
    """A regular Git file shared with another inode would be written through.

    Reflogs are opened for append and the index/refs are replaced in place, so a
    hard link outside the target would let the bootstrap commit change a file the
    target does not own. Object and pack files are content-addressed and only ever
    created, and local clones share them by hard link, so they are allowed.
    """
    if path.is_symlink() or not path.is_file():
        return None
    dot = target / ".git"
    for shared in (dot / "objects", dot / "hooks"):
        if path == shared or shared in path.parents:
            return None
    try:
        links = path.stat().st_nlink
    except OSError as exc:
        return str(path.relative_to(target)) + " cannot be inspected: " + str(exc)
    if links > 1:
        return (str(path.relative_to(target)) + " has " + str(links)
                + " hard links; bootstrap writes only files the target owns")
    return None


def _lock_problem(target, path, refs_root):
    """Lock file inside refs; Git refuses to update refs while it exists."""
    if not path.name.endswith(".lock"):
        return None
    if path != refs_root and refs_root not in path.parents:
        return None
    return (str(path.relative_to(target)) + " is a Git lock file; finish or abort the Git "
            "operation before creating a project")


def _refs_problem(target):
    """Refs are read by git show-ref/commit, so a special or linked entry is refused early."""
    dot = target / ".git"
    refs = dot / "refs"
    if refs.is_symlink():
        return ".git/refs is a symlink; bootstrap accepts only a plain repository"
    if not refs.exists():
        return None
    if not refs.is_dir():
        return ".git/refs is not a directory; bootstrap accepts only a plain repository"
    for dirpath, dirnames, filenames in os.walk(refs, followlinks=False):
        for entry in list(dirnames) + filenames:
            path = Path(dirpath) / entry
            problem = _lock_problem(target, path, refs)
            if problem:
                return problem
            problem = _entry_problem(target, path, shallow=False)
            if problem:
                return problem
        dirnames[:] = [item for item in dirnames
                       if not (Path(dirpath) / item).is_symlink()]
    return None


def git_config_problem(target):
    """Repository setting that would execute code during the bootstrap commit."""
    listing = _git(target, "config", "--local", "--list", "--includes", "-z", env=_git_env())
    if listing.returncode != 0:
        return ("target .git config cannot be read including includes: "
                + (listing.stderr or "unknown error").strip())
    for entry in listing.stdout.split("\0"):
        key = entry.split("\n", 1)[0].strip()
        lower = key.lower()
        if lower.startswith("filter.") and lower.endswith((".clean", ".smudge", ".process")):
            return ("target .git config defines " + key
                    + "; bootstrap does not run repository filters")
        if lower == "extensions.partialclone":
            return ("target .git config defines " + key
                    + "; bootstrap requires locally complete history, not a partial clone")
        if lower == "core.alternaterefscommand":
            return ("target .git config defines " + key
                    + "; bootstrap does not run alternate-refs commands and requires "
                    "locally complete history")
        if lower.startswith("remote.") and (lower.endswith(".promisor")
                                            or lower.endswith(".partialclonefilter")):
            return ("target .git config defines " + key
                    + "; bootstrap requires locally complete history, not a partial clone")
    return None


def _config_includes(path):
    """include.path values of one config file, read without following includes."""
    probe = subprocess.run(["git", "config", "--file", str(path), "--list", "-z"],
                           capture_output=True, text=True, timeout=60, env=_git_env())
    if probe.returncode != 0:
        return None, ("target .git config cannot be read: "
                      + (probe.stderr or "unknown error").strip())
    values = []
    for entry in probe.stdout.split("\0"):
        if not entry:
            continue
        key, _, value = entry.partition("\n")
        key = key.strip().lower()
        if key == "include.path" or (key.startswith("includeif.") and key.endswith(".path")):
            values.append(value)
    return values, None


def git_include_problem(target, limit=200):
    """Includes Git follows from .git/config; they must be plain files inside the target.

    `git config --includes` opens every included file, so a FIFO, symlink or a file
    outside the target could hang or redirect the bootstrap. Checked before the first
    Git call that reads configuration.
    """
    target = Path(target).resolve()
    dot = target / ".git"
    pending = [dot / "config"]
    seen = set()
    visited = 0
    while pending:
        config = pending.pop(0)
        label = str(config)
        if label in seen:
            continue
        seen.add(label)
        visited += 1
        if visited > limit:
            return ("target .git config includes more than " + str(limit)
                    + " files; bootstrap cannot verify it")
        if config.is_symlink():
            return (label + " is a symlink; bootstrap reads configuration only from plain "
                    "files inside the target")
        if not config.exists():
            continue
        if not config.is_file():
            return (label + " is not a regular file; bootstrap reads configuration only from "
                    "plain files inside the target")
        values, problem = _config_includes(config)
        if problem:
            return problem
        for value in values:
            if not value:
                continue
            candidate = Path(value)
            if value.startswith("~"):
                candidate = Path(os.path.expanduser(value))
            elif not candidate.is_absolute():
                candidate = config.parent / candidate
            if candidate.is_symlink():
                return (str(candidate) + " is a symlink; bootstrap reads configuration only "
                        "from plain files inside the target")
            try:
                resolved = candidate.resolve()
            except OSError:
                resolved = candidate
            if not resolved.exists():
                continue
            if target not in resolved.parents:
                return ("target .git config includes " + str(resolved) + " outside the target; "
                        "bootstrap does not read configuration from there")
            if not resolved.is_file():
                return ("target .git config includes " + str(resolved) + " which is not a "
                        "regular file; bootstrap reads configuration only from plain files")
            pending.append(resolved)
    return None


def git_repository_top(target):
    """Top level of the repository containing target; None when it is outside one."""
    probe = _git(target, "rev-parse", "--show-toplevel", env=_git_env())
    if probe.returncode != 0:
        return None
    value = probe.stdout.strip()
    return Path(value).resolve() if value else None


def git_ancestor_repository(target):
    """Nearest ancestor holding .git; decided on the filesystem, without Git reads.

    Asking Git whether a target is nested makes Git read the parent repository's
    configuration and includes. A FIFO or an unreadable include there would hang
    the plan before any refusal, so nesting is detected by walking up instead.
    """
    current = Path(target).resolve().parent
    while True:
        dot = current / ".git"
        if dot.exists() or dot.is_symlink():
            return current
        if current.parent == current:
            return None
        current = current.parent


def git_integrity_problem(target):
    """Reachable objects must be present: a missing tree must not look like history."""
    try:
        check = _git(target, "fsck", "--full", "--no-reflogs", "--no-dangling",
                     "--no-progress", env=_git_env())
    except subprocess.TimeoutExpired:
        return ("target repository integrity check timed out; bootstrap does not write to a "
                "repository it cannot verify")
    if check.returncode != 0:
        detail = [line for line in (check.stderr or check.stdout or "").splitlines() if line.strip()]
        return ("target repository is damaged (git fsck): "
                + ("; ".join(detail[:3]) if detail else "unknown error"))
    return None


def git_layout(target):
    """Describe target/.git without following it: absent, directory, file or symlink."""
    dot = Path(target) / ".git"
    if dot.is_symlink():
        return {"kind": "symlink", "path": os.readlink(dot)}
    if dot.is_dir():
        return {"kind": "directory"}
    if dot.is_file():
        return {"kind": "file"}
    return {"kind": "absent"} if not dot.exists() else {"kind": "other"}


GIT_OPERATION_MARKERS = (
    "MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "REBASE_HEAD", "BISECT_LOG",
    "BISECT_START", "sequencer", "rebase-merge", "rebase-apply",
    "index.lock", "HEAD.lock", "config.lock", "packed-refs.lock", "shallow.lock",
)


def git_operation_problem(target):
    """Unfinished Git operation or lock that would make the commit fail halfway."""
    dot = Path(target).resolve() / ".git"
    for name in GIT_OPERATION_MARKERS:
        path = dot / name
        if path.exists() or path.is_symlink():
            return (".git/" + name + " is present; finish or abort the Git operation "
                    "before creating a project")
    return None


def git_state_digest(target):
    """Digest of HEAD, refs and operation markers, so a state change invalidates plan_id."""
    dot = Path(target).resolve() / ".git"
    if not dot.is_dir() or dot.is_symlink():
        return None
    records = []
    head = dot / "HEAD"
    if head.is_symlink():
        records.append("HEAD:symlink")
    elif head.is_file():
        try:
            records.append("HEAD:" + head.read_text(encoding="utf-8", errors="replace").strip())
        except OSError:
            records.append("HEAD:unreadable")
    else:
        records.append("HEAD:absent")
    for name in GIT_OPERATION_MARKERS + ("packed-refs", "shallow"):
        path = dot / name
        if path.is_symlink():
            mark = "symlink"
        elif path.is_dir():
            mark = "dir"
        elif path.is_file():
            try:
                mark = sha(path.read_bytes())
            except OSError:
                mark = "unreadable"
        else:
            mark = "absent"
        records.append(name + ":" + mark)
    refs = dot / "refs"
    if refs.is_symlink():
        records.append("refs:symlink")
    elif refs.is_dir():
        for dirpath, dirnames, filenames in os.walk(refs, followlinks=False):
            dirnames.sort()
            for entry in sorted(filenames):
                path = Path(dirpath) / entry
                label = str(path.relative_to(dot))
                if path.is_symlink():
                    records.append(label + ":symlink")
                elif not path.is_file():
                    records.append(label + ":special")
                else:
                    try:
                        records.append(label + ":" + sha(path.read_bytes()))
                    except OSError:
                        records.append(label + ":unreadable")
            for entry in sorted(dirnames):
                path = Path(dirpath) / entry
                if path.is_symlink():
                    records.append(str(path.relative_to(dot)) + ":symlink")
            dirnames[:] = [item for item in dirnames
                           if not (Path(dirpath) / item).is_symlink()]
    return sha("\n".join(records).encode())


def git_required_layout_problem(target):
    """A repository Git creates always has HEAD, objects and refs.

    A bare or half-made `.git` directory has no configuration to read, so the
    refusal must be decided without asking Git anything.
    """
    dot = Path(target).resolve() / ".git"
    for name in ("HEAD", "objects", "refs"):
        path = dot / name
        if path.is_symlink() or not path.exists():
            return ("target .git is not a usable repository: .git/" + name
                    + " is missing or linked; bootstrap never runs git init over an "
                    "existing .git")
    return None


def git_bootstrap_plan(target):
    """Read-only: how the bootstrap commit relates to existing Git history."""
    target = Path(target).resolve()
    plan = {"target": str(target), "action": "init", "repository": None, "nested_in": None,
            "layout": None, "problem": None, "index_entries": [], "state_digest": None}
    layout = git_layout(target)
    plan["layout"] = layout["kind"]
    if layout["kind"] == "symlink":
        plan["action"] = "refuse"
        plan["problem"] = ("target .git is a symlink to " + str(layout.get("path"))
                           + "; bootstrap does not write Git data through it")
        return plan
    if layout["kind"] == "file":
        plan["action"] = "refuse"
        plan["problem"] = ("target .git is a gitfile; new-project creates its own repository "
                           "and does not use worktree or submodule Git directories")
        return plan
    if layout["kind"] == "other":
        plan["action"] = "refuse"
        plan["problem"] = "target .git is not a directory"
        return plan
    if layout["kind"] == "directory":
        problem = git_storage_problem(target)
        if problem:
            plan["action"] = "refuse"
            plan["problem"] = problem
            return plan
        problem = git_required_layout_problem(target)
        if problem:
            plan["action"] = "refuse"
            plan["problem"] = problem
            return plan
        plan["state_digest"] = git_state_digest(target)
        problem = git_include_problem(target)
        if problem:
            plan["action"] = "refuse"
            plan["problem"] = problem
            return plan
        # Configuration is refused before the first Git call that resolves refs or
        # objects: a promisor remote would otherwise fetch missing objects while
        # the plan is only being computed.
        problem = git_config_problem(target)
        if problem:
            plan["action"] = "refuse"
            plan["problem"] = problem
            return plan
        worktree = _git(target, "config", "--local", "--includes", "--get", "core.worktree",
                        env=_git_env())
        value = worktree.stdout.strip() if worktree.returncode == 0 else ""
        if value:
            path = Path(value)
            path = path if path.is_absolute() else target / ".git" / path
            try:
                path = path.resolve()
            except OSError:
                pass
            if path != target and target not in path.parents:
                plan["action"] = "refuse"
                plan["problem"] = ("target .git config core.worktree points outside the target: "
                                   + str(path) + "; bootstrap does not write Git data there")
                return plan
    if layout["kind"] == "absent":
        ancestor = git_ancestor_repository(target)
        if ancestor is None:
            return plan
        plan["action"] = "init_nested"
        plan["nested_in"] = str(ancestor)
        return plan
    top = git_repository_top(target)
    if top is None:
        plan["action"] = "refuse"
        plan["problem"] = ("target .git exists but is not a usable repository: "
                           "git rev-parse --show-toplevel failed; bootstrap never runs "
                           "git init over an existing .git")
        return plan
    if top != target:
        plan["action"] = "refuse"
        plan["problem"] = ("target .git exists but Git reports another repository top ("
                           + str(top) + "); bootstrap does not mix it with this directory")
        return plan
    plan["repository"] = str(top)
    problem = git_operation_problem(target)
    if problem:
        plan["action"] = "refuse"
        plan["problem"] = problem
        return plan
    refs = _git(target, "show-ref", "--head", env=_git_env())
    if refs.returncode not in (0, 1):
        plan["action"] = "refuse"
        plan["problem"] = ("target repository refs cannot be read: "
                           + (refs.stderr or "unknown error").strip())
        return plan
    head = _git(target, "rev-parse", "--verify", "--quiet", "HEAD", env=_git_env())
    if head.returncode == 0:
        commit = _git(target, "rev-parse", "--verify", "--quiet", "HEAD^{commit}",
                      env=_git_env())
        if commit.returncode != 0:
            plan["action"] = "refuse"
            plan["problem"] = ("target repository HEAD resolves to a missing or non-commit "
                               "object; the history is damaged and bootstrap does not "
                               "rewrite it")
            return plan
        problem = (git_index_problem(target) or git_storage_problem(target)
                   or git_integrity_problem(target))
        if problem:
            plan["action"] = "refuse"
            plan["problem"] = problem
            return plan
        plan["action"] = "keep_history"
        return plan
    branch = _git(target, "symbolic-ref", "--quiet", "HEAD", env=_git_env())
    if branch.returncode == 1:
        plan["action"] = "refuse"
        plan["problem"] = ("target repository has a detached HEAD that does not resolve to a "
                           "commit object; the history is damaged and bootstrap does not "
                           "rewrite it")
        return plan
    if branch.returncode != 0:
        plan["action"] = "refuse"
        plan["problem"] = ("target repository HEAD cannot be inspected: "
                           + (branch.stderr or "unknown error").strip())
        return plan
    name = branch.stdout.strip()
    unborn = _git(target, "show-ref", "--verify", "--quiet", name, env=_git_env())
    if unborn.returncode == 0:
        plan["action"] = "refuse"
        plan["problem"] = ("target repository branch " + name + " exists but does not resolve "
                           "to a commit object; the history is damaged and bootstrap does not "
                           "rewrite it")
        return plan
    if unborn.returncode != 1:
        plan["action"] = "refuse"
        plan["problem"] = ("target repository refs cannot be read: "
                           + (unborn.stderr or "unknown error").strip())
        return plan
    problem = (git_index_problem(target) or git_storage_problem(target)
               or git_integrity_problem(target))
    if problem:
        plan["action"] = "refuse"
        plan["problem"] = problem
        return plan
    index = _git(target, "ls-files", "--cached", env=_git_env())
    if index.returncode != 0:
        plan["action"] = "refuse"
        plan["problem"] = ("target repository index cannot be read: "
                           + (index.stderr or "unknown error").strip())
        return plan
    plan["index_entries"] = index.stdout.splitlines()[:20]
    plan["action"] = "commit"
    return plan


def git_index_problem(target):
    """Index that Git cannot read; keep_history must not hide a damaged repository."""
    index = _git(target, "ls-files", "--cached", env=_git_env())
    if index.returncode != 0:
        return ("target repository index cannot be read: "
                + (index.stderr or "unknown error").strip())
    return None


def bootstrap_paths(target):
    """Paths the bootstrap owns; nothing else in the target is staged."""
    target = Path(target).resolve()
    names = list(NEW_PROJECT_GIT_PATHS)
    try:
        entries = sorted(target.iterdir())
    except OSError:
        entries = []
    for item in entries:
        lower = item.name.lower()
        if item.is_file() and (lower.startswith("readme") or lower.startswith("license")):
            names.append(item.name)
    return [name for name in dict.fromkeys(names)
            if (target / name).exists() or (target / name).is_symlink()]


def installed_source(target):
    """Source identity recorded by install; read-only and safe if the file is unreadable."""
    try:
        manifest = json.loads((Path(target) / ".agentic-kit" / "install-manifest.json")
                              .read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    identity = manifest.get("source_identity") if isinstance(manifest, dict) else None
    if not isinstance(identity, dict):
        return None
    return {
        "git_commit": identity.get("git_commit"),
        "version": manifest.get("kit_version"),
        "content_sha256": identity.get("content_sha256"),
        "file_count": identity.get("file_count"),
        "source_dirty": identity.get("source_dirty"),
    }


def ensure_git_repository(target):
    """Commit the bootstrap inside the target's own repository only.

    A target nested in another repository gets its own repository: the parent
    history, index and untouched files are never staged or committed.
    """
    target = Path(target).resolve()
    env = _git_env()
    result = {"initialized": False, "commit": None, "head": None, "error": None,
              "note": None, "repository": str(target), "nested_in": None,
              "layout": None, "action": None, "staged": []}
    try:
        identity = git_identity(target)
        plan = git_bootstrap_plan(target)
        result["nested_in"] = plan["nested_in"]
        result["layout"] = plan["layout"]
        result["action"] = plan["action"]
        if plan["action"] == "refuse":
            result["error"] = plan["problem"]
            return result
        if plan["index_entries"]:
            result["error"] = ("index already has staged entries that the bootstrap does not own: "
                               + ", ".join(plan["index_entries"]))
            return result
        if plan["action"] in ("init", "init_nested"):
            init = _git(target, "init", "-q", env=env, config=identity)
            if init.returncode != 0:
                result["error"] = (init.stderr or "git init failed").strip()
                return result
            result["initialized"] = True
            boundary = _git(target, "rev-parse", "--show-toplevel", env=env)
            top = Path(boundary.stdout.strip()).resolve() if boundary.returncode == 0 else None
            if top != target:
                result["error"] = ("repository boundary is not the target: "
                                   + (str(top) if top else (boundary.stderr or "unknown").strip()))
                return result
            problem = git_storage_problem(target) or git_config_problem(target)
            if problem:
                result["error"] = problem
                return result
        if plan["action"] == "keep_history":
            result["head"] = _git(target, "rev-parse", "HEAD", env=env).stdout.strip()
            result["note"] = "existing repository history; bootstrap files left uncommitted"
            return result
        paths = bootstrap_paths(target)
        if not paths:
            result["error"] = "nothing to commit; bootstrap files are missing"
            return result
        index = _git(target, "ls-files", "--cached", env=env)
        if index.returncode == 0 and index.stdout.strip():
            result["error"] = ("index already has staged entries that the bootstrap does not own: "
                               + ", ".join(index.stdout.splitlines()[:20]))
            return result
        staged = _git(target, "add", "--", *paths, env=env, config=identity)
        if staged.returncode != 0:
            result["error"] = (staged.stderr or "git add failed").strip()
            return result
        result["staged"] = paths
        commit = _git(target, "commit", "-q", "-m", "chore: каркас проекта и подключение AEK",
                      "--", *paths, env=env, config=identity)
        if commit.returncode != 0:
            result["error"] = (commit.stderr or commit.stdout or "git commit failed").strip()
            return result
        result["commit"] = _git(target, "rev-parse", "--short=12", "HEAD", env=env).stdout.strip()
        result["head"] = _git(target, "rev-parse", "HEAD", env=env).stdout.strip()
    except (OSError, subprocess.SubprocessError) as exc:
        result["error"] = str(exc)
    return result


def new_project(source, target, title, providers=(), profiles=(), apply=False, plan_id=None):
    """Create a project: scaffold, kit, skills, commands, lock, checks, git, handoff."""
    plan = new_project_plan(source, target, title, providers, profiles)
    if not apply:
        return plan
    if plan["conflicts"]:
        raise ValueError("Conflicting files or unsafe Git state; no files written: "
                         + ", ".join(plan["conflicts"]))
    if not plan_id or plan_id != plan["plan_id"]:
        raise ValueError("Missing or stale plan_id; review a fresh plan")
    target = Path(plan["target"])
    install_helper = load_helper("aek_install.py")
    source = install_helper.validate_source(source)
    installed = install_helper.install(
        source, target, "new", source_files, adoption_note,
        apply=True, plan_id=plan["install"]["plan_id"], activate=True, profiles=profiles)
    scaffold = project_scaffold(plan["title"])
    written = []
    for name in plan["scaffold"]["add"]:
        path = target / name
        path.parent.mkdir(parents=True, exist_ok=True)
        install_helper.atomic_write(path, scaffold[name], exclusive=True)
        written.append(name)
    shortcuts = {}
    for provider in plan["providers"]:
        engine = load_helper("aek_shortcuts.py")
        summary = engine.plan("project", target, [provider])[0]
        engine.apply_plan("project", target, [provider], summary["plan_id"])
        check = engine.plan("project", target, [provider])[0]
        shortcuts[provider] = {
            "plan_id": summary["plan_id"],
            "ready": not check["conflicts"] and not check["operations"],
            "conflicts": check["conflicts"],
        }
    environment = load_helper("aek_environment.py")
    env_plan = environment.plan(target)
    environment.apply(target, env_plan["plan_id"])
    env_check = environment.check(target)
    doctor = load_helper("aek_components.py").doctor(target)
    git = ensure_git_repository(target)
    source_info = installed_source(target)
    checks = {
        "install": installed.get("verified") is True,
        "shortcuts": all(item["ready"] for item in shortcuts.values()),
        "environment": env_check.get("passed") is True and env_check.get("matches") is True,
        "doctor": doctor.get("passed") is True,
        "git": git.get("error") is None,
    }
    warnings = []
    if source_info and source_info.get("source_dirty"):
        warnings.append("source tree is dirty; this install is not a verified source")
    if not plan["providers"]:
        warnings.append("provider commands were not requested; skills are activated only")
    if git.get("error"):
        warnings.append("git: " + git["error"])
    if git.get("note"):
        warnings.append("git: " + git["note"])
    if git.get("nested_in"):
        warnings.append("git: target is inside another repository (" + git["nested_in"]
                        + "); a separate repository was created in the target and the parent is untouched")
    if not checks["shortcuts"]:
        warnings.append("provider commands are not ready")
    result = {
        "target": str(target),
        "title": plan["title"],
        "dry_run": False,
        "writes": True,
        "applied": True,
        "plan_id": plan["plan_id"],
        "version": plan.get("version"),
        "source": source_info,        "install": {"plan_id": plan["install"]["plan_id"], "verified": installed.get("verified") is True},
        "scaffold": {"written": written, "unchanged": plan["scaffold"]["unchanged"]},
        "shortcuts": shortcuts,
        "environment": {
            "plan_id": env_plan["plan_id"],
            "passed": env_check.get("passed") is True,
            "matches": env_check.get("matches") is True,
            "provider_visibility": env_check.get("provider_visibility"),
        },
        "doctor": {
            "passed": doctor.get("passed") is True,
            "next_action": doctor.get("next_action"),
        },
        "git": git,
        "checks": checks,
        "passed": all(checks.values()),
        "warnings": warnings,
        "handoff": plan["handoff"],
        "next_steps": [
            "Выполнить handoff: зарегистрировать проект и открыть поток с full-access.",
            "В новом потоке подтвердить доступность навыков AEK и начать discovery.",
            "Проверить видимость провайдера отдельно: файлы не доказывают загрузку навыков.",
        ],
    }
    return result


def load_helper(filename):
    """Load a sibling CLI module without requiring package installation."""
    import importlib.util
    path = Path(__file__).resolve().with_name(filename)
    spec = importlib.util.spec_from_file_location("aek_helper_" + path.stem, path)
    if spec is None or spec.loader is None:
        raise ValueError("Cannot load AEK helper " + filename)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def upgrade(source, target, apply=False, plan_id=None, show_diff=False):
    source = load_helper("aek_install.py").validate_source(source)
    engine = load_helper("aek_upgrade.py")
    activation = load_helper("aek_activate.py")
    if apply:
        return engine.perform_upgrade(source, target, source_files,
                                      activation.portable_skill, plan_id, show_diff)
    result, _ = engine.prepare(source, target, source_files,
                               activation.portable_skill, show_diff)
    return result


def rollback(target, backup_id, apply=False, plan_id=None):
    engine = load_helper("aek_upgrade.py")
    if apply:
        return engine.perform_rollback(target, backup_id, plan_id)
    return engine.prepare_rollback(target, backup_id)

def task_file(root, task_id):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", task_id):
        raise ValueError("Invalid task ID")
    path = root / ".agentic-kit" / "tasks" / (task_id + ".json")
    for part in (root / ".agentic-kit", path.parent, path):
        if part.is_symlink():
            raise ValueError("Unsafe task path")
    return path

def load_task(root, task_id):
    load_helper("aek_state.py").ensure_readable(root)
    file = task_file(root, task_id)
    if not file.is_file():
        raise ValueError("Task not found")
    task = json.loads(file.read_text(encoding="utf-8"))
    load_helper("aek_workflow.py").validate(task, PHASES)
    if task.get("id") != task_id:
        raise ValueError("Task ID does not match filename")
    load_helper("aek_state.py").ensure_readable(root)
    return file, task

def save_task(path, task, kind="task.updated", actor="agent", payload=None):
    load_helper("aek_workflow.py").validate(task, PHASES)
    return load_helper("aek_state.py").save_task(path, task, kind, actor, payload)


def process_view(root, task, allow_waiver=False):
    """Temporary evidence view; never persist synthetic approval evidence."""
    import copy
    effective = copy.deepcopy(task)
    if allow_waiver:
        for key in ("visual_qa", "browser_capability"):
            waiver = task.get("waivers", {}).get(key)
            if (not evidence_for_key(root, key, task.get("evidence", {}).get(key))
                    and _evidence_helper().waiver_valid(root, waiver, key)):
                effective.setdefault("evidence", {})[key] = {"source": "waiver", "waiver": copy.deepcopy(waiver)}
    review = review_status(root, task)
    if review["passed"]:
        effective.setdefault("evidence", {})["independent_review"] = {"source": "review_gate", "review": review}
    def check(key, item):
        if key == "independent_review":
            return review["passed"] and item == effective.get("evidence", {}).get(key)
        if isinstance(item, dict) and item.get("source") == "waiver":
            return (allow_waiver and key in ("visual_qa", "browser_capability")
                    and item.get("waiver") == task.get("waivers", {}).get(key)
                    and _evidence_helper().waiver_valid(root, item.get("waiver"), key))
        return evidence_for_key(root, key, item)
    return effective, check, lambda destination: required(task, destination)


def process_status(root, task, allow_waiver=False):
    engine = load_helper("aek_process.py")
    effective, check, gates = process_view(root, task, allow_waiver)
    current = engine.load_definition(Path(__file__).resolve().parents[1], task["process"]["id"])
    return engine.inspect(effective, check, gates, current_definition=current)


def mutation(root, kind, apply, plan_id, operation):
    """Join CLI operations in one history; operation journals own crash recovery."""
    if not apply:
        return operation()
    if not root.is_dir() or not (root / ".agentic-kit/install-manifest.json").is_file():
        raise ValueError("Operation requires an existing project with installed AEK")
    if not plan_id:
        raise ValueError("Apply requires a reviewed plan_id")
    from uuid import uuid4
    state = load_helper("aek_state.py")
    operation_id = uuid4().hex
    payload = {"operation_id": operation_id, "plan_id": plan_id}
    state.emit(root, kind + ".started", "environment", payload)
    try:
        result = operation()
    except Exception as exc:
        state.emit(root, kind + ".failed", "environment", {**payload, "error_type": type(exc).__name__})
        raise
    outcome = "failed" if (result.get("conflicts") or result.get("passed") is False
                            or result.get("state") in ("failed", "partial", "recovery_failed")) else "completed"
    state.emit(root, kind + "." + outcome, "environment", {
        **payload, "state": result.get("state"), "backup_id": result.get("backup_id"),
        "recovery_id": result.get("recovery_id")})
    return result


def review_status(root, task):
    try:
        head = _evidence_helper().git_snapshot(root)["git_commit"]
    except ValueError:
        head = None  # Read-only status must work before Git initialization.
    return load_helper("aek_reviews.py").inspect(root, task, head)


def task_status(root, task, allow_waiver=False):
    result = load_helper("aek_workflow.py").resume(root, task, PHASES,
        lambda project, current, destination: missing(project, current, destination, allow_waiver=allow_waiver))
    result["independent_reviews"] = review_status(root, task)
    if result["execution_boundary"]["passed"] and not result["independent_reviews"]["passed"] and task.get("stage") != "done" and task.get("status") != "cancelled":
        result["next_action"] = result["independent_reviews"]["next_action"]
    runs = load_helper("aek_state.py").attempts(root, task["id"])
    result["latest_attempt"] = runs[-1] if runs else None
    if task.get("process"):
        result["process"] = process_status(root, task, allow_waiver)
        result["waiver_mode"] = allow_waiver
        if result["process"]["next_action"] == "execution_boundary_reached" and result["independent_reviews"]["passed"]:
            result["next_action"] = "execution_boundary_reached"
    elif (task.get("stage") != "done" and result["execution_boundary"].get("reached") and result["independent_reviews"]["passed"]
          and not result["missing"] and task.get("status", "active") == "active" and not result["blockers"]):
        result["next_action"] = "execution_boundary_reached"
    return result


def required(task, destination):
    """Checks applied to a registered task. Task creation is enforced by the CI gate."""
    profile = task["profile"]
    ui = bool(task.get("ui"))
    if profile == "lite":
        checks = {
            "design": ["scope_approved"] + (["browser_capability"] if ui else []),
            "planning": ["design_approved"] if ui else [],
            "implementation": [],
            "verification": ["implementation_proof"],
            "release": ["tests"] + (["visual_qa"] if ui else []),
            "done": ["final_report"],
        }
    else:
        checks = {
            "design": ["environment_reviewed", "product_brief_approved"] + (["browser_capability"] if ui else []),
            "planning": ["design_approved"] if ui else [],
            "implementation": ["architecture_simplified", "security_screened", "routes_confirmed", "swarm_planned"],
            "verification": ["implementation_proof"],
            "release": ["tests", "security_review"] + (["visual_qa"] if ui else []),
            "done": ["release_approved", "final_report"],
        }
        if profile == "critical":
            checks["release"] += ["recovery_plan"]
        checks["release"] += ["independent_review"]
        checks["done"] += ["independent_review"]
    if task.get("runtime") == "http":
        checks["release"].append("runtime_smoke")
    return checks.get(destination, [])

def evidence_ok(root, item):
    if not isinstance(item, dict) or "path" not in item or "sha256" not in item:
        return False
    p = (root / item["path"]).resolve()
    try:
        p.relative_to(root.resolve())
    except ValueError:
        return False
    return p.is_file() and p.stat().st_size > 0 and sha(p.read_bytes()) == item["sha256"]

def _evidence_helper():
    return load_helper("aek_evidence.py")


def evidence_for_key(root, key, item):
    if key in ("tests", "runtime_smoke", "visual_qa"):
        return _evidence_helper().evidence_valid(root, key, item)
    if key == "browser_capability":
        return (isinstance(item, dict) and item.get("source") == "report"
                and item.get("browser_status") == "available" and evidence_ok(root, item))
    return evidence_ok(root, item)


def missing(root, task, destination, allow_waiver=False):
    gaps = []
    for key in required(task, destination):
        if key == "independent_review":
            if not review_status(root, task)["passed"]:
                gaps.append(key)
            continue
        if evidence_for_key(root, key, task.get("evidence", {}).get(key)):
            continue
        if (allow_waiver and key in ("visual_qa", "browser_capability")
                and _evidence_helper().waiver_valid(root, task.get("waivers", {}).get(key), key)):
            continue
        gaps.append(key)
    return gaps


def add_evidence(root, task, key, filename):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,63}", key):
        raise ValueError("Invalid evidence key")
    if key == "independent_review":
        raise ValueError("Use review assign/import-launch/import-result; Markdown does not prove execution")
    if key in ("tests", "runtime_smoke"):
        raise ValueError("Executable checks require record --run; document files cannot prove test execution")
    p = filename.resolve()
    try:
        relative = p.relative_to(root.resolve())
    except ValueError:
        raise ValueError("Evidence must be inside target project")
    if not p.is_file() or p.stat().st_size == 0:
        raise ValueError("Evidence file must exist and be nonempty")
    item = {"path": str(relative), "sha256": sha(p.read_bytes())}
    if key in ("visual_qa", "browser_capability"):
        extras = _evidence_helper().validate_report(root, key, p)
        item.update(extras)
        item["source"] = "report"
        if key == "browser_capability":
            item["browser_status"] = "available"
    task.setdefault("evidence", {})[key] = item


def add_waiver(root, task, key, reason, approval_file):
    if key not in ("visual_qa", "browser_capability"):
        raise ValueError("Only browser capability or visual QA may be waived")
    if not reason or not reason.strip():
        raise ValueError("Waiver requires a reason")
    p = approval_file.resolve()
    try:
        relative = p.relative_to(root.resolve())
    except ValueError:
        raise ValueError("Approval evidence must be inside the target project")
    if not p.is_file() or not p.stat().st_size:
        raise ValueError("Approval evidence must exist and be nonempty")
    task.setdefault("waivers", {})[key] = {
        "key": key, "reason": reason.strip(),
        "approval": {"path": str(relative), "sha256": sha(p.read_bytes())},
    }

def conflict(a, b):
    def overlaps(xs, ys):
        for x in xs:
            for y in ys:
                x, y = str(x).rstrip("/"), str(y).rstrip("/")
                if x == y or x.startswith(y + "/") or y.startswith(x + "/"):
                    return True
        return False
    return overlaps(a.get("writes", []), b.get("writes", [])) or overlaps(a.get("resources", []), b.get("resources", []))

def swarm(plan):
    tasks = plan.get("tasks")
    max_workers = plan.get("max_workers", 4)
    if not isinstance(tasks, list) or not tasks or not isinstance(max_workers, int) or max_workers < 1:
        raise ValueError("Expected nonempty tasks and positive max_workers")
    index = {}
    for t in tasks:
        key = t.get("id")
        if not isinstance(key, str) or not key or key in index:
            raise ValueError("Task IDs must be unique nonempty strings")
        if any(not isinstance(t.get(k, []), list) for k in ("depends_on", "writes", "resources")):
            raise ValueError("Task dependencies/writes/resources must be arrays")
        index[key] = t
    for t in tasks:
        if set(t.get("depends_on", [])) - index.keys():
            raise ValueError("Unknown dependency in " + t["id"])
        if t["id"] in t.get("depends_on", []):
            raise ValueError("Self-dependency")
    done, waves = set(), []
    while len(done) < len(tasks):
        ready = [t for t in tasks if t["id"] not in done and set(t.get("depends_on", [])) <= done]
        if not ready:
            raise ValueError("Cycle in task dependencies")
        wave = []
        for t in ready:
            if len(wave) >= max_workers:
                break
            if all(not conflict(t, w) for w in wave):
                wave.append(t)
        if not wave:
            raise ValueError("No schedulable task")
        waves.append([t["id"] for t in wave])
        done.update(t["id"] for t in wave)
    return {"waves": waves, "max_workers": max_workers, "note": "Planning only; no agents started. Validate hidden resource conflicts manually."}

def main():
    parser = argparse.ArgumentParser(description="Agentic Engineering Kit reference tools")
    sub = parser.add_subparsers(dest="action", required=True)
    s = sub.add_parser("inspect"); s.add_argument("--target", type=Path, required=True)
    s = sub.add_parser("install")
    s.add_argument("--target", type=Path, required=True)
    s.add_argument("--mode", choices=("new", "existing"), required=True)
    s.add_argument("--apply", action="store_true", help="Apply the reviewed plan")
    s.add_argument("--plan-id")
    s.add_argument("--activate", action="store_true", help="Include skill activation in the same plan")
    s.add_argument("--profile", action="append", default=[])
    s = sub.add_parser("new-project")
    s.add_argument("--target", type=Path, required=True,
                   help="Existing empty project directory")
    s.add_argument("--title", required=True, help="Project title for README and the harness")
    s.add_argument("--provider", action="append", default=[],
                   help="Install project commands for this provider; repeatable")
    s.add_argument("--profile", action="append", default=[])
    s.add_argument("--apply", action="store_true", help="Apply the reviewed plan")
    s.add_argument("--plan-id")
    s = sub.add_parser("upgrade")
    s.add_argument("--target", type=Path, required=True)
    s.add_argument("--apply", action="store_true")
    s.add_argument("--plan-id", help="Exact plan_id from the dry-run; required with --apply")
    s.add_argument("--diff", action="store_true", help="Show text changes for unmodified AEK files")
    s = sub.add_parser("rollback")
    s.add_argument("--target", type=Path, required=True)
    s.add_argument("--id", required=True, help="backup_id returned by upgrade")
    s.add_argument("--apply", action="store_true")
    s.add_argument("--plan-id", help="Exact plan_id from rollback dry-run")
    s = sub.add_parser("update")
    s.add_argument("--target", type=Path, required=True)
    s.add_argument("--candidates", type=Path)
    s.add_argument("--component", action="append")
    s.add_argument("--apply", action="store_true")
    s.add_argument("--plan-id")
    s = sub.add_parser("recover")
    s.add_argument("--target", type=Path, required=True)
    s.add_argument("--id", required=True)
    s.add_argument("--apply", action="store_true")
    s.add_argument("--plan-id")
    s = sub.add_parser("components")
    csub = s.add_subparsers(dest="component_action", required=True)
    c = csub.add_parser("register")
    c.add_argument("--target", type=Path, required=True)
    c.add_argument("--file", type=Path, required=True)
    c.add_argument("--apply", action="store_true")
    c.add_argument("--plan-id")
    for name in ("doctor", "list-tasks"):
        s = sub.add_parser(name)
        s.add_argument("--target", type=Path, required=True)
    s = sub.add_parser("resume")
    s.add_argument("--target", type=Path, required=True)
    s.add_argument("--task", required=True)
    s.add_argument("--allow-waiver", action="store_true")
    s = sub.add_parser("transition")
    s.add_argument("--target", type=Path, required=True)
    s.add_argument("--task", required=True)
    s.add_argument("--state", choices=("active", "blocked", "failed", "rework", "cancelled"), required=True)
    s.add_argument("--reason", required=True)
    s.add_argument("--to", choices=PHASES)
    s = sub.add_parser("swarm"); s.add_argument("--plan", type=Path, required=True)
    s = sub.add_parser("new-task")
    s.add_argument("--target", type=Path, required=True); s.add_argument("--id", required=True)
    s.add_argument("--title", required=True); s.add_argument("--profile", choices=("lite", "standard", "critical"), default="standard")
    s.add_argument("--ui", action="store_true")
    s.add_argument("--runtime", choices=("none", "http"), default="none")
    s.add_argument("--process", choices=("change", "fix", "update"))
    s.add_argument("--stop-after", choices=PHASES, default="verification")
    s.add_argument("--user-message-ref", help="Reference to the actual user instruction, not a generated approval file")
    s.add_argument("--user-instruction", help="Exact relevant user instruction; local provenance is not authenticated")
    s = sub.add_parser("boundary")
    s.add_argument("--target", type=Path, required=True)
    s.add_argument("--task", required=True)
    s.add_argument("--stop-after", choices=PHASES, required=True)
    s.add_argument("--user-message-ref", required=True)
    s.add_argument("--user-instruction", required=True)
    s = sub.add_parser("review")
    s.add_argument("review_action", choices=("assign", "import-launch", "import-result"))
    s.add_argument("--target", type=Path, required=True)
    s.add_argument("--task", required=True)
    s.add_argument("--file", type=Path, required=True)
    s.add_argument("--assignment")
    s.add_argument("--fixture", action="store_true")
    s = sub.add_parser("execution")
    s.add_argument("execution_action", choices=("route",))
    s.add_argument("--target", type=Path, required=True)
    s.add_argument("--task", required=True)
    s.add_argument("--file", type=Path, required=True)
    s = sub.add_parser("record")
    s.add_argument("--target", type=Path, required=True); s.add_argument("--task", required=True)
    s.add_argument("--key", required=True)
    source = s.add_mutually_exclusive_group(required=True)
    source.add_argument("--file", type=Path)
    source.add_argument("--run", help="One reviewed executable and arguments; never run through a shell")
    s.add_argument("--allow-env", action="append", default=[], help="Explicitly allow inherited environment variable by name; never provide values")
    s.add_argument("--timeout", type=int, default=300)
    s.add_argument("--allow-dirty", action="store_true", help="Diagnostic run only; cannot pass release gate")
    s.add_argument("--reason", help="Required with --allow-dirty")
    s = sub.add_parser("waive")
    s.add_argument("--target", type=Path, required=True); s.add_argument("--task", required=True)
    s.add_argument("--key", choices=("visual_qa", "browser_capability"), required=True)
    s.add_argument("--reason", required=True); s.add_argument("--approval-file", type=Path, required=True)
    s = sub.add_parser("status")
    s.add_argument("--target", type=Path, required=True); s.add_argument("--task", required=True)
    s.add_argument("--allow-waiver", action="store_true")
    s = sub.add_parser("advance")
    s.add_argument("--target", type=Path, required=True); s.add_argument("--task", required=True)
    s.add_argument("--to", choices=PHASES, required=True)
    s.add_argument("--allow-waiver", action="store_true", help="Requires recorded human approval; not trusted by CI by default")
    s = sub.add_parser("verify")
    s.add_argument("--target", type=Path, required=True); s.add_argument("--task", required=True)
    s.add_argument("--base", help="Trusted base Git ref for changed-file scope checks")
    s.add_argument("--required-profile", choices=("lite", "standard", "critical"))
    s.add_argument("--require-runtime", choices=("none", "http"), help="Required runtime profile in trusted CI")
    s.add_argument("--require-command", action="append", default=[],
                   help="Trusted key=exact command binding, e.g. tests=python3 -m pytest")
    s.add_argument("--allow-waiver", action="store_true", help="Disabled in CI by default")
    s = sub.add_parser("context")
    s.add_argument("--target", type=Path, required=True)
    s.add_argument("--task", required=True)
    s.add_argument("--file", type=Path, required=True, help="JSON context patch, no implicit approvals")
    s = sub.add_parser("events")
    s.add_argument("--target", type=Path, required=True)
    s.add_argument("--task")
    s.add_argument("--after", type=int, default=0)
    s.add_argument("--limit", type=int, default=100)
    s = sub.add_parser("state-recover")
    s.add_argument("--target", type=Path, required=True)
    s = sub.add_parser("environment")
    s.add_argument("environment_action", choices=("plan", "apply", "check"))
    s.add_argument("--target", type=Path, required=True)
    s.add_argument("--plan-id")
    s = sub.add_parser("process")
    s.add_argument("process_action", choices=("start", "status", "start-step", "finish-step", "retry", "resume-step", "refresh-step"))
    s.add_argument("--target", type=Path, required=True)
    s.add_argument("--task", required=True)
    s.add_argument("--name", choices=("change", "fix", "update"))
    s.add_argument("--step")
    s.add_argument("--outcome", choices=("passed", "failed", "blocked"))
    s.add_argument("--reason")
    s.add_argument("--allow-waiver", action="store_true")
    a = parser.parse_args()
    try:
        if a.action == "inspect":
            dump(inspect(a.target.resolve()))
        elif a.action == "install":
            return install(Path(__file__).resolve().parents[1], a.target, a.mode, a.apply,
                           plan_id=a.plan_id, activate=a.activate, profiles=a.profile)
        elif a.action == "new-project":
            result = new_project(Path(__file__).resolve().parents[1], a.target, a.title,
                                 a.provider, a.profile, apply=a.apply, plan_id=a.plan_id)
            dump(result)
            return 2 if result.get("conflicts") or result.get("passed") is False else 0
        elif a.action == "upgrade":
            result = mutation(a.target.resolve(), "upgrade", a.apply, a.plan_id, lambda:
                upgrade(Path(__file__).resolve().parents[1], a.target.resolve(),
                        apply=a.apply, plan_id=a.plan_id, show_diff=a.diff))
            dump(result)
            return 2 if result.get("conflicts") else 0
        elif a.action == "rollback":
            result = mutation(a.target.resolve(), "rollback", a.apply, a.plan_id, lambda:
                rollback(a.target.resolve(), a.id, apply=a.apply, plan_id=a.plan_id))
            dump(result)
            return 2 if result.get("conflicts") else 0
        elif a.action in ("components", "update", "recover", "doctor"):
            helper = load_helper("aek_components.py")
            root = a.target.resolve()
            source = Path(__file__).resolve().parents[1]
            if a.action == "components":
                result = mutation(root, "components.register", a.apply, a.plan_id, lambda:
                    helper.register(root, json.loads(a.file.read_text(encoding="utf-8")),
                                    apply=a.apply, plan_id=a.plan_id))
            elif a.action == "update":
                result = mutation(root, "update", a.apply, a.plan_id, lambda:
                    helper.update(source, root, candidates=a.candidates, selected=a.component,
                                  apply=a.apply, plan_id=a.plan_id))
            elif a.action == "recover":
                result = mutation(root, "recover", a.apply, a.plan_id, lambda:
                    helper.recover(source, root, a.id, apply=a.apply, plan_id=a.plan_id))
            else:
                result = helper.doctor(root)
            dump(result)
            return 2 if result.get("passed") is False or result.get("conflicts") or result.get("state") in ("failed", "partial", "recovery_failed") else 0
        elif a.action == "events":
            dump(load_helper("aek_state.py").read_events(a.target.resolve(), a.after, a.limit, a.task))
        elif a.action == "state-recover":
            dump(load_helper("aek_state.py").recover(a.target.resolve()))
        elif a.action == "environment":
            engine = load_helper("aek_environment.py")
            root = a.target.resolve()
            result = mutation(root, "environment.lock", True, a.plan_id, lambda:
                engine.apply(root, a.plan_id)) if a.environment_action == "apply" else getattr(engine, a.environment_action)(root)
            dump(result)
            return 2 if result.get("passed") is False or result.get("conflicts") else 0
        elif a.action == "list-tasks":
            dump(load_helper("aek_workflow.py").list_tasks(a.target.resolve(), load_task))
        elif a.action == "swarm":
            dump(swarm(json.loads(a.plan.read_text(encoding="utf-8"))))
        elif a.action == "new-task":
            root = a.target.resolve()
            if not (root / ".agentic-kit" / "AGENTS.md").is_file():
                raise ValueError("Install AEK before creating tasks")
            path = task_file(root, a.id)
            if path.exists():
                raise ValueError("Task already exists")
            task = {"id": a.id, "title": a.title, "profile": a.profile,
                    "ui": a.ui, "runtime": a.runtime, "stage": "discovery", "status": "active",
                    "schema_version": 2, "revision": 0, "context": {}, "history": [], "evidence": {}}
            load_helper("aek_workflow.py").set_boundary(task, PHASES, a.stop_after,
                a.user_message_ref, a.user_instruction, initial=True)
            if a.profile in ("standard", "critical"):
                task["review_policy"] = 2
            if a.process:
                engine = load_helper("aek_process.py")
                engine.start(task, engine.load_definition(Path(__file__).resolve().parents[1], a.process))
            save_task(path, task); dump(task)
        else:
            root = a.target.resolve()
            path, task = load_task(root, a.task)
            if a.action in ("record", "waive", "review", "execution") and (task.get("status") == "cancelled" or task["stage"] == "done"):
                raise ValueError("Terminal task; create a follow-up task before executing or changing evidence")
            if a.action == "boundary":
                changed = load_helper("aek_workflow.py").set_boundary(task, PHASES,
                    a.stop_after, a.user_message_ref, a.user_instruction)
                if changed:
                    save_task(path, task, "task.execution_boundary_recorded")
                dump(task_status(root, task))
            elif a.action == "review":
                helper = load_helper("aek_reviews.py")
                outcome = {"changed": True, "conflict": False}
                if a.review_action == "assign":
                    helper.assign(task, json.loads(a.file.read_text()))
                else:
                    outcome = helper.observe(root, task, a.assignment, a.review_action.removeprefix("import-"), a.file, a.fixture)
                if outcome["changed"]:
                    save_task(path, task, "review." + a.review_action)
                dump(review_status(root, task))
                if outcome["conflict"]:
                    return 2
            elif a.action == "execution":
                changed = load_helper("aek_reviews.py").set_execution_route(
                    task, json.loads(a.file.read_text()))
                if changed:
                    save_task(path, task, "execution.route_recorded")
                dump(task_status(root, task))
            elif a.action == "resume":
                dump(task_status(root, task, getattr(a, "allow_waiver", False)))
            elif a.action == "context":
                load_helper("aek_workflow.py").update_context(task, json.loads(a.file.read_text()))
                save_task(path, task, "task.context_changed")
                dump(task_status(root, task, getattr(a, "allow_waiver", False)))
            elif a.action == "process":
                engine = load_helper("aek_process.py")
                effective, check, gates = process_view(root, task, a.allow_waiver)
                if a.process_action == "start":
                    if not a.name:
                        raise ValueError("process start requires --name")
                    engine.start(effective, engine.load_definition(Path(__file__).resolve().parents[1], a.name))
                elif a.process_action == "start-step":
                    engine.start_step(effective, a.step, check, gates)
                elif a.process_action == "finish-step":
                    if not a.outcome:
                        raise ValueError("finish-step requires --outcome")
                    engine.finish_step(effective, a.step, a.outcome, a.reason, check, gates)
                elif a.process_action == "retry":
                    engine.retry_step(effective, a.step, a.reason)
                elif a.process_action == "resume-step":
                    engine.resume_step(effective, a.step, a.reason, check, gates)
                elif a.process_action == "refresh-step":
                    engine.refresh_step(effective, a.step, a.reason, check, gates)
                if a.process_action != "status":
                    task["process"] = effective["process"]
                    save_task(path, task, "process." + a.process_action,
                              payload={"step": a.step, "outcome": a.outcome})
                dump(process_status(root, task, a.allow_waiver))
            elif a.action == "transition":
                load_helper("aek_workflow.py").transition(task, a.state, a.reason, PHASES, required, a.to)
                if a.state == "rework" and task.get("process"):
                    load_helper("aek_process.py").reset_for_rework(task, a.reason, lambda dest: required(task, dest))
                save_task(path, task, "task.transition")
                dump(task)
            elif a.action == "record":
                if a.run:
                    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,63}", a.key):
                        raise ValueError("Invalid evidence key")
                    from uuid import uuid4
                    state = load_helper("aek_state.py")
                    attempt = {"id": uuid4().hex, "task": a.task, "key": a.key,
                               "task_revision": task.get("revision", 0), "state": "running",
                               "started_at": state.now()}
                    state.attempt(root, attempt)
                    try:
                        item = _evidence_helper().command_record(root, a.task, a.key,
                                a.run, a.timeout, a.allow_dirty, a.reason, env_allow=a.allow_env)
                    except Exception as exc:
                        attempt.update(state="failed", error=type(exc).__name__)
                        state.attempt(root, attempt)
                        raise
                    item["attempt_id"] = attempt["id"]
                    attempt.update(state="passed" if item["exit_code"] == 0 else "failed", evidence=item)
                    state.attempt(root, attempt)
                    task.setdefault("evidence", {})[a.key] = item
                    try:
                        save_task(path, task, "check.attached", payload={"attempt_id": attempt["id"], "key": a.key})
                    except ValueError as exc:
                        dump({"ok": False, "attached": False, "attempt_id": attempt["id"],
                              "result": item, "error": {"code": "task_attachment_rejected", "message": str(exc)},
                              "next_action": "Reload task; inspect preserved attempt before deciding whether to rerun"})
                        return 2
                    dump(item)
                    return 2 if item["exit_code"] != 0 else 0
                if a.allow_dirty or a.reason or a.allow_env:
                    raise ValueError("--allow-dirty/--reason apply only to --run")
                add_evidence(root, task, a.key, a.file)
                save_task(path, task, "evidence.recorded", payload={"key": a.key})
                dump(task["evidence"][a.key])
            elif a.action == "waive":
                add_waiver(root, task, a.key, a.reason, a.approval_file)
                save_task(path, task)
                dump({"waiver_recorded": a.key, "note": "Local approval file is not independently authenticated"})
            elif a.action == "status":
                dump(task_status(root, task, getattr(a, "allow_waiver", False)))
            elif a.action == "advance":
                if task.get("status", "active") != "active":
                    raise ValueError("Task is not active; resolve the recorded blocker before advancing")
                if task.get("context", {}).get("blockers"):
                    raise ValueError("Resolve context blockers before advancing")
                load_helper("aek_workflow.py").check_boundary(task, PHASES, a.to)
                if task.get("process"):
                    process = process_status(root, task, a.allow_waiver)
                    unfinished = [s["id"] for s in process["steps"] if s["stage"] == task["stage"]
                                  and (s["state"] != "passed" or s["stale"])]
                    if unfinished:
                        raise ValueError("Complete current process step before advancing: " + ", ".join(unfinished))
                cur = PHASES.index(task["stage"]); wanted = PHASES.index(a.to)
                if wanted != cur + 1:
                    raise ValueError("Only one forward phase transition is allowed")
                gaps = missing(root, task, a.to, allow_waiver=a.allow_waiver)
                if gaps:
                    raise ValueError("Missing, failed or stale evidence: " + ", ".join(gaps))
                task.setdefault("history", []).append({"at": load_helper("aek_state.py").now(), "event": "advance", "from": task["stage"], "to": a.to})
                task["stage"] = a.to
                save_task(path, task, "task.advanced")
                dump({"task": a.task, "stage": a.to, "waiver_mode": a.allow_waiver})
            elif a.action == "verify":
                helper = _evidence_helper()
                state = helper.git_snapshot(root)
                problems = []
                boundary = load_helper("aek_workflow.py").boundary_status(task, PHASES)
                if not boundary["passed"]:
                    problems.append(boundary["next_action"])
                if task.get("status", "active") != "active":
                    problems.append("task_not_active")
                if task.get("context", {}).get("blockers"):
                    problems.append("unresolved_context_blockers")
                if task.get("process"):
                    process = process_status(root, task, a.allow_waiver)
                    for step in process["steps"]:
                        if PHASES.index(step["stage"]) <= PHASES.index("verification") and (step["state"] != "passed" or step["stale"]):
                            problems.append("process_step_not_verified:" + step["id"])
                if state["dirty"]:
                    problems.append("uncommitted_source_changes")
                if task.get("stage") not in ("verification", "release", "done"):
                    problems.append("workflow_not_at_verification")
                problems += helper.scope_problems(root, task, a.base, a.required_profile)
                if a.require_runtime and task.get("runtime", "none") != a.require_runtime:
                    problems.append("runtime_profile_below_required")
                for binding in a.require_command:
                    if "=" not in binding:
                        raise ValueError("--require-command expects key=exact command")
                    key, expected = binding.split("=", 1)
                    if key not in ("tests", "runtime_smoke") or not expected:
                        raise ValueError("--require-command supports tests and runtime_smoke")
                    recorded = task.get("evidence", {}).get(key, {})
                    if not isinstance(recorded, dict) or recorded.get("source") != "executed" or recorded.get("command") != expected:
                        problems.append("unexpected_check_command:" + key)
                checks = list(missing(root, task, "release", allow_waiver=a.allow_waiver))
                if task.get("ui"):
                    checks += missing(root, task, "design", allow_waiver=a.allow_waiver)
                    checks = list(dict.fromkeys(x for x in checks if x == "browser_capability" or x in required(task, "release")))
                output = {"task": a.task, "git_commit": state["git_commit"],
                          "profile": task["profile"], "ui": bool(task.get("ui")),
                          "execution_boundary": boundary,
                          "independent_reviews": review_status(root, task),
                          "missing": checks, "problems": problems,
                          "waiver_mode": a.allow_waiver,
                          "passed": not checks and not problems,
                          "warning": "Local task/evidence can be modified by the agent; use a protected CI job for independent verification"}
                dump(output)
                return 0 if output["passed"] else 2
        return 0
    except (ValueError, OSError, KeyError, json.JSONDecodeError,
            subprocess.TimeoutExpired) as ex:
        print("AEK ERROR:", ex, file=sys.stderr)
        code = ("stale_task_revision" if type(ex).__name__ == "StaleState"
                else getattr(ex, "code", "operation_rejected"))
        dump({"ok": False, "error": {"code": code, "message": str(ex)},
              "next_action": "Review the error and current state before retrying"})
        return 2

if __name__ == "__main__":
    sys.exit(main())
