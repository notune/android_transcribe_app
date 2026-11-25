"""Read-only environment inventory and explicitly reviewed local lock snapshots.

The lock records installed bytes and declared versions, not remote availability,
provider visibility, package-manager state, or authenticated provenance.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess

LOCK = '.agentic-kit/environment.lock.json'
SCHEMA = 1


def helper(name):
    spec = importlib.util.spec_from_file_location('aek_environment_' + name, Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def encoded(value):
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, indent=2) + '\n').encode()


def digest(value):
    return hashlib.sha256(value).hexdigest()


def identity(source, source_files=None):
    """Hash actual shipped bytes; claim an exact Git commit only for a clean root."""
    source = helper('aek_install').validate_source(source)
    files = (source_files or helper('aek').source_files)(source)
    files = list(files)
    hashes = {str(p): digest(content) for p, content in files}
    result = {'schema_version': SCHEMA, 'version': (source / 'VERSION').read_text().strip(),
              'content_sha256': digest(encoded(hashes)), 'file_count': len(hashes),
              'git_commit': None, 'git_tree': None, 'source_dirty': None}
    def git(*args):
        p = subprocess.run(['git', '-C', str(source), *args], capture_output=True,
                           text=True, timeout=15)
        return p.stdout.strip() if p.returncode == 0 else None
    try:
        top = git('rev-parse', '--show-toplevel')
        if top is None or Path(top).resolve() != source:
            return result
        status = git('status', '--porcelain', '--untracked-files=all')
        if status is None:
            return result
        result['source_dirty'] = bool(status)
        if not status:
            commit, tree = git('rev-parse', 'HEAD'), git('rev-parse', 'HEAD^{tree}')
            if commit and tree and re.fullmatch(r'[0-9a-f]{40,64}', commit) and re.fullmatch(r'[0-9a-f]{40,64}', tree):
                # Git status omits ignored files and can hide assume-unchanged
                # paths. Only claim HEAD when every shipped byte has that blob.
                listing = git('ls-tree', '-r', '-z', commit)
                if listing is None:
                    return result
                blobs = {}
                for entry in listing.split('\0'):
                    if entry:
                        metadata, name = entry.split('\t', 1)
                        mode, kind, object_id = metadata.split()
                        if kind == 'blob' and mode in ('100644', '100755'):
                            blobs[name] = object_id
                algorithm = 'sha1' if len(commit) == 40 else 'sha256'
                exact = all(blobs.get(str(path)) == hashlib.new(
                    algorithm, b'blob ' + str(len(content)).encode() + b'\0' + content).hexdigest()
                            for path, content in files)
                if exact:
                    result.update(git_commit=commit, git_tree=tree)
                else:
                    result['source_dirty'] = True
    except (OSError, subprocess.TimeoutExpired):
        pass
    return result


def validate_identity(value):
    if not isinstance(value, dict) or value.get('schema_version') != SCHEMA:
        raise ValueError('Unsupported source identity schema')
    if not isinstance(value.get('content_sha256'), str) or not re.fullmatch(r'[0-9a-f]{64}', value['content_sha256']):
        raise ValueError('Invalid source content identity')
    if not isinstance(value.get('version'), str) or not value['version']:
        raise ValueError('Invalid source version')
    for name in ('git_commit', 'git_tree'):
        if value.get(name) is not None and (not isinstance(value[name], str) or not re.fullmatch(r'[0-9a-f]{40,64}', value[name])):
            raise ValueError('Invalid source Git identity')
    if value.get('source_dirty') not in (None, True, False):
        raise ValueError('Invalid source dirty state')
    if value.get('git_commit') and (value.get('source_dirty') is not False or not value.get('git_tree')):
        raise ValueError('Exact Git identity requires a clean source')


def files(root, expected):
    c = helper('aek_components')
    observed = {}
    for name, wanted in sorted(expected.items()):
        p = c.safe(root, name)
        if p.exists() and not p.is_file():
            raise ValueError('Managed path must be a file: ' + name)
        observed[name] = {'expected': wanted, 'actual': digest(p.read_bytes()) if p.is_file() else None}
    return observed


def shortcut_inventory(root, scope, providers=None):
    c, s = helper('aek_components'), helper('aek_shortcuts')
    path = c.safe(root, s.MANIFEST)
    if not path.is_file():
        return {'scope': scope, 'location': str(root), 'manifest_present': False,
                'manifest_sha256': None, 'providers': {}, 'files_ready': False,
                'problems': ['missing_shortcut_manifest']}
    manifest, raw = s.read_manifest(root, scope)
    names = sorted(providers if providers is not None else manifest['providers'])
    groups, problems = {}, []
    for name in names:
        entry = manifest['providers'].get(name)
        if entry is None:
            problems.append('missing_provider:' + name)
            continue
        observed = files(root, entry['files'])
        changed = [p for p, h in observed.items() if h['actual'] != h['expected']]
        problems += ['shortcut_changed:' + p for p in changed]
        groups[name] = {'kit_version': entry['kit_version'], 'files': observed}
    return {'scope': scope, 'location': str(root), 'manifest_present': True,
            'manifest_sha256': digest(path.read_bytes()), 'providers': groups,
            'files_ready': not problems, 'problems': problems}


def snapshot(target):
    c = helper('aek_components')
    target = c.root_path(target)
    manifest, raw = helper('aek_upgrade').read_manifest(target)
    data, registry_hash = c.registry(target)
    observed = files(target, {k: v for field in ('managed_files', 'managed_skills')
                             for k, v in manifest.get(field, {}).items()
                             if k not in ('AGENTS.md', '.agentic-kit/ADOPTION.md')})
    problems = ['kit_changed:' + p for p, h in observed.items() if h['expected'] != h['actual']]
    components, commands = {}, {}
    for name, item in sorted(data['components'].items()):
        record = {k: item[k] for k in ('kind', 'scope', 'source', 'installed_version', 'location', 'adapter')}
        if item['adapter'] == 'command':
            record['files'] = files(Path(item['location']), item['managed_hashes'])
            record['env_allow'] = item.get('env_allow', [])
            record['version_probe'] = 'not_executed'
            problems += ['component_changed:' + name + ':' + p for p, h in record['files'].items() if h['actual'] != h['expected']]
        else:
            record['providers'] = item['providers']
            inventory = shortcut_inventory(Path(item['location']), item['scope'], item['providers'])
            commands[name] = inventory
            problems += [name + ':' + p for p in inventory['problems']]
        components[name] = record
    # Owned project commands remain checked even before their explicit registration.
    if c.safe(target, helper('aek_shortcuts').MANIFEST).is_file():
        own = shortcut_inventory(target, 'project')
        commands['project-owned'] = own
        problems += ['project-owned:' + p for p in own['problems']]
    profiles = {}
    for name in sorted(manifest.get('active_profiles', [])):
        path = c.safe(target, '.agentic-kit/profiles/' + name + '/manifest.json')
        if not path.is_file():
            problems.append('profile_manifest_missing:' + name)
            continue
        metadata = json.loads(path.read_text())
        profiles[name] = {'version': metadata.get('version'), 'manifest_sha256': digest(path.read_bytes()),
                          'source': metadata.get('source', metadata.get('upstream'))}
    result = {'schema_version': SCHEMA,
              'profiles': profiles,
              'kit': {'version': manifest['kit_version'], 'source_identity': manifest.get('source_identity'),
                      'provenance': 'recorded' if manifest.get('source_identity') else 'legacy_unknown',
                      'active_profiles': sorted(manifest.get('active_profiles', [])),
                      'manifest_sha256': digest(raw), 'files': observed},
              'registry_sha256': registry_hash, 'components': components, 'commands': commands,
              'files_ready': not problems, 'problems': sorted(set(problems)),
              'provider_visibility': 'unverified', 'online_versions_checked': False}
    result['content_sha256'] = digest(encoded(result))
    return result


def read_lock(target):
    path = helper('aek_components').safe(target, LOCK)
    if not path.exists():
        return None, None
    raw = path.read_bytes()
    value = json.loads(raw)
    if not isinstance(value, dict) or value.get('schema_version') != SCHEMA or not isinstance(value.get('snapshot'), dict):
        raise ValueError('Unsupported environment lock schema')
    stored = value['snapshot']
    if stored.get('schema_version') != SCHEMA or stored.get('content_sha256') != digest(encoded({k: v for k, v in stored.items() if k != 'content_sha256'})):
        raise ValueError('Invalid environment lock snapshot')
    return value, digest(raw)


def plan(target):
    return _plan(target)


def _plan(target, held_locks=None):
    c = helper('aek_components')
    target = c.root_path(target)
    previous, before = read_lock(target)
    current = snapshot(target)
    registered, _ = c.registry(target)
    pending, locks = c.pending_operations(target, registered)
    # Only apply's private recheck can disregard locks it just acquired. A path
    # match alone is insufficient if its owner/content changed unexpectedly.
    held_locks = held_locks or {}
    locks = [item for item in locks if item['path'] not in held_locks or
             Path(item['path']).read_bytes() != held_locks[item['path']]]
    conflicts = current['problems'] + ['pending_operation:' + p['history'] + ':' + p['run_id'] for p in pending]
    conflicts += ['operation_lock:' + item['path'] for item in locks]
    return c.identify({'action': 'environment-lock', 'target': str(target), 'before_sha256': before,
                       'snapshot': current, 'changed': previous is None or previous['snapshot'] != current,
                       'safe_to_apply': current['files_ready'] and not pending and not locks, 'conflicts': conflicts,
                       'pending_recovery': pending, 'operation_locks': locks,
                       'note': 'Pins observed local files and declared versions. No packages are installed and no remote versions are inferred.'})


def apply(target, plan_id):
    c, install = helper('aek_components'), helper('aek_install')
    target = c.root_path(target)
    reviewed = plan(target)
    c.assert_plan(plan_id, reviewed)
    if not reviewed['safe_to_apply']:
        raise ValueError('Environment has conflicts; repair before accepting a new lock')
    # Coordinate with component updates; the file transaction also checks stale bytes.
    registry, _ = c.registry(target)
    steps = [{'adapter': item['adapter'], 'location': item['location']} for item in registry['components'].values()]
    with c.locks(target, steps):
        owned_paths = {c.safe(target, '.agentic-kit/components.lock')}
        owned_paths.update(c.safe(Path(item['location']), '.aek-update.lock') for item in steps)
        held_locks = {str(path): path.read_bytes() for path in owned_paths}
        fresh = _plan(target, held_locks)
        c.assert_plan(plan_id, fresh)
        desired = {LOCK: encoded({'schema_version': SCHEMA, 'snapshot': reviewed['snapshot']})}
        summary, desired, expected = install.files_plan(target, desired, replace=(LOCK,), context={'environment_plan_id': plan_id})
        install.transact(target, summary, desired, expected, summary['plan_id'])
    return {**reviewed, 'applied': True}


def check(target):
    current = plan(target)
    return {'schema_version': SCHEMA, 'lock_present': current['before_sha256'] is not None,
            'matches': not current['changed'], 'files_ready': current['snapshot']['files_ready'],
            'passed': not current['changed'] and current['safe_to_apply'],
            'content_sha256': current['snapshot']['content_sha256'],
            'problems': current['conflicts'], 'pending_recovery': current['pending_recovery'],
            'operation_locks': current['operation_locks'], 'provider_visibility': 'unverified',
            'next_action': 'none' if not current['changed'] and current['safe_to_apply'] else
                           'inspect_pending_operation' if current['pending_recovery'] or current['operation_locks'] else
                           'repair_managed_files' if current['conflicts'] else 'review_environment_plan'}
