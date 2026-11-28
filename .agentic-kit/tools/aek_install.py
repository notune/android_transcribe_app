"""Reviewed, transactional local installation. No network or package managers."""
import hashlib
from datetime import datetime, timezone
from uuid import uuid4
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import tempfile

MANIFEST = '.agentic-kit/install-manifest.json'
LOCK = '.aek-install.lock'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical_root(root):
    root = Path(root)
    if root.is_symlink():
        raise ValueError('Symlinked root is not allowed')
    if not root.is_dir():
        raise ValueError('Target must be an existing directory')
    return root.resolve()


def safe_path(root, name):
    name = str(name)
    rel = PurePosixPath(name)
    if not name or '\\' in name or rel.is_absolute() or any(p in ('', '.', '..') for p in name.split('/')):
        raise ValueError('Unsafe relative path: ' + name)
    current = Path(root)
    for index, part in enumerate(rel.parts):
        current = current / part
        if current.is_symlink():
            raise ValueError('Symlink in managed path: ' + name)
        if current.exists() and index < len(rel.parts) - 1 and not current.is_dir():
            raise ValueError('Non-directory managed parent: ' + name)
    if current.exists() and not current.is_file():
        raise ValueError('Non-file managed destination: ' + name)
    return current


def observe(root, name):
    file = safe_path(root, name)
    return file.read_bytes() if file.exists() else None


def helper(name):
    spec = importlib.util.spec_from_file_location('aek_install_' + name, Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def files_plan(root, desired, replace=(), context=None):
    root = canonical_root(root)
    desired = {str(k): v for k, v in desired.items()}
    add, update, unchanged, conflicts = [], [], [], []
    expected = {}
    for name, data in sorted(desired.items()):
        previous = observe(root, name)
        expected[name] = previous
        if previous is None:
            add.append(name)
        elif previous == data:
            unchanged.append(name)
        elif name in replace:
            update.append(name)
        else:
            conflicts.append(name)
    material = {'target': str(root), 'context': context or {}, 'files': {
        name: {'before': sha(expected[name]) if expected[name] is not None else None,
               'after': sha(data)} for name, data in sorted(desired.items())}}
    plan_id = sha(json.dumps(material, sort_keys=True).encode())[:24]
    return {'target': str(root), 'add': add, 'update': update, 'unchanged': unchanged,
            'conflicts': conflicts, 'plan_id': plan_id, 'safe_to_apply': not conflicts}, desired, expected


def assert_plan(expected, actual):
    if not expected or expected != actual:
        raise ValueError('Missing or stale plan_id; review a fresh plan')


def atomic_write(path, data, exclusive=False):
    if exclusive:
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0)
        descriptor = os.open(path, flags, 0o600)
        try:
            with os.fdopen(descriptor, 'wb') as output:
                output.write(data)
        except Exception:
            path.unlink(missing_ok=True)
            raise
        return
    mode = path.stat().st_mode & 0o777
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.aek-', delete=False) as out:
            temporary = Path(out.name)
            out.write(data)
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if temporary and temporary.exists():
            temporary.unlink()


def transact(root, summary, desired, expected, plan_id):
    """Apply one reviewed file set, restore completed writes on ordinary failure.

    The lock serializes cooperating installers; it is not a security boundary
    against another process running with the same filesystem permissions.
    """
    root = canonical_root(root)
    assert_plan(plan_id, summary['plan_id'])
    if summary['conflicts']:
        raise ValueError('Conflicting files; no files written: ' + ', '.join(summary['conflicts']))
    lock = root / LOCK
    fd = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(json.dumps({'pid': os.getpid(), 'operation_id': uuid4().hex,
                                 'created_at': datetime.now(timezone.utc).isoformat()}).encode())
    created_dirs, completed = [], []
    try:
        for name, old in expected.items():
            if observe(root, name) != old:
                raise ValueError('File changed after preview: ' + name)
        for name in summary['add'] + summary['update']:
            file = safe_path(root, name)
            parents = []
            current = file.parent
            while current != root and not current.exists():
                parents.append(current)
                current = current.parent
            for parent in reversed(parents):
                parent.mkdir()
                created_dirs.append(parent)
            if observe(root, name) != expected[name]:
                raise ValueError('File changed during apply: ' + name)
            atomic_write(file, desired[name], exclusive=expected[name] is None)
            completed.append(name)
        for name, data in desired.items():
            if observe(root, name) != data:
                raise ValueError('Post-install verification failed: ' + name)
    except Exception as original:
        errors = []
        for name in reversed(completed):
            try:
                file = safe_path(root, name)
                if observe(root, name) != desired[name]:
                    raise ValueError('Concurrent change prevents restore: ' + name)
                if expected[name] is None:
                    file.unlink()
                else:
                    atomic_write(file, expected[name])
            except (OSError, ValueError) as error:
                errors.append(str(error))
        for directory in reversed(created_dirs):
            try:
                directory.rmdir()
            except OSError:
                pass
        if errors:
            raise ValueError('Install failed and restore incomplete: ' + '; '.join(errors)) from original
        raise
    finally:
        lock.unlink()
    return {**summary, 'applied': True, 'verified': True}


def validate_source(source):
    source = canonical_root(source)
    # Reject links before source_files reads bytes, including nested assets.
    for folder in ('docs', 'roles', 'skills', 'templates', 'harnesses', 'profiles', 'shortcuts', 'tools'):
        path = source / folder
        if path.is_symlink():
            raise ValueError('Symlink in AEK source: ' + folder)
        if path.exists():
            for item in path.rglob('*'):
                if item.is_symlink():
                    raise ValueError('Symlink in AEK source: ' + str(item.relative_to(source)))
    for name in ('AGENTS.md', 'VERSION'):
        file = safe_path(source, name)
        if not file.is_file():
            raise ValueError('Missing AEK source: ' + name)
    return source


def greenfield_check(target):
    allowed = {'.git', '.gitignore', '.gitattributes', '.ds_store', 'agents.md', 'claude.md', 'gemini.md'}
    leftovers = []
    for path in target.iterdir():
        name = path.name.lower()
        if name in allowed or path.is_file() and (name.startswith('readme') or name.startswith('license')):
            continue
        leftovers.append(path.name)
    if leftovers:
        raise ValueError('Greenfield requires a new project; use existing mode for: ' + ', '.join(sorted(leftovers)))


def prepare(source, target, mode, source_files, adoption_note, activate=False, profiles=()):
    source, target = validate_source(source), canonical_root(target)
    if source == target or source in target.parents or target in source.parents:
        raise ValueError('AEK source must be outside the target project in a separate directory')
    if mode not in ('new', 'existing'):
        raise ValueError('Unknown installation mode')
    if safe_path(target, MANIFEST).exists():
        raise ValueError('AEK already installed; use upgrade')
    if mode == 'new':
        greenfield_check(target)
    if profiles and not activate:
        raise ValueError('Profiles require combined activation with --activate')
    desired = {str(Path('.agentic-kit') / rel): data for rel, data in source_files(source)}
    if '.agentic-kit/AGENTS.md' not in desired or '.agentic-kit/VERSION' not in desired:
        raise ValueError('Incomplete kit source')
    if mode == 'existing':
        desired['.agentic-kit/ADOPTION.md'] = adoption_note(target).encode('utf-8')
    if mode == 'new' and observe(target, 'AGENTS.md') is None:
        desired['AGENTS.md'] = b'# Project agent instructions\n\nRead .agentic-kit/AGENTS.md and the applicable role before work.\nFollow the user-approved project specifications and safety constraints.\n'
    managed = {name: sha(data) for name, data in sorted(desired.items())}
    manifest = {'schema_version': 1, 'source_identity': helper('aek_environment').identity(source, source_files), 'kit_version': (source / 'VERSION').read_text().strip(), 'mode': mode, 'managed_files': managed}
    replace = ()
    if activate:
        activation = helper('aek_activate')
        skills, root_text, instruction = activation.build(target, source, profiles, root_bytes=desired.get('AGENTS.md'))
        desired.update({str(k): v for k, v in skills.items()})
        desired['AGENTS.md'] = root_text.encode('utf-8')
        replace = ('AGENTS.md',)
        manifest['managed_skills'] = {str(k): sha(v) for k, v in skills.items()}
        manifest['active_profiles'] = sorted(set(profiles))
    desired[MANIFEST] = (json.dumps(manifest, indent=2, sort_keys=True) + '\n').encode()
    context = {'mode': mode, 'activate': bool(activate), 'profiles': sorted(set(profiles)), 'version': manifest['kit_version']}
    result, desired, expected = files_plan(target, desired, replace, context)
    result.update(context)
    result.update({'dry_run': True, 'writes': False})
    return result, desired, expected


def install(source, target, mode, source_files, adoption_note, apply=False, plan_id=None, activate=False, profiles=()):
    result, desired, expected = prepare(source, target, mode, source_files, adoption_note, activate, profiles)
    if apply:
        result = transact(target, result, desired, expected, plan_id)
        result.update({'dry_run': False, 'writes': True})
    return result
