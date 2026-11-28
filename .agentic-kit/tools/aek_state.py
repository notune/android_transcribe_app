"""Revisioned local task state and recoverable events. Not an approval service.

One cooperating writer holds a project flock. A durable intent joins the task
write and its event; interrupted commits are completed only by explicit recovery
or a subsequent mutation. Readers never repair or silently accept partial state.
"""
import contextlib
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import time
from uuid import uuid4

PENDING = '.agentic-kit/state-pending.json'
EVENTS = '.agentic-kit/events'
LOCK_TIMEOUT = 5.0
TASK_ID = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')
ATTEMPT_ID = re.compile(r'^[a-f0-9]{32}$')
CHECK_KEY = re.compile(r'^[A-Za-z][A-Za-z0-9_-]{0,63}$')
PHASES = ('discovery', 'design', 'planning', 'implementation', 'verification', 'release', 'done')


class StaleState(ValueError):
    pass


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode()


def digest(value):
    return hashlib.sha256(value).hexdigest() if value is not None else None


def safe(root, relative):
    root = Path(root)
    if root.is_symlink():
        raise ValueError('Unsafe state root')
    if not isinstance(relative, str) or not relative or '\\' in relative or Path(relative).is_absolute():
        raise ValueError('Unsafe state path')
    current = root
    for part in relative.split('/'):
        if part in ('', '.', '..'):
            raise ValueError('Unsafe state path')
        current = current / part
        if current.is_symlink():
            raise ValueError('Unsafe state path: symlink')
    return current


def atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.state-', delete=False) as output:
            temporary = Path(output.name)
            output.write(encoded(value))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if temporary and temporary.exists():
            temporary.unlink()


@contextlib.contextmanager
def locked(root, timeout=None):
    import fcntl
    timeout = LOCK_TIMEOUT if timeout is None else timeout
    if not isinstance(timeout, (int, float)) or not 0 <= timeout <= 60:
        raise ValueError('Invalid state lock timeout')
    path = safe(root, '.agentic-kit/state.lock')
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_RDWR | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    try:
        deadline = time.monotonic() + timeout
        while True:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError as exc:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise ValueError('State is busy; retry after the current writer finishes') from exc
                time.sleep(min(0.01, remaining))
        yield
    finally:
        os.close(fd)  # OS releases the lock even if the process crashes.


def ensure_readable(root):
    if safe(root, PENDING).exists():
        raise ValueError('Interrupted state transaction; run state-recover before reading')


def read_events(root, after=0, limit=100, subject=None):
    if type(after) is not int or after < 0 or type(limit) is not int or not 1 <= limit <= 1000:
        raise ValueError('Invalid event cursor/limit')
    ensure_readable(root)
    folder = safe(root, EVENTS)
    result = []
    if folder.exists():
        for path in sorted(folder.glob('*.json')):
            safe(root, str(path.relative_to(root)))
            event = json.loads(path.read_text())
            if event.get('schema_version') != 1 or type(event.get('sequence')) is not int:
                raise ValueError('Unsupported event record')
            if event['sequence'] > after and (subject is None or event['subject'] == subject):
                result.append(event)
                if len(result) == limit:
                    break
    ensure_readable(root)
    return {'schema_version': 1, 'events': result,
            'next_cursor': result[-1]['sequence'] if result else after}


def _next_sequence(root):
    folder = safe(root, EVENTS)
    names = sorted(folder.glob('*.json')) if folder.exists() else []
    if not names:
        return 1
    safe(root, str(names[-1].relative_to(root)))
    return json.loads(names[-1].read_text())['sequence'] + 1


def _validate_event(event):
    fields = {'schema_version', 'id', 'sequence', 'at', 'type', 'subject', 'actor', 'payload'}
    if not isinstance(event, dict) or set(event) != fields:
        raise ValueError('Invalid state event intent')
    if (type(event['schema_version']) is not int or event['schema_version'] != 1
            or type(event['sequence']) is not int or event['sequence'] < 1
            or not isinstance(event['id'], str) or not ATTEMPT_ID.fullmatch(event['id'])
            or not isinstance(event['payload'], dict)):
        raise ValueError('Invalid state event intent')
    for key, limit in (('at', 80), ('type', 160), ('subject', 160), ('actor', 160)):
        if not isinstance(event[key], str) or not event[key].strip() or len(event[key]) > limit:
            raise ValueError('Invalid state event ' + key)


def _validate_task(task, task_id):
    import importlib.util
    spec = importlib.util.spec_from_file_location('aek_state_workflow', Path(__file__).with_name('aek_workflow.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.validate(task, PHASES)
    if task.get('id') != task_id:
        raise ValueError('State task ID does not match destination')


def _validate_attempt(value):
    if not isinstance(value, dict):
        raise ValueError('Invalid attempt')
    for field, pattern in (('id', ATTEMPT_ID), ('task', TASK_ID), ('key', CHECK_KEY)):
        if not isinstance(value.get(field), str) or not pattern.fullmatch(value[field]):
            raise ValueError('Invalid attempt ' + field)
    if value.get('state') not in ('running', 'passed', 'failed'):
        raise ValueError('Invalid attempt state')
    if type(value.get('task_revision')) is not int or value['task_revision'] < 0:
        raise ValueError('Invalid attempt task revision')
    if type(value.get('schema_version', 1)) is not int or value.get('schema_version', 1) != 1:
        raise ValueError('Unsupported attempt schema')


def _prepare_intent(root, intent):
    """Validate the entire recovery record and both destinations before writes."""
    if (not isinstance(intent, dict) or set(intent) != {'schema_version', 'path', 'before_sha256', 'after', 'event'}
            or type(intent['schema_version']) is not int or intent['schema_version'] != 1):
        raise ValueError('Unsupported state intent')
    before = intent['before_sha256']
    if before is not None and (not isinstance(before, str) or not re.fullmatch(r'[a-f0-9]{64}', before)):
        raise ValueError('Invalid state intent checksum')
    event = intent['event']
    _validate_event(event)
    event_dest = safe(root, f"{EVENTS}/{event['sequence']:012d}-{event['id']}.json")
    if event_dest.exists() and event_dest.read_bytes() != encoded(event):
        raise ValueError('Event recovery conflict')
    # A sequence belongs to exactly one event, including an interrupted retry.
    for existing in event_dest.parent.glob(f"{event['sequence']:012d}-*.json"):
        if existing != event_dest:
            raise ValueError('Event sequence recovery conflict')
    destination, observed = None, None
    name = intent['path']
    if name is not None:
        if not isinstance(name, str):
            raise ValueError('Invalid state intent destination')
        task_match = re.fullmatch(r'\.agentic-kit/tasks/([A-Za-z0-9][A-Za-z0-9._-]{0,63})\.json', name)
        attempt_match = re.fullmatch(r'\.agentic-kit/attempts/([a-f0-9]{32})\.json', name)
        if task_match:
            _validate_task(intent['after'], task_match.group(1))
            if intent['after'].get('schema_version') != 2 or intent['after'].get('revision', 0) < 1:
                raise ValueError('Invalid state task revision/schema')
            subject = task_match.group(1)
        elif attempt_match:
            _validate_attempt(intent['after'])
            if intent['after']['id'] != attempt_match.group(1):
                raise ValueError('State attempt ID does not match destination')
            subject = intent['after']['task']
        else:
            raise ValueError('Invalid state intent destination')
        if subject != event['subject']:
            raise ValueError('State intent and event subjects differ')
        destination = safe(root, name)
        observed = destination.read_bytes() if destination.exists() else None
        if digest(observed) not in (before, digest(encoded(intent['after']))):
            raise ValueError('State recovery conflict; preserve files and inspect intent')
    elif intent['after'] is not None or before is not None:
        raise ValueError('Event-only intent cannot replace state')
    return destination, observed, event_dest


def _recover(root):
    pending = safe(root, PENDING)
    if not pending.exists():
        return False
    intent = json.loads(pending.read_text())
    destination, observed, event_dest = _prepare_intent(root, intent)
    if destination is not None and observed != encoded(intent['after']):
        atomic(destination, intent['after'])
    if not event_dest.exists():
        atomic(event_dest, intent['event'])
    pending.unlink()
    return True


def recover(root):
    with locked(root):
        restored = _recover(root)
    return {'schema_version': 1, 'recovered': restored}


def _commit(root, kind, subject, payload, actor, path=None, before=None, after=None):
    if not isinstance(actor, str) or not actor.strip() or len(actor) > 160:
        raise ValueError('Invalid event actor')
    event = {'schema_version': 1, 'id': uuid4().hex, 'sequence': _next_sequence(root),
             'at': now(), 'type': kind, 'subject': subject, 'actor': actor,
             'payload': payload}
    intent = {'schema_version': 1, 'path': path, 'before_sha256': digest(before),
              'after': after, 'event': event}
    _prepare_intent(root, intent)
    atomic(safe(root, PENDING), intent)
    _recover(root)
    return event


def emit(root, kind, subject, payload, actor='agent'):
    with locked(root):
        _recover(root)
        return _commit(root, kind, subject, payload, actor)


def save_task(path, task, kind='task.updated', actor='agent', payload=None):
    root = Path(path).parent.parent.parent
    relative = '.agentic-kit/tasks/' + Path(path).name
    expected = task.get('revision', 0)
    if type(expected) is not int or expected < 0:
        raise ValueError('Invalid task revision')
    with locked(root):
        _recover(root)
        dest = safe(root, relative)
        before = dest.read_bytes() if dest.exists() else None
        previous = json.loads(before) if before is not None else None
        actual = previous.get('revision', 0) if previous is not None else 0
        if actual != expected or (previous is None and expected != 0):
            raise StaleState('Task revision changed; reload task before writing')
        if previous is not None and (previous.get('status') == 'cancelled' or previous.get('stage') == 'done'):
            raise ValueError('Terminal task cannot be changed')
        updated = json.loads(json.dumps(task))
        updated.update(schema_version=2, revision=actual + 1, updated_at=now())
        event_data = {'revision': updated['revision'], 'stage': updated['stage'],
                      'status': updated.get('status', 'active'), **(payload or {})}
        _commit(root, 'task.created' if previous is None else kind, task['id'], event_data,
                actor, relative, before, updated)
        task.clear()
        task.update(updated)


def attempt(root, value, actor='agent'):
    """Persist a run independently, so stale task attachment cannot erase it.

    Starting a run validates its task revision under the state lock. Finishing
    a run preserves its result even if the owning task was cancelled meanwhile.
    """
    _validate_attempt(value)
    relative = '.agentic-kit/attempts/' + value['id'] + '.json'
    with locked(root):
        _recover(root)
        dest = safe(root, relative)
        before = dest.read_bytes() if dest.exists() else None
        if before is not None:
            old = json.loads(before)
            _validate_attempt(old)
            if (old['state'] != 'running' or value['state'] not in ('passed', 'failed')
                    or any(old.get(key) != value.get(key) for key in ('task', 'key', 'task_revision'))):
                raise ValueError('Attempt is terminal or its identity changed')
        else:
            if value['state'] != 'running':
                raise ValueError('New attempt must start running')
            task_path = safe(root, '.agentic-kit/tasks/' + value['task'] + '.json')
            if not task_path.is_file():
                raise ValueError('Attempt task not found')
            task = json.loads(task_path.read_text())
            _validate_task(task, value['task'])
            if task.get('status') == 'cancelled' or task['stage'] == 'done':
                raise ValueError('Terminal task cannot start checks')
            if task.get('revision', 0) != value['task_revision']:
                raise StaleState('Task revision changed; reload task before starting check')
        value = {**value, 'schema_version': 1, 'updated_at': now()}
        _commit(root, 'check.' + value['state'], value['task'],
                {'attempt_id': value['id'], 'key': value['key'], 'state': value['state']},
                actor, relative, before, value)


def attempts(root, task_id):
    ensure_readable(root)
    folder = safe(root, '.agentic-kit/attempts')
    results = []
    for path in sorted(folder.glob('*.json')):
        safe(root, str(path.relative_to(root)))
        data = json.loads(path.read_text())
        if data.get('schema_version') != 1:
            raise ValueError('Unsupported attempt schema')
        if data.get('task') == task_id:
            results.append(data)
    ensure_readable(root)
    return sorted(results, key=lambda r: r['updated_at'])
