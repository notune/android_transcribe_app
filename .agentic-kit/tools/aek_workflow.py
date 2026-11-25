"""Small task lifecycle helpers. No agent launch and no inferred approvals."""
import copy
import datetime as dt

STATUSES = ("active", "blocked", "failed", "rework", "cancelled")


def validate_boundary(value, phases):
    if not isinstance(value, dict) or set(value) != {"stop_after", "source"} or value["stop_after"] not in phases:
        raise ValueError("Invalid execution boundary")
    source = value["source"]
    if not isinstance(source, dict):
        raise ValueError("Invalid execution boundary source")
    if source == {"kind": "safe_default"}:
        if value["stop_after"] != "verification":
            raise ValueError("Safe default boundary must be verification")
    elif set(source) != {"kind", "reference", "instruction"} or source.get("kind") != "user_message" or any(
            not isinstance(source.get(key), str) or not source[key].strip() or len(source[key]) > 8000
            for key in ("reference", "instruction")):
        raise ValueError("Execution boundary requires a user message reference and instruction; a local approval file is not consent")
    return value


def set_boundary(task, phases, stop_after, reference=None, instruction=None, initial=False):
    """Record claimed user instruction; this is not authentication of its author."""
    if task.get("stage") == "done" or task.get("status") == "cancelled":
        raise ValueError("Historical terminal task cannot change execution boundary")
    if stop_after not in phases:
        raise ValueError("Invalid execution boundary stage")
    source = ({"kind": "safe_default"} if initial and stop_after == "verification" and reference is None and instruction is None
              else {"kind": "user_message", "reference": reference, "instruction": instruction})
    value = validate_boundary({"stop_after": stop_after, "source": source}, phases)
    previous = task.get("execution_boundary")
    if previous == value:
        return False
    task["execution_boundary"] = copy.deepcopy(value)
    task.setdefault("history", []).append({"event": "execution_boundary.recorded",
        "at": dt.datetime.now(dt.timezone.utc).isoformat(), "previous": copy.deepcopy(previous),
        "boundary": copy.deepcopy(value)})
    return True


def boundary_status(task, phases):
    value = task.get("execution_boundary")
    historical = task.get("stage") == "done" or task.get("status") == "cancelled"
    if value is None:
        return {"configured": False, "historical_terminal": historical,
                "passed": historical, "next_action": "none" if historical else "record_execution_boundary"}
    validate_boundary(value, phases)
    exceeded = phases.index(task["stage"]) > phases.index(value["stop_after"])
    return {"configured": True, "historical_terminal": historical, "stop_after": value["stop_after"],
            "passed": not exceeded, "reached": task["stage"] == value["stop_after"],
            "exceeded": exceeded, "source": copy.deepcopy(value["source"]),
            "next_action": "resolve_execution_boundary" if exceeded else "none",
            "trust": "Locally recorded user instruction; author and consent are not authenticated"}


def check_boundary(task, phases, destination=None):
    value = boundary_status(task, phases)
    if not value["configured"]:
        raise ValueError("Record execution boundary from the user's instruction before progressing legacy task")
    if not value["passed"] or (destination is not None and phases.index(destination) > phases.index(value["stop_after"])):
        raise ValueError("Execution boundary forbids work after " + value["stop_after"])


def validate(task, phases):
    if not isinstance(task, dict) or task.get("stage") not in phases:
        raise ValueError("Invalid task stage")
    if task.get("status", "active") not in STATUSES:
        raise ValueError("Invalid task status")
    if task.get("profile") not in ("lite", "standard", "critical"):
        raise ValueError("Invalid task profile")
    if not isinstance(task.get("evidence"), dict) or not isinstance(task.get("history", []), list):
        raise ValueError("Invalid task evidence or history")
    schema = task.get("schema_version", 1)
    if type(schema) is not int or schema not in (1, 2):
        raise ValueError("Unsupported task schema_version")
    revision = task.get("revision", 0)
    if type(revision) is not int or revision < 0:
        raise ValueError("Invalid task revision")
    validate_context(task.get("context", {}))
    if "execution_boundary" in task:
        validate_boundary(task["execution_boundary"], phases)


def validate_context(value):
    fields = {"owner", "branch", "worktree", "scope", "next_action", "summary", "base_commit", "blockers"}
    if not isinstance(value, dict) or set(value) - fields:
        raise ValueError("Invalid task context fields")
    for key, content in value.items():
        if key == "blockers":
            if not isinstance(content, list) or len(content) > 30 or any(not isinstance(s, str) or not s.strip() or len(s) > 2000 for s in content):
                raise ValueError("Invalid context blockers")
        elif not isinstance(content, str) or len(content) > 8000:
            raise ValueError("Invalid context " + key)


def update_context(task, values):
    validate_context(values)
    if task.get("status") == "cancelled" or task["stage"] == "done":
        raise ValueError("Terminal task cannot change context")
    task["context"] = {**task.get("context", {}), **values}


def transition(task, state, reason, phases, required, destination=None):
    validate(task, phases)
    if state not in STATUSES or not reason or not reason.strip():
        raise ValueError("Transition requires a valid state and reason")
    old = task.get("status", "active")
    if old == "cancelled" or task["stage"] == "done":
        raise ValueError("Terminal task; create a new task instead")
    if state == old:
        raise ValueError("Task is already in that state")
    if destination is not None and state != "rework":
        raise ValueError("--to is only valid for rework")
    if state == "active":
        check_boundary(task, phases, destination)
    event = {"at": dt.datetime.now(dt.timezone.utc).isoformat(),
             "event": "transition", "from": old, "to": state, "reason": reason.strip(),
             "stage": task["stage"]}
    if state == "rework":
        if destination not in ("discovery", "design", "planning", "implementation"):
            raise ValueError("Rework requires --to discovery, design, planning or implementation")
        if phases.index(destination) > phases.index(task["stage"]):
            raise ValueError("Rework cannot skip forward")
        # Repair an already-exceeded boundary by returning into scope, without
        # authorizing work at the old stage or widening the recorded user limit.
        check_boundary({**task, "stage": destination}, phases, destination)
        keys = set()
        for phase in phases[phases.index(destination) + 1:]:
            keys.update(required(task, phase))
        event["invalidated_evidence"] = {key: task["evidence"].pop(key) for key in sorted(keys)
                                         if key in task["evidence"]}
        event["invalidated_waivers"] = {key: task.get("waivers", {}).pop(key) for key in sorted(keys)
                                       if key in task.get("waivers", {})}
        event["return_to"] = destination
        task["stage"] = destination
    task["status"] = state
    task["reason"] = reason.strip()
    task.setdefault("history", []).append(event)


def resume(root, task, phases, missing):
    """Read-only handoff; a resumed chat cannot silently clear task blockers."""
    validate(task, phases)
    stage = task["stage"]
    active = task.get("status", "active") == "active"
    candidate = phases[phases.index(stage) + 1] if stage != phases[-1] else None
    boundary = boundary_status(task, phases)
    next_stage = candidate if active and boundary["configured"] and boundary["passed"] and not boundary.get("reached") else None
    try:
        import subprocess
        status = subprocess.run(["git", "-C", str(root), "status", "--porcelain"],
                                text=True, capture_output=True, timeout=15)
        head = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"],
                              text=True, capture_output=True, timeout=15)
        git = {"commit": head.stdout.strip() if head.returncode == 0 else None,
               "dirty": bool(status.stdout) if status.returncode == 0 else None}
    except (OSError, subprocess.TimeoutExpired):
        git = {"commit": None, "dirty": None}
    gaps = missing(root, task, candidate) if candidate else []
    context = task.get("context", {})
    blockers = list(context.get("blockers", []))
    if not active and task.get("reason"):
        blockers.insert(0, task["reason"])
    return {"task": task, "next": next_stage, "execution_boundary": boundary,
            "missing": gaps, "blockers": blockers,
            "context": context, "revision": task.get("revision", 0),
            "source_changed": bool(context.get("base_commit") and context["base_commit"] != git["commit"]),
            "git": git, "read_only": True,
            "next_action": "create_new_task" if task.get("status") == "cancelled" else
                           "review_and_resolve_blocker" if not active else
                           ("task_complete" if stage == phases[-1] else
                            boundary["next_action"] if not boundary["passed"] else
                            "review_and_resolve_blocker" if blockers else
                            "finish_boundary_stage" if boundary.get("reached") else
                            context.get("next_action") or "continue_current_stage"),
            "note": "Reuse recorded scope; recheck changed code and stale evidence. No approval is inferred."}


def list_tasks(root, load_task):
    folder = root / ".agentic-kit" / "tasks"
    if folder.is_symlink():
        raise ValueError("Unsafe task directory")
    tasks = []
    for path in sorted(folder.glob("*.json")):
        _, task = load_task(root, path.stem)
        tasks.append({key: task.get(key, "active" if key == "status" else None)
                      for key in ("id", "title", "stage", "status", "reason")})
    return {"tasks": tasks}
