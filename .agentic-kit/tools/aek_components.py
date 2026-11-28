#!/usr/bin/env python3
"""Explicit component ownership and reviewed, journaled environment updates.

No online discovery or automatic adoption. External adapters are reviewed argv
recipes, not a sandbox. Preview never executes them; apply verifies versions.
"""
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import datetime as dt
import tempfile
from uuid import uuid4

REGISTRY = '.agentic-kit/components.json'
HISTORY = '.agentic-kit/component-history'
SCHEMA = 1
ID = re.compile(r'^[a-z][a-z0-9-]{0,63}$')
HEX = re.compile(r'^[a-f0-9]{64}$')
MAX_LOG = 1024 * 1024


def helper(name):
    spec = importlib.util.spec_from_file_location('aek_components_' + name, Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode()


def digest(value):
    return hashlib.sha256(value).hexdigest()


def safe(root, relative):
    if not isinstance(relative, str) or not relative or '\\' in relative:
        raise ValueError('Invalid relative component path')
    parts = PurePosixPath(relative)
    if parts.is_absolute() or any(p in ('', '.', '..') for p in relative.split('/')):
        raise ValueError('Unsafe component path: ' + relative)
    current = Path(root)
    for part in parts.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError('Symlink in component path: ' + str(current))
    return current


def root_path(value):
    path = Path(value)
    if path.is_symlink() or not path.is_dir():
        raise ValueError('Component location must be an existing real directory')
    return path.resolve()


def read_json(value):
    return json.loads(Path(value).read_text(encoding='utf-8')) if isinstance(value, (str, Path)) else value


def atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    name = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
            name = Path(stream.name)
            stream.write(encoded(value))
        os.replace(name, path)
    finally:
        if name and name.exists():
            name.unlink()


def argv(value):
    if not isinstance(value, list) or not value or any(not isinstance(s, str) or not s or '\x00' in s for s in value):
        raise ValueError('Commands must be nonempty argv arrays')
    if not Path(value[0]).is_absolute():
        raise ValueError('Command executable must be an absolute path')
    if not Path(value[0]).is_file():
        raise ValueError('Command executable not found: ' + value[0])
    return value


def version(value):
    if not isinstance(value, str) or not value.strip() or value != value.strip() or len(value) > 160 or any(c in value for c in '\r\n'):
        raise ValueError('A nonempty exact version is required')
    if value.lower() in ('latest', 'main', 'master', '*', 'next'):
        raise ValueError('Floating versions are not accepted')
    return value


def snapshot(component):
    location = root_path(component['location'])
    result = {}
    for name in component.get('managed_paths', []):
        path = safe(location, name)
        if path.exists() and not path.is_file():
            raise ValueError('Managed component path must be a file')
        result[name] = digest(path.read_bytes()) if path.is_file() else None
    return result


def validate_component(item, target, observe=True):
    if not isinstance(item, dict) or not ID.fullmatch(item.get('id', '')) or item['id'] == 'kit':
        raise ValueError('Invalid component ID (kit is reserved)')
    if item.get('kind') not in ('tool', 'skill', 'shortcuts') or item.get('scope') not in ('project', 'global'):
        raise ValueError('Invalid component kind or scope')
    if not isinstance(item.get('source'), str) or not item['source'].strip():
        raise ValueError('Component source is required')
    version(item.get('installed_version'))
    if not isinstance(item.get('location'), str) or not Path(item['location']).is_absolute():
        raise ValueError('Component location must be an explicit absolute path')
    location = root_path(item['location'])
    if item['scope'] == 'project' and not location.is_relative_to(target):
        raise ValueError('Project component location escapes target')
    clean = {k: item[k] for k in ('id', 'kind', 'source', 'installed_version', 'scope')}
    clean['location'] = str(location)
    clean['adapter'] = item.get('adapter')
    if clean['kind'] == 'shortcuts':
        if clean['adapter'] != 'shortcuts':
            raise ValueError('Shortcuts require the shortcuts adapter')
        providers = item.get('providers')
        if not isinstance(providers, list) or not providers or any(p not in ('claude', 'codex', 'opencode', 'pi') for p in providers):
            raise ValueError('Explicit shortcut providers are required')
        clean['providers'] = sorted(set(providers))
    else:
        if clean['adapter'] != 'command':
            raise ValueError('External components require a command adapter')
        clean['version_command'] = argv(item.get('version_command'))
        allowed = env_names(item.get('env_allow', []))
        if allowed:
            clean['env_allow'] = allowed
        if item.get('verify_command'):
            clean['verify_command'] = argv(item['verify_command'])
        paths = item.get('managed_paths', [])
        if not isinstance(paths, list) or not paths or any(not isinstance(p, str) for p in paths):
            raise ValueError('Explicit managed_paths are required to detect local changes')
        clean['managed_paths'] = sorted(set(paths))
        if observe:
            clean['managed_hashes'] = snapshot(clean)
            if any(x is None for x in clean['managed_hashes'].values()):
                raise ValueError('Register only existing component files')
    return clean


def registry(target):
    path = safe(target, REGISTRY)
    if not path.exists():
        return {'schema_version': SCHEMA, 'components': {}}, None
    raw = path.read_bytes()
    data = json.loads(raw)
    if not isinstance(data, dict) or data.get('schema_version') != SCHEMA or not isinstance(data.get('components'), dict):
        raise ValueError('Unsupported component registry')
    for name, item in data['components'].items():
        clean = validate_component(item, target, observe=False)
        if name != clean['id']:
            raise ValueError('Registry ID mismatch')
        if item['adapter'] == 'command':
            hashes = item.get('managed_hashes')
            if not isinstance(hashes, dict) or set(hashes) != set(clean['managed_paths']) or any(not isinstance(v, str) or not HEX.fullmatch(v) for v in hashes.values()):
                raise ValueError('Invalid registered component checksums')
    return data, digest(raw)


def identify(plan):
    return {**plan, 'plan_id': digest(encoded(plan))[:24]}


def assert_plan(expected, plan):
    if not expected or expected != plan['plan_id']:
        raise ValueError('Missing or stale plan_id; review a fresh plan')


def env_names(value):
    if not isinstance(value, (list, tuple)) or any(not isinstance(x, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', x) for x in value):
        raise ValueError('env_allow must contain variable names, never assignments or values')
    return sorted(set(value))


def execute(args, cwd, timeout=300, env_allow=()):
    argv(args)
    result = helper('aek_execution').run(args, cwd, timeout=timeout, max_output=MAX_LOG,
                                         env_allow=env_names(env_allow))
    return {'argv': args, **result}


def checked(args, cwd, expected=None, env_allow=()):
    result = execute(args, cwd, env_allow=env_allow)
    if result['exit_code'] or result['truncated'] or result['timed_out'] or (expected is not None and result['output'].strip() != expected):
        raise ValueError('Component command failed/version mismatch: ' + json.dumps(result, ensure_ascii=False))
    return result



def owned_paths(component):
    root = Path(component['location'])
    if component['adapter'] == 'command':
        return {str(safe(root, p)) for p in component['managed_paths']}
    manifest, _ = helper('aek_shortcuts').read_manifest(root, component['scope'])
    return {str(safe(root, p)) for provider in component['providers']
            for p in manifest['providers'].get(provider, {}).get('files', {})}


def check_ownership(component, data, target):
    paths = owned_paths(component)
    for name, registered in data['components'].items():
        if name != component['id'] and paths.intersection(owned_paths(registered)):
            raise ValueError('Managed component paths overlap with ' + name)
    manifest = json.loads(safe(target, '.agentic-kit/install-manifest.json').read_text())
    kit_paths = {str(safe(target, p)) for field in ('managed_files', 'managed_skills')
                 for p in manifest.get(field, {})}
    if paths.intersection(kit_paths):
        raise ValueError('Component paths overlap with managed kit files')


def register(target, specification, apply=False, plan_id=None):
    target = root_path(target)
    if not safe(target, '.agentic-kit/install-manifest.json').is_file():
        raise ValueError('Install AEK before registering components')
    data, before = registry(target)
    clean = validate_component(read_json(specification), target)
    old = data['components'].get(clean['id'])
    if old is not None and old != clean:
        raise ValueError('Component already registered; refusing ownership replacement')
    if clean['adapter'] == 'shortcuts':
        ownership = safe(Path(clean['location']), helper('aek_shortcuts').MANIFEST)
        if not ownership.is_file():
            raise ValueError('Install managed shortcuts before registering their group')
        manifest, _ = helper('aek_shortcuts').read_manifest(Path(clean['location']), clean['scope'])
        for provider in clean['providers']:
            installed = manifest['providers'].get(provider)
            if not installed or installed['kit_version'] != clean['installed_version']:
                raise ValueError('Shortcut version/provider does not match ownership manifest')
    check_ownership(clean, data, target)
    plan = identify({'action': 'register', 'target': str(target), 'registry_sha256': before,
                     'component': clean, 'command_inputs': command_fingerprints([clean['version_command']]) if clean['adapter'] == 'command' else {}, 'scope_warning': 'Shared with other projects' if clean['scope'] == 'global' else None})
    if not apply:
        return {**plan, 'applied': False}
    assert_plan(plan_id, plan)
    with locks(target, [{'adapter': clean['adapter'], 'location': clean['location']}]):
        fresh = register(target, specification)
        fresh.pop('applied', None)
        assert_plan(plan_id, fresh)
        if clean['adapter'] == 'command':
            checked(clean['version_command'], clean['location'], clean['installed_version'], clean.get('env_allow', []))
        if registry(target)[1] != before or (clean['adapter'] == 'command' and snapshot(clean) != clean['managed_hashes']):
            raise ValueError('Registry or component changed during registration')
        data['components'][clean['id']] = clean
        atomic(safe(target, REGISTRY), data)
    return {**plan, 'applied': True}


def command_fingerprints(commands, explicit=None):
    hashes = {}
    for command in commands:
        for part in command:
            path = Path(part)
            if path.is_absolute() and path.is_file():
                hashes[str(path.resolve())] = digest(path.read_bytes())
    for name, expected in (explicit or {}).items():
        path = Path(name)
        if not path.is_absolute() or not isinstance(expected, str) or not HEX.fullmatch(expected):
            raise ValueError('Candidate inputs require absolute paths and SHA256')
        if not path.is_file() or digest(path.read_bytes()) != expected:
            raise ValueError('Candidate input checksum mismatch: ' + name)
        hashes[str(path.resolve())] = expected
    return hashes


def prepare(source, target, candidates=None, selected=None):
    source, target = root_path(source), root_path(target)
    helper('aek_install').validate_source(source)
    if source == target or source.is_relative_to(target) or target.is_relative_to(source):
        raise ValueError('Use a separate reviewed kit source outside the project')
    data, reg_hash = registry(target)
    choices = read_json(candidates) if candidates is not None else {}
    if not isinstance(choices, dict):
        raise ValueError('Candidates must be an object keyed by registered component ID')
    known = {'kit', *data['components']}
    if set(choices) - known:
        raise ValueError('Unregistered candidate component')
    selection = sorted(set(selected)) if selected else sorted(known)
    if set(selection) - known:
        raise ValueError('Unregistered selected component')
    selection.sort(key=lambda n: (n != 'kit', n != 'kit' and data['components'][n]['adapter'] == 'command', n))
    aek = helper('aek')
    source_hash = digest(encoded({str(p): digest(b) for p, b in aek.source_files(source)}))
    steps, conflicts, unchecked = [], [], []
    for name in selection:
        if name == 'kit':
            plan = aek.upgrade(source, target, show_diff=True)
            conflicts += [{'component': name, 'detail': x} for x in plan['conflicts']]
            if plan['operations']:
                steps.append({'id': 'kit', 'adapter': 'kit', 'scope': 'project', 'location': str(target),
                              'from_version': plan['from_version'], 'to_version': plan['to_version'],
                              'rollback_available': True, 'plan': plan})
            continue
        component = data['components'][name]
        if component['adapter'] == 'shortcuts':
            plan, _ = helper('aek_shortcuts').plan(component['scope'], Path(component['location']), component['providers'], source=source)
            conflicts += [{'component': name, 'detail': x} for x in plan['conflicts']]
            steps.append({'id': name, 'adapter': 'shortcuts', 'scope': component['scope'], 'location': component['location'],
                          'from_version': component['installed_version'], 'to_version': (source / 'VERSION').read_text().strip(),
                          'rollback_available': True, 'plan': plan, 'before': json.loads(json.dumps(component))})
            continue
        current = snapshot(component)
        if current != component['managed_hashes']:
            conflicts.append({'component': name, 'detail': 'Managed files modified since registration/update'})
        candidate = choices.get(name)
        if candidate is None:
            unchecked.append(name)
            continue
        if not isinstance(candidate, dict) or candidate.get('source') != component['source']:
            raise ValueError('Candidate must identify the registered source: ' + name)
        wanted = version(candidate.get('version'))
        if wanted == component['installed_version']:
            continue
        install = argv(candidate.get('update_command'))
        rollback = argv(candidate['rollback_command']) if candidate.get('rollback_command') else None
        commands = [install, component['version_command']]
        if component.get('verify_command'):
            commands.append(component['verify_command'])
        if rollback:
            commands.append(rollback)
        steps.append({'id': name, 'adapter': 'command', 'scope': component['scope'], 'location': component['location'],
                      'from_version': component['installed_version'], 'to_version': wanted,
                      'update_command': install, 'version_command': component['version_command'],
                      'verify_command': component.get('verify_command'), 'rollback_command': rollback,
                      'env_allow': env_names(candidate.get('env_allow', component.get('env_allow', []))),
                      'rollback_available': rollback is not None, 'before': json.loads(json.dumps(component)), 'observed_files': current,
                      'inputs': command_fingerprints(commands, candidate.get('inputs'))})
    plan = identify({'action': 'update', 'target': str(target), 'source': str(source), 'source_sha256': source_hash,
                     'source_identity': helper('aek_environment').identity(source, aek.source_files),
                     'registry_sha256': reg_hash, 'selected': selection, 'steps': steps, 'conflicts': conflicts,
                     'not_checked_online': unchecked, 'safe_to_apply': not conflicts,
                     'note': 'Candidates are reviewed pinned recipes. No online version lookup; shared scope affects other projects. External recipes may access the network.'})
    return plan, data


@contextlib.contextmanager
def locks(target, steps):
    paths = {safe(target, '.agentic-kit/components.lock')}
    paths.update(safe(Path(s['location']), '.aek-update.lock') for s in steps if s['adapter'] != 'kit')
    acquired = []
    operation = {'pid': os.getpid(), 'operation_id': uuid4().hex, 'created_at': dt.datetime.now(dt.timezone.utc).isoformat()}
    try:
        for path in sorted(paths):
            path.parent.mkdir(parents=True, exist_ok=True)
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'wb') as stream:
                stream.write(encoded(operation))
            acquired.append(path)
        yield
    except FileExistsError as exc:
        raise ValueError('Another update owns a lock; investigate interruption before removing it') from exc
    finally:
        for path in reversed(acquired):
            path.unlink(missing_ok=True)


def update(source, target, candidates=None, selected=None, apply=False, plan_id=None):
    plan, data = prepare(source, target, candidates, selected)
    if not apply:
        return {**plan, 'applied': False}
    assert_plan(plan_id, plan)
    if plan['conflicts']:
        raise ValueError('Component conflicts; no update applied')
    target, source = Path(plan['target']), Path(plan['source'])
    if not plan['steps']:
        return {**plan, 'applied': False, 'state': 'already_current'}
    run_id = uuid4().hex
    journal_path = safe(target, HISTORY + '/' + run_id + '/record.json')
    journal = {'schema_version': SCHEMA, 'id': run_id, 'state': 'prepared', 'plan': plan, 'steps': []}
    with locks(target, plan['steps']):
        # A lock is not authorization: recheck reviewed source, files and registry.
        fresh, _ = prepare(source, target, candidates, selected)
        assert_plan(plan_id, fresh)
        atomic(journal_path, journal)
        for step in plan['steps']:
            record = {'id': step['id'], 'state': 'started', 'step': step}
            journal['steps'].append(record)
            journal['state'] = 'applying'
            atomic(journal_path, journal)
            try:
                if step['adapter'] == 'kit':
                    record['mutation_started'] = True
                    atomic(journal_path, journal)
                    result = helper('aek').upgrade(source, target, apply=True, plan_id=step['plan']['plan_id'])
                    record['result'] = result
                elif step['adapter'] == 'shortcuts':
                    c = step['before']
                    record['mutation_started'] = True
                    atomic(journal_path, journal)
                    result = helper('aek_shortcuts').apply_plan(c['scope'], Path(c['location']), c['providers'], step['plan']['plan_id'], source=source)
                    record['result'] = result
                else:
                    checked(step['version_command'], step['location'], step['from_version'], step.get('env_allow', []))
                    record['mutation_started'] = True
                    atomic(journal_path, journal)
                    record['update_result'] = checked(step['update_command'], step['location'], env_allow=step.get('env_allow', []))
                    record['version_result'] = checked(step['version_command'], step['location'], step['to_version'], step.get('env_allow', []))
                    if step['verify_command']:
                        record['verify_result'] = checked(step['verify_command'], step['location'], env_allow=step.get('env_allow', []))
                if step['id'] != 'kit':
                    component = data['components'][step['id']]
                    component['installed_version'] = step['to_version']
                    if step['adapter'] == 'command':
                        component['managed_hashes'] = snapshot(component)
                        if any(v is None for v in component['managed_hashes'].values()):
                            raise ValueError('Updated component is missing managed files')
                        record['after_files'] = component['managed_hashes']
                    atomic(safe(target, REGISTRY), data)
                record['state'] = 'verified'
                atomic(journal_path, journal)
            except Exception as exc:
                record['state'] = 'failed'
                record['error'] = str(exc)
                if step['adapter'] == 'command':
                    record['after_files'] = snapshot(step['before'])
                journal['state'] = 'failed'
                atomic(journal_path, journal)
                return {**plan, 'applied': False, 'state': 'failed', 'run_id': run_id,
                        'failed_component': step['id'], 'error': str(exc), 'journal': str(journal_path),
                        'note': 'Earlier steps may be applied. Review recover; no automatic blanket rollback.'}
        journal['state'] = 'verified'
        atomic(journal_path, journal)
    return {**plan, 'applied': True, 'state': 'verified', 'run_id': run_id, 'journal': str(journal_path)}


def recovery_plan(source, target, run_id):
    target = root_path(target)
    if not isinstance(run_id, str) or not re.fullmatch(r'[0-9a-f]{32}', run_id):
        raise ValueError('Invalid component update run ID')
    path = safe(target, HISTORY + '/' + run_id + '/record.json')
    journal = json.loads(path.read_text())
    if journal.get('state') not in ('failed', 'verified', 'applying', 'recovering', 'recovery_failed'):
        raise ValueError('Run does not need recovery')
    _, registry_hash = registry(target)
    actions, conflicts = [], []
    for record in reversed(journal['steps']):
        if record['state'] == 'rolled_back' or not record.get('mutation_started'):
            continue
        step = record['step']
        action = {'id': step['id'], 'adapter': step['adapter'], 'scope': step['scope'], 'location': step['location']}
        if step['adapter'] == 'command':
            if not step.get('rollback_command'):
                conflicts.append({'component': step['id'], 'detail': 'No rollback recipe; manual recovery required'})
                continue
            observed = snapshot(step['before'])
            if record.get('after_files') is None or observed != record['after_files']:
                conflicts.append({'component': step['id'], 'detail': 'Files changed or interrupted before snapshot; manual recovery required'})
                continue
            action.update({'rollback_command': step['rollback_command'], 'observed_files': observed,
                           'inputs': command_fingerprints([step['rollback_command'], step['version_command']])})
        else:
            backup = record.get('result', {}).get('backup_id')
            if not backup:
                if record.get('result', {}).get('applied') is False:
                    continue
                conflicts.append({'component': step['id'], 'detail': 'Missing backup ID; inspect component journal manually'})
                continue
            if step['adapter'] == 'kit':
                child = helper('aek').rollback(target, backup)
            else:
                child = helper('aek_shortcuts').prepare_rollback(Path(step['location']), backup)
            conflicts += [{'component': step['id'], 'detail': c} for c in child.get('conflicts', [])]
            action.update({'backup_id': backup, 'plan': child})
        actions.append(action)
    return identify({'action': 'recover', 'target': str(target), 'run_id': run_id, 'journal_sha256': digest(path.read_bytes()),
                     'registry_sha256': registry_hash, 'steps': actions, 'conflicts': conflicts, 'safe_to_apply': not conflicts}), journal, path


def recover(source, target, run_id, apply=False, plan_id=None):
    plan, journal, path = recovery_plan(source, target, run_id)
    if not apply:
        return {**plan, 'applied': False}
    assert_plan(plan_id, plan)
    if plan['conflicts']:
        raise ValueError('Recovery conflicts require manual resolution')
    target = Path(plan['target'])
    data, _ = registry(target)
    records = {r['id']: r for r in journal['steps']}
    with locks(target, plan['steps']):
        fresh, _, _ = recovery_plan(source, target, run_id)
        assert_plan(plan_id, fresh)
        journal['state'] = 'recovering'
        atomic(path, journal)
        for action in plan['steps']:
            record = records[action['id']]
            step = record['step']
            try:
                if action['adapter'] == 'command':
                    checked(action['rollback_command'], action['location'], env_allow=step.get('env_allow', []))
                    checked(step['version_command'], action['location'], step['from_version'], step.get('env_allow', []))
                    if step.get('verify_command'):
                        checked(step['verify_command'], action['location'], env_allow=step.get('env_allow', []))
                elif action['adapter'] == 'kit':
                    helper('aek').rollback(target, action['backup_id'], apply=True, plan_id=action['plan']['plan_id'])
                else:
                    helper('aek_shortcuts').perform_rollback(Path(action['location']), action['backup_id'], action['plan']['plan_id'])
                if action['id'] != 'kit':
                    data['components'][action['id']] = step['before']
                    if action['adapter'] == 'command':
                        data['components'][action['id']]['managed_hashes'] = snapshot(step['before'])
                    atomic(safe(target, REGISTRY), data)
                record['state'] = 'rolled_back'
                atomic(path, journal)
            except Exception as exc:
                journal['state'] = 'recovery_failed'
                journal['error'] = str(exc)
                atomic(path, journal)
                return {**plan, 'applied': False, 'state': 'recovery_failed', 'error': str(exc)}
        journal['state'] = 'rolled_back'
        atomic(path, journal)
    return {**plan, 'applied': True, 'state': 'rolled_back'}


def pending_operations(target, data):
    pending, lock_records = [], []
    locations = {(str(target), HISTORY, ('verified', 'rolled_back')),
                 (str(target), '.agentic-kit/upgrade-history', ('applied', 'rolled_back')),
                 (str(target), '.aek/shortcuts-history', ('applied', 'rolled_back'))}
    lock_paths = {(str(target), '.aek-install.lock'), (str(target), '.agentic-kit/components.lock'),
                  (str(target), '.aek/shortcuts.lock'), (str(target), '.aek-update.lock')}
    for item in data['components'].values():
        lock_paths.add((item['location'], '.aek-update.lock'))
        if item['adapter'] == 'shortcuts':
            locations.add((item['location'], '.aek/shortcuts-history', ('applied', 'rolled_back')))
            lock_paths.add((item['location'], '.aek/shortcuts.lock'))
    for root, relative, terminal in sorted(locations):
        history = safe(Path(root), relative)
        if history.is_dir():
            for run in sorted(history.iterdir()):
                if run.is_symlink():
                    raise ValueError('Unsafe update history')
                record = safe(run, 'record.json')
                if record.is_file():
                    content = json.loads(record.read_text())
                    if content.get('state') not in terminal:
                        pending.append({'run_id': run.name, 'state': content.get('state'),
                                        'history': relative, 'location': root})
    for root, relative in sorted(lock_paths):
        path = safe(Path(root), relative)
        if path.exists():
            # A lock can be active or abandoned; never infer safe removal from PID alone.
            metadata = None
            try:
                raw = json.loads(path.read_text())
                if isinstance(raw, dict):
                    metadata = {k: raw[k] for k in ('pid', 'operation_id', 'created_at') if k in raw}
            except (ValueError, OSError):
                pass
            lock_records.append({'path': str(path), 'owner': metadata, 'status': 'investigate_owner'})
    return pending, lock_records


def doctor(target):
    target = root_path(target)
    data, _ = registry(target)
    env = helper('aek_environment')
    items = []
    for name, component in data['components'].items():
        item = {'id': name, 'kind': component['kind'], 'scope': component['scope'],
                'installed_version': component['installed_version'], 'version_probe': 'not_executed'}
        if component['adapter'] == 'command':
            item['files_match'] = snapshot(component) == component['managed_hashes']
        else:
            checks = env.shortcut_inventory(Path(component['location']), component['scope'], component['providers'])
            item.update(ownership_present=checks['manifest_present'], files_match=checks['files_ready'],
                        problems=checks['problems'])
        items.append(item)
    project_commands = None
    if safe(target, helper('aek_shortcuts').MANIFEST).is_file():
        project_commands = env.shortcut_inventory(target, 'project')
    pending, lock_records = pending_operations(target, data)
    installed = safe(target, '.agentic-kit/install-manifest.json').is_file()
    kit_changed, kit_missing_required, identity = [], [], None
    if installed:
        manifest, _ = helper('aek_upgrade').read_manifest(target)
        identity = manifest.get('source_identity')
        # The manifest describes the delivered subset; check the runtime contract too.
        required = ('.agentic-kit/' + name for name in helper('aek').FILES
                    if name.startswith('tools/'))
        kit_missing_required = sorted(name for name in required
                                      if not safe(target, name).is_file())
        for field in ('managed_files', 'managed_skills'):
            for name, expected in manifest.get(field, {}).items():
                if name in ('AGENTS.md', '.agentic-kit/ADOPTION.md'):
                    continue
                file = safe(target, name)
                if not file.is_file() or digest(file.read_bytes()) != expected:
                    kit_changed.append(name)
    files_ready = installed and not kit_changed and not kit_missing_required and all(i['files_match'] for i in items)
    files_ready = files_ready and (project_commands is None or project_commands['files_ready'])
    environment = env.check(target) if installed and safe(target, env.LOCK).is_file() else {'lock_present': False, 'matches': None}
    passed = files_ready and not pending and not lock_records and environment.get('passed', True)
    return {'registry_schema': SCHEMA, 'kit_installed': installed, 'kit_changed': kit_changed,
            'kit_missing_required': kit_missing_required,
            'source_identity': identity, 'components': items, 'project_commands': project_commands,
            'pending_recovery': pending, 'operation_locks': lock_records, 'environment_lock': environment,
            'files_ready': bool(files_ready), 'passed': bool(passed), 'provider_visibility': 'unverified',
            'online_versions_checked': False,
            'next_action': 'inspect_pending_operation' if pending or lock_records else
                           'repair_managed_files' if not files_ready else
                           'review_environment_plan' if environment.get('passed') is False else
                           'verify_provider_visibility',
            'note': 'Read-only file checks; no executables or network requests run. Files ready does not prove provider visibility.'}
