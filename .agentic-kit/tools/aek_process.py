"""Small pinned process graphs. Pure task mutations; never execute commands.

The caller owns persistence/CAS and validates evidence with the same policy as
AEK workflow gates. Local evidence is not an authenticated human approval.
"""
import copy
import datetime as dt
import hashlib
import json
from pathlib import Path
import re

SCHEMA = 1
PHASES = ('discovery', 'design', 'planning', 'implementation', 'verification', 'release', 'done')
STATES = ('pending', 'running', 'passed', 'failed', 'blocked')
IDENTIFIER = re.compile(r'^[a-z][a-z0-9_-]{0,63}$')
EVIDENCE = re.compile(r'^[A-Za-z][A-Za-z0-9_-]{0,63}$')


def _workflow():
    # Also works when the standalone helper is loaded with importlib by callers.
    import importlib.util
    spec = importlib.util.spec_from_file_location('aek_boundary_workflow', Path(__file__).with_name('aek_workflow.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _boundary(task, stage=None):
    _workflow().check_boundary(task, PHASES, stage)


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()


def fingerprint(value):
    return hashlib.sha256(encoded(value)).hexdigest()


def _text(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError('Nonempty ' + label + ' required')


def _names(value, label, pattern=IDENTIFIER):
    if not isinstance(value, list) or any(not isinstance(x, str) or not pattern.fullmatch(x) for x in value):
        raise ValueError('Invalid ' + label)
    if len(value) != len(set(value)):
        raise ValueError('Duplicate ' + label)


def validate_definition(value):
    if not isinstance(value, dict) or type(value.get('schema_version')) is not int or value['schema_version'] != SCHEMA:
        raise ValueError('Unsupported process definition schema')
    if set(value) != {'schema_version', 'id', 'title', 'steps'}:
        raise ValueError('Unknown or missing process definition fields')
    if not isinstance(value['id'], str) or not IDENTIFIER.fullmatch(value['id']):
        raise ValueError('Invalid process ID')
    _text(value['title'], 'process title')
    steps = value['steps']
    if not isinstance(steps, list) or not 1 <= len(steps) <= 32:
        raise ValueError('Process needs 1..32 steps')
    seen = {}
    for step in steps:
        required = {'id', 'title', 'stage', 'depends_on', 'inputs', 'evidence', 'gate',
                    'max_attempts', 'retryable', 'stop_conditions'}
        if not isinstance(step, dict) or set(step) != required:
            raise ValueError('Unknown or missing process step fields')
        name = step['id']
        if not isinstance(name, str) or not IDENTIFIER.fullmatch(name) or name in seen:
            raise ValueError('Invalid or duplicate process step ID')
        seen[name] = step
        _text(step['title'], 'step title')
        if step['stage'] not in PHASES[:-1] or step['gate'] not in PHASES[1:]:
            raise ValueError('Invalid process phase/gate')
        if PHASES.index(step['gate']) != PHASES.index(step['stage']) + 1:
            raise ValueError('Process gate must be the next workflow phase')
        for field in ('depends_on', 'inputs', 'evidence'):
            _names(step[field], field, IDENTIFIER if field == 'depends_on' else EVIDENCE)
        if type(step['max_attempts']) is not int or not 1 <= step['max_attempts'] <= 10:
            raise ValueError('max_attempts must be between 1 and 10')
        if type(step['retryable']) is not bool or not step['retryable'] and step['max_attempts'] != 1:
            raise ValueError('Non-retryable steps must allow exactly one attempt')
        if not isinstance(step['stop_conditions'], list) or not step['stop_conditions']:
            raise ValueError('Step stop conditions required')
        for condition in step['stop_conditions']:
            _text(condition, 'stop condition')
    for step in steps:
        if set(step['depends_on']) - seen.keys() or step['id'] in step['depends_on']:
            raise ValueError('Unknown or self-referencing dependency')
        if any(PHASES.index(seen[name]['stage']) > PHASES.index(step['stage']) for name in step['depends_on']):
            raise ValueError('Dependency is in a later workflow phase')
    done = set()
    while len(done) < len(steps):
        ready = {s['id'] for s in steps if s['id'] not in done and set(s['depends_on']) <= done}
        if not ready:
            raise ValueError('Cycle in process dependencies')
        done.update(ready)
    return value


def load_definition(source_root, name):
    if name not in ('change', 'fix', 'update'):
        raise ValueError('Unknown built-in process')
    root = Path(source_root)
    folder, path = root / 'workflows', root / 'workflows' / (name + '.json')
    if root.is_symlink() or folder.is_symlink() or path.is_symlink():
        raise ValueError('Unsafe process definition path')
    result = validate_definition(json.loads(path.read_text(encoding='utf-8')))
    if result['id'] != name:
        raise ValueError('Process filename/ID mismatch')
    return result


def _state(task):
    process = task.get('process')
    if not isinstance(process, dict) or type(process.get('schema_version')) is not int or process['schema_version'] != SCHEMA:
        raise ValueError('Unsupported or missing task process schema')
    definition = validate_definition(process.get('definition'))
    if process.get('definition_sha256') != fingerprint(definition) or process.get('id') != definition['id']:
        raise ValueError('Pinned process definition changed')
    states = process.get('steps')
    if not isinstance(states, dict) or set(states) != {step['id'] for step in definition['steps']}:
        raise ValueError('Invalid process step state')
    for step in definition['steps']:
        state = states[step['id']]
        if not isinstance(state, dict) or state.get('state') not in STATES:
            raise ValueError('Invalid process step status')
        attempts = state.get('attempts')
        if type(attempts) is not int or not 0 <= attempts <= step['max_attempts']:
            raise ValueError('Invalid process attempt count')
        if state['state'] != 'pending' and attempts == 0:
            raise ValueError('Started step needs an attempt')
        if not isinstance(state.get('results'), dict):
            raise ValueError('Invalid process result references')
    if not isinstance(process.get('history'), list):
        raise ValueError('Invalid process history')
    return process, definition


def _active(task):
    if task.get('status', 'active') != 'active' or task.get('stage') == 'done':
        raise ValueError('Task must be active and nonterminal')
    if task.get('context', {}).get('blockers'):
        raise ValueError('Resolve context blockers before process work')
    _boundary(task)


def _step(task, name):
    process, definition = _state(task)
    for step in definition['steps']:
        if step['id'] == name:
            return process, step, process['steps'][name]
    raise ValueError('Unknown process step')


def _event(process, kind, step=None, **fields):
    event = {'event': kind, 'at': dt.datetime.now(dt.timezone.utc).isoformat(),
             'process': process['id'], **fields}
    if step is not None:
        event['step'] = step
    process['history'].append(event)
    return event


def start(task, definition):
    _active(task)
    if 'process' in task:
        raise ValueError('Task already has a pinned process; create a follow-up task')
    validate_definition(definition)
    if task.get('stage') != definition['steps'][0]['stage']:
        raise ValueError('Start a process at its first workflow phase')
    task['process'] = {'schema_version': SCHEMA, 'id': definition['id'],
                       'definition': copy.deepcopy(definition), 'definition_sha256': fingerprint(definition),
                       'steps': {s['id']: {'state': 'pending', 'attempts': 0, 'results': {}}
                                 for s in definition['steps']}, 'history': []}
    return _event(task['process'], 'process.started', definition_sha256=fingerprint(definition))


def _keys(step, gate_keys):
    return sorted(set(step['evidence']) | set(gate_keys(step['gate'])))


def _observations(task, evidence_check, gate_keys):
    process, definition = _state(task)
    valid, pending = {}, list(definition['steps'])
    while pending:
        for step in pending[:]:
            if not all(dep in valid for dep in step['depends_on']):
                continue
            state = process['steps'][step['id']]
            keys = _keys(step, gate_keys)
            references = state['results']
            valid[step['id']] = (state['state'] == 'passed' and set(references) == set(keys)
                and all(task.get('evidence', {}).get(key) == references[key]
                        and evidence_check(key, references[key]) for key in keys)
                and all(valid[dep] for dep in step['depends_on']))
            pending.remove(step)
    return valid


def inspect(task, evidence_check, gate_keys, current_definition=None):
    process, definition = _state(task)
    valid = _observations(task, evidence_check, gate_keys)
    items, ready = [], []
    context_blockers = task.get('context', {}).get('blockers', [])
    boundary = _workflow().boundary_status(task, PHASES)
    active = task.get('status', 'active') == 'active' and task.get('stage') != 'done' and not context_blockers
    active = active and boundary['configured'] and boundary['passed']
    for step in definition['steps']:
        state = process['steps'][step['id']]
        beyond_boundary = boundary['configured'] and PHASES.index(step['stage']) > PHASES.index(boundary['stop_after'])
        blocked_by = [name for name in step['depends_on'] if not valid[name]]
        inputs = [key for key in step['inputs'] if not evidence_check(key, task.get('evidence', {}).get(key))]
        missing = [key for key in _keys(step, gate_keys) if not evidence_check(key, task.get('evidence', {}).get(key))]
        if active and not beyond_boundary and task.get('stage') == step['stage'] and state['state'] == 'pending' and not blocked_by and not inputs:
            ready.append(step['id'])
        items.append({'id': step['id'], 'title': step['title'], 'stage': step['stage'], **copy.deepcopy(state),
                      'max_attempts': step['max_attempts'], 'retryable': step['retryable'],
                      'outside_execution_boundary': beyond_boundary, 'blocked_by': blocked_by, 'missing_inputs': inputs, 'missing_evidence': missing,
                      'stale': state['state'] == 'passed' and not valid[step['id']],
                      'stop_conditions': step['stop_conditions']})
    complete = all(valid.values())
    blockers = [s['id'] for s in items if s['state'] in ('failed', 'blocked') or s['stale']]
    next_action = ('resolve_task_blocker' if context_blockers else 'complete' if complete else 'resolve_task_blocker' if not active else
                   'review_process_blocker' if blockers else 'start_step' if ready else
                   'finish_running_step' if any(s['state'] == 'running' for s in items) else 'advance_task_phase')
    if not boundary['historical_terminal']:
        if not boundary['passed']:
            next_action = boundary['next_action']
        elif active and boundary.get('reached') and all(valid[s['id']] for s in definition['steps']
                if PHASES.index(s['stage']) <= PHASES.index(boundary['stop_after'])):
            next_action = 'execution_boundary_reached'
    return {'execution_boundary': boundary, 'id': process['id'], 'definition_sha256': process['definition_sha256'],
            'definition_changed': current_definition is not None and fingerprint(validate_definition(current_definition)) != process['definition_sha256'],
            'complete': complete, 'ready': ready, 'blockers': blockers, 'steps': items,
            'next_action': next_action, 'read_only': True}


def _ready(task, step, evidence_check, gate_keys):
    _active(task)
    _boundary(task, step['stage'])
    if task.get('stage') != step['stage']:
        raise ValueError('Task phase does not match process step; use the ordinary workflow gates')
    valid = _observations(task, evidence_check, gate_keys)
    if any(not valid[name] for name in step['depends_on']):
        raise ValueError('Process dependencies are incomplete or stale')
    if any(not evidence_check(key, task.get('evidence', {}).get(key)) for key in step['inputs']):
        raise ValueError('Process inputs missing or stale')


def start_step(task, step_id, evidence_check, gate_keys):
    process, step, state = _step(task, step_id)
    _ready(task, step, evidence_check, gate_keys)
    if state['state'] != 'pending' or state['attempts'] >= step['max_attempts']:
        raise ValueError('Step is not pending or attempt budget exhausted')
    state.update(state='running', attempts=state['attempts'] + 1, results={})
    state.pop('reason', None)
    return _event(process, 'process.step.started', step_id, attempt=state['attempts'])


def finish_step(task, step_id, outcome, reason, evidence_check, gate_keys):
    process, step, state = _step(task, step_id)
    _active(task)
    _boundary(task, step['stage'])
    if state['state'] != 'running' or outcome not in ('passed', 'failed', 'blocked'):
        raise ValueError('Only a running step can finish with passed, failed or blocked')
    if reason is not None and not isinstance(reason, str):
        raise ValueError('Step reason must be text')
    if outcome == 'passed':
        _ready(task, step, evidence_check, gate_keys)
    if outcome != 'passed':
        _text(reason, 'failure/blocker reason')
    keys = _keys(step, gate_keys)
    if outcome == 'passed':
        missing = [key for key in keys if not evidence_check(key, task.get('evidence', {}).get(key))]
        if missing:
            raise ValueError('Missing, failed or stale process evidence: ' + ', '.join(missing))
    state.update(state=outcome, results={key: copy.deepcopy(task['evidence'][key]) for key in keys} if outcome == 'passed' else {})
    if outcome != 'passed':
        state['reason'] = reason.strip()
    return _event(process, 'process.step.' + outcome, step_id, attempt=state['attempts'],
                  reason=reason.strip() if reason else None, evidence=keys if outcome == 'passed' else [])


def retry_step(task, step_id, reason):
    _active(task)
    _text(reason, 'retry reason')
    process, step, state = _step(task, step_id)
    _boundary(task, step['stage'])
    if state['state'] != 'failed' or not step['retryable'] or state['attempts'] >= step['max_attempts']:
        raise ValueError('Retry forbidden or attempt budget exhausted; resolve explicitly or create a follow-up task')
    state.update(state='pending', results={}, reason=reason.strip())
    return _event(process, 'process.step.retry_requested', step_id, reason=reason.strip())


def resume_step(task, step_id, reason, evidence_check, gate_keys):
    process, step, state = _step(task, step_id)
    _ready(task, step, evidence_check, gate_keys)
    _text(reason, 'blocker resolution reason')
    if state['state'] != 'blocked':
        raise ValueError('Only a blocked step can resume; failed steps need bounded retry')
    state.update(state='running', reason=reason.strip())
    return _event(process, 'process.step.resumed', step_id, attempt=state['attempts'], reason=reason.strip())


def reset_for_rework(task, reason, gate_keys):
    """Start an explicit new process cycle after the ordinary rework transition.

    Attempt budgets reset only at this explicit scope-rework boundary. Previous
    results and attempts remain in history; no execution or approval is inferred.
    """
    _text(reason, 'rework reason')
    if task.get('status') != 'rework' or task.get('stage') not in PHASES[:4]:
        raise ValueError('Process reset requires an ordinary task rework transition')
    _boundary(task)
    process, definition = _state(task)
    selected = [step for step in definition['steps']
                if PHASES.index(step['stage']) >= PHASES.index(task['stage'])]
    previous = {s['id']: copy.deepcopy(process['steps'][s['id']]) for s in selected}
    keys = set(key for step in selected for key in _keys(step, gate_keys))
    invalidated = {key: task['evidence'].pop(key) for key in sorted(keys)
                   if key in task.get('evidence', {})}
    for step in selected:
        process['steps'][step['id']] = {'state': 'pending', 'attempts': 0, 'results': {}}
    return _event(process, 'process.rework', reason=reason.strip(), return_to=task['stage'],
                  previous_steps=previous, invalidated_evidence=invalidated)


def refresh_step(task, step_id, reason, evidence_check, gate_keys):
    """Revalidate a passed checkpoint after rerunning its executable checks.

    This does not repeat work or reset its budget. Changed documentary scope or
    approval references require explicit rework; fresh executed test reports can
    replace old runs only after the caller's normal current-code validation.
    """
    _active(task)
    _text(reason, 'refresh reason')
    process, step, state = _step(task, step_id)
    _boundary(task, step['stage'])
    if state['state'] != 'passed':
        raise ValueError('Only a passed step can refresh proof')
    if task.get('stage') not in PHASES or PHASES.index(step['stage']) > PHASES.index(task['stage']):
        raise ValueError('Cannot refresh a future process phase')
    valid = _observations(task, evidence_check, gate_keys)
    if any(not valid[name] for name in step['depends_on']):
        raise ValueError('Process dependencies are incomplete or stale')
    if any(not evidence_check(key, task.get('evidence', {}).get(key)) for key in step['inputs']):
        raise ValueError('Process inputs missing or stale')
    keys = _keys(step, gate_keys)
    missing = [key for key in keys if not evidence_check(key, task.get('evidence', {}).get(key))]
    if missing:
        raise ValueError('Missing, failed or stale process evidence: ' + ', '.join(missing))
    previous = copy.deepcopy(state['results'])
    current = {key: copy.deepcopy(task['evidence'][key]) for key in keys}
    if set(previous) != set(current):
        raise ValueError('Process requirements changed; explicit rework required')
    for key in keys:
        if previous[key] != current[key] and not (
                key in ('tests', 'runtime_smoke') and isinstance(current[key], dict)
                and current[key].get('source') == 'executed'):
            raise ValueError('Documentary proof changed; explicit rework required: ' + key)
    state['results'] = current
    return _event(process, 'process.step.proof_refreshed', step_id, reason=reason.strip(),
                  attempt=state['attempts'], previous_results=previous, evidence=keys)
