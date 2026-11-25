"""Evidence execution and verification for AEK. Python standard library only.

Local task metadata and logs are not trusted attestations. Use protected CI for
independent evidence of the exact commit. Never run unreviewed commands.
"""
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess

MAX_OUTPUT = 5 * 1024 * 1024
RUN_KEYS = frozenset(("tests", "runtime_smoke"))
WAIVABLE_KEYS = frozenset(("visual_qa", "browser_capability"))
IGNORE_STATUS = [
    ".", ":(exclude).agentic-kit/tasks", ":(exclude).agentic-kit/evidence",
    ":(exclude).agentic-kit/attempts", ":(exclude).agentic-kit/events",
    ":(exclude).agentic-kit/state.lock", ":(exclude).agentic-kit/state-pending.json",
]
UI_SUFFIXES = frozenset((".tsx", ".jsx", ".vue", ".svelte", ".html", ".css", ".scss", ".sass"))


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def git(root, *args):
    try:
        proc = subprocess.run(
            ["git", "-C", str(root), *args],
            capture_output=True, text=True, check=False, timeout=15,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ValueError("Git unavailable: " + str(exc)) from exc
    if proc.returncode != 0:
        raise ValueError("Git command failed: " + " ".join(args) + ": " + proc.stderr.strip()[:200])
    return proc.stdout.strip()


def git_snapshot(root):
    commit = git(root, "rev-parse", "--verify", "HEAD")
    if not re.fullmatch(r"[0-9a-f]{40,64}", commit):
        raise ValueError("Unrecognized Git HEAD")
    dirty_output = git(root, "status", "--porcelain", "--untracked-files=all", "--", *IGNORE_STATUS)
    return {"git_commit": commit, "dirty": bool(dirty_output)}


def inside(root, name):
    if not isinstance(name, str) or not name or Path(name).is_absolute():
        raise ValueError("Evidence path must be relative to target")
    target = (Path(root) / name).resolve()
    try:
        target.relative_to(Path(root).resolve())
    except ValueError as exc:
        raise ValueError("Evidence path escapes target") from exc
    return target


def execution_helper():
    import importlib.util
    path = Path(__file__).with_name("aek_execution.py")
    spec = importlib.util.spec_from_file_location("aek_evidence_execution", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def evidence_directory(root, task_id):
    if not isinstance(task_id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", task_id):
        raise ValueError("Invalid task ID")
    root = Path(root)
    if root.is_symlink() or not root.is_dir():
        raise ValueError("Unsafe evidence root")
    current = root
    parents = []
    for part in (".agentic-kit", "evidence", task_id):
        current = current / part
        if current.is_symlink() or current.exists() and not current.is_dir():
            raise ValueError("Unsafe evidence directory: " + str(current))
        parents.append(current)
    # Validate every existing ancestor before creating even the first directory.
    for directory in parents:
        directory.mkdir(exist_ok=True)
        if directory.is_symlink() or not directory.is_dir():
            raise ValueError("Evidence directory changed during creation")
    return current


def command_record(root, task_id, key, command, timeout=300, allow_dirty=False, reason=None, env_allow=()):
    if key not in RUN_KEYS:
        raise ValueError("Use --file for documents and approvals; --run for executable checks")
    if not isinstance(command, str) or not command.strip():
        raise ValueError("Empty run command")
    if not 1 <= timeout <= 1800:
        raise ValueError("Timeout must be between 1 and 1800 seconds")
    if allow_dirty and (not reason or not reason.strip()):
        raise ValueError("--allow-dirty requires --reason")
    args = shlex.split(command)
    if not args or args[0].startswith("-"):
        raise ValueError("Invalid executable")
    if any(a in ("|", "&&", "||", ";", ">", ">>", "<") for a in args):
        raise ValueError("Shell operators are not supported; pass one executable and arguments")
    before = git_snapshot(root)
    if before["dirty"] and not allow_dirty:
        raise ValueError("Working tree has source changes; commit or use --allow-dirty with a reason for diagnostic runs")
    runner = execution_helper()
    runner.environment(env_allow)  # Reject malformed policy before creating files.
    evidence_dir = evidence_directory(root, task_id)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    path = evidence_dir / (key + "-" + stamp + ".log")
    started_at = now()
    try:
        result = runner.run(args, root, timeout, MAX_OUTPUT, env_allow, path)
    except OSError as exc:
        raise ValueError("Cannot write evidence log: " + str(exc)) from exc
    after = git_snapshot(root)
    relative = path.relative_to(Path(root))
    return {
        "source": "executed", "path": str(relative),
        "sha256": digest(path), "command": command, "exit_code": result["exit_code"],
        "started_at": started_at, "finished_at": now(),
        "git_commit": before["git_commit"],
        "dirty": before["dirty"] or after["dirty"] or before["git_commit"] != after["git_commit"],
        "dirty_reason": reason.strip() if allow_dirty else None,
        "timed_out": result["timed_out"], "output_truncated": result["truncated"],
        "env_policy": result["env_policy"],
    }


def validate_report(root, key, filename):
    """Check report format and bound screenshot hashes, not claimed authenticity."""
    if key not in ("visual_qa", "browser_capability"):
        return {}
    try:
        report = json.loads(Path(filename).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError(key + " requires a valid JSON report") from exc
    if not isinstance(report, dict):
        raise ValueError("Report must be an object")
    if key == "browser_capability":
        if report.get("status") != "available":
            raise ValueError("Browser status must be available; otherwise request an explicit waiver")
        for field in ("tool", "version", "verified_at", "mode"):
            if not isinstance(report.get(field), str) or not report[field].strip():
                raise ValueError("Browser capability missing " + field)
        return {}
    for field in ("browser", "browser_version", "url", "keyboard", "focus"):
        if not isinstance(report.get(field), str) or not report[field].strip():
            raise ValueError("Visual QA missing " + field)
    viewport = report.get("viewport")
    if not isinstance(viewport, dict) or not all(isinstance(viewport.get(x), int) and viewport[x] > 0 for x in ("width", "height")):
        raise ValueError("Visual QA requires a valid viewport")
    scenarios = report.get("scenarios")
    if not isinstance(scenarios, list) or not scenarios or any(
        not isinstance(s, dict) or not all(isinstance(s.get(k), str) and s[k].strip() for k in ("name", "expected", "actual")) or s.get("passed") is not True
        for s in scenarios
    ):
        raise ValueError("Visual QA requires passed scenarios with expected and actual results")
    if report.get("result") != "passed":
        raise ValueError("Visual QA was not marked passed")
    if not isinstance(report.get("console_errors"), list) or not isinstance(report.get("network_errors"), list):
        raise ValueError("Visual QA requires console_errors and network_errors arrays")
    if report["console_errors"] or report["network_errors"]:
        raise ValueError("Visual QA report contains unresolved browser errors")
    screenshots = report.get("screenshots")
    if not isinstance(screenshots, list) or not screenshots:
        raise ValueError("Visual QA requires at least one screenshot")
    attachments = {}
    for name in screenshots:
        file = inside(root, name)
        if not file.is_file() or file.stat().st_size == 0:
            raise ValueError("Visual QA screenshot missing: " + str(name))
        with file.open("rb") as stream:
            prefix = stream.read(12)
        extension = file.suffix.lower()
        valid_image = ((extension == ".png" and prefix.startswith(b"\x89PNG\r\n\x1a\n"))
                       or (extension in (".jpg", ".jpeg") and prefix.startswith(b"\xff\xd8"))
                       or (extension == ".webp" and prefix.startswith(b"RIFF")
                           and prefix[8:12] == b"WEBP"))
        if not valid_image:
            raise ValueError("Visual QA screenshot format is not PNG/JPEG/WebP: " + str(name))
        attachments[name] = digest(file)
    recorded_commit = report.get("git_commit")
    if recorded_commit != git_snapshot(root)["git_commit"]:
        raise ValueError("Visual QA report must identify current Git HEAD")
    return {"attachments": attachments, "git_commit": recorded_commit}


def evidence_valid(root, key, item):
    if not isinstance(item, dict):
        return False
    try:
        path = inside(root, item.get("path"))
        if not path.is_file() or digest(path) != item.get("sha256"):
            return False
        if key not in RUN_KEYS and not path.stat().st_size:
            return False
        if key in RUN_KEYS:
            if item.get("source") != "executed":
                return False
            state = git_snapshot(root)
            return (item.get("exit_code") == 0
                    and item.get("git_commit") == state["git_commit"]
                    and not item.get("dirty")
                    and not state["dirty"]
                    and not item.get("timed_out")
                    and not item.get("output_truncated")
                    and bool(item.get("command")))
        if key == "visual_qa":
            if item.get("source") != "report":
                return False
            state = git_snapshot(root)
            if item.get("git_commit") != state["git_commit"] or state["dirty"]:
                return False
            for name, expected in item.get("attachments", {}).items():
                if digest(inside(root, name)) != expected:
                    return False
            return bool(item.get("attachments"))
        return True
    except (OSError, ValueError, TypeError):
        return False


def waiver_valid(root, item, key):
    if key not in WAIVABLE_KEYS or not isinstance(item, dict):
        return False
    if item.get("key") != key or not isinstance(item.get("reason"), str) or not item["reason"].strip():
        return False
    approval = item.get("approval")
    if not isinstance(approval, dict):
        return False
    try:
        p = inside(root, approval.get("path"))
        return p.is_file() and digest(p) == approval.get("sha256")
    except (OSError, ValueError, TypeError):
        return False


def changed_paths(root, base):
    if not base:
        return []
    git(root, "rev-parse", "--verify", base)
    result = git(root, "diff", "--name-only", "-z", "--diff-filter=ACDMRT", base + "...HEAD")
    return [x for x in result.split("\0") if x]


def has_ui_change(paths):
    return any(Path(p).suffix.lower() in UI_SUFFIXES or "/components/" in "/" + p.lower()
               for p in paths)


def scope_problems(root, task, base=None, required_profile=None):
    problems = []
    if required_profile in ("standard", "critical"):
        if task["profile"] == "lite" or (required_profile == "critical" and task["profile"] != "critical"):
            problems.append("task_profile_below_required")
    if base:
        files = changed_paths(root, base)
        if has_ui_change(files) and not task.get("ui"):
            problems.append("ui_changes_without_ui_task")
    return problems
