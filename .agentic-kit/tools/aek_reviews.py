"""Local review gates. Imported MCP observations are not authenticated attestations."""
import copy
import datetime as dt
import hashlib
import json
import os
import tempfile
import re
import subprocess
from pathlib import Path

REVIEW_POLICIES = (1, 2)
EXECUTION_SOURCE_KINDS = ('session_observation', 'user_declared')
REVIEW_ROLES = ('review', 'qa', 'security')
REVIEW_ROLE_POLICY = {
    'standard': {'min_reviewers': 1, 'must_include': ()},
    'critical': {'min_reviewers': 2, 'must_include': (('security',), ('review', 'qa'))},
}
REPORT_SCHEMA_LEGACY = 1
REPORT_SCHEMA_FINDINGS = 2
REPORT_SCHEMAS = (REPORT_SCHEMA_LEGACY, REPORT_SCHEMA_FINDINGS)
REPORT_SEVERITIES = ('important', 'nit', 'pre-existing')
REPORT_DISPOSITIONS = ('open', 'fixed', 'resolved', 'rejected')
REPORT_UNRESOLVED_DISPOSITIONS = ('open', 'fixed')


def problem(code, message):
    """ValueError carrying a stable diagnostic code for status output."""
    error = ValueError(message)
    error.code = code
    return error


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def option_map(value):
    if value is None:
        return {}
    if isinstance(value, dict):
        items = list(value.items())
    elif isinstance(value, list) and all(isinstance(x, dict) and set(x) == {'id', 'value'} for x in value):
        items = [(x['id'], x['value']) for x in value]
    else:
        raise ValueError('Invalid route options')
    result = {}
    for key, val in items:
        if not isinstance(key, str) or not key.strip() or key in result or not isinstance(val, (str, bool)):
            raise ValueError('Invalid or duplicate route option')
        result[key] = val
    return result


def mutable(task):
    if task.get('stage') == 'done' or task.get('status') == 'cancelled':
        raise ValueError('Historical terminal task cannot change reviews')


def execution_document(value):
    """Validate the executor route recorded by the leading session.

    The local CLI cannot discover the provider, so this is a claim, not an
    attestation. Policy 2 uses it only to refuse reviewers on the same provider.
    """
    if not isinstance(value, dict) or set(value) - {'providerInstanceId', 'model', 'options', 'source'}:
        raise problem('execution_route_invalid', 'Execution route must be an object with providerInstanceId, model, options and source')
    provider = value.get('providerInstanceId')
    if not isinstance(provider, str) or not provider.strip() or len(provider) > 200:
        raise problem('execution_route_invalid', 'Execution route requires a nonempty providerInstanceId')
    model = value.get('model')
    if model is not None and (not isinstance(model, str) or not model.strip() or len(model) > 200):
        raise problem('execution_route_invalid', 'Execution route model must be a nonempty string or null')
    try:
        options = option_map(value.get('options'))
    except ValueError as exc:
        raise problem('execution_route_invalid', str(exc)) from exc
    source = value.get('source')
    if (not isinstance(source, dict) or set(source) != {'kind', 'reference'}
            or source.get('kind') not in EXECUTION_SOURCE_KINDS
            or not isinstance(source.get('reference'), str) or not source['reference'].strip()
            or len(source['reference']) > 2000):
        raise problem('execution_route_invalid',
                      'Execution route source requires kind=session_observation|user_declared and a reference')
    return {'providerInstanceId': provider.strip(),
            'model': model.strip() if isinstance(model, str) else None,
            'options': options,
            'source': {'kind': source['kind'], 'reference': source['reference'].strip()}}


def recorded_execution_route(task):
    value = task.get('execution')
    if not isinstance(value, dict) or set(value) != {'route', 'recorded_at'}:
        raise problem('execution_route_missing',
                      'Record the executor route before assigning independent reviewers')
    return execution_document(value.get('route'))


def set_execution_route(task, document):
    """Store the leading session's claimed route; a provider change needs a fresh task."""
    mutable(task)
    value = execution_document(document)
    previous = task.get('execution') if isinstance(task.get('execution'), dict) else None
    if previous and previous.get('route') == value:
        return False
    stored = task.get('reviews')
    assignments = (stored.get('assignments') if isinstance(stored, dict) else None) or []
    old_route = (previous or {}).get('route') or {}
    if (assignments and old_route.get('providerInstanceId')
            and provider_key(old_route['providerInstanceId']) != provider_key(value['providerInstanceId'])):
        raise problem('execution_route_locked',
                      'Executor provider changed after review assignments; start a fresh task and assignments')
    now = dt.datetime.now(dt.timezone.utc).isoformat()
    task['execution'] = {'route': value, 'recorded_at': now}
    task.setdefault('history', []).append({'event': 'execution.route_recorded', 'at': now,
                                           'previous': copy.deepcopy(previous),
                                           'route': copy.deepcopy(value)})
    return True


def provider_key(value):
    """Normalized provider identity used by all independence comparisons.

    Case and surrounding whitespace are presentation details of a locally
    editable document, so both are folded: `Codex ` and `codex` are the same
    provider and must never count as two independent harnesses.
    """
    return value.strip().casefold() if isinstance(value, str) else None


def assignment_problem(assignment):
    """Shape/type diagnostic for a stored assignment. Never raises.

    Inspecting a locally editable task must not crash the CLI on tampered data,
    so malformed entries become codes instead of TypeError.
    """
    if not isinstance(assignment, dict):
        return 'review_assignment_invalid'
    for key in ('id', 'role', 'parent_session_id'):
        if not isinstance(assignment.get(key), str) or not assignment[key].strip():
            return 'review_assignment_invalid'
    route = assignment.get('route')
    if (not isinstance(route, dict) or not isinstance(route.get('providerInstanceId'), str)
            or not route['providerInstanceId'].strip()):
        return 'review_assignment_invalid'
    schema = assignment.get('report_schema', REPORT_SCHEMA_LEGACY)
    if type(schema) is not int or schema not in REPORT_SCHEMAS:
        return 'review_assignment_invalid'
    return None


def report_schema(assignment):
    """Schema 2 is the default for new assignments; stored legacy ones stay schema 1."""
    value = assignment.get('report_schema', REPORT_SCHEMA_LEGACY)
    if type(value) is not int or value not in REPORT_SCHEMAS:
        raise problem('review_assignment_invalid', 'Assignment report_schema must be 1 or 2')
    return value


def review_report(verdict):
    """Validate a schema 2 findings report and return gate-relevant counts.

    The report is a claim of the reviewer, not authenticated evidence. This
    validator only makes its shape and its own internal counts consistent, and
    refuses an approval that still has open or fixed Important findings.
    """
    summary = verdict.get('summary')
    findings = verdict.get('findings')
    if not isinstance(summary, dict) or not isinstance(findings, list):
        raise problem('review_report_invalid',
                      'Report schema 2 requires summary and findings')
    total = summary.get('findings_total')
    if type(total) is not int or total < 0:
        raise problem('review_report_invalid',
                      'summary.findings_total must be a non-negative integer')
    reported = summary.get('by_severity')
    if not isinstance(reported, dict):
        raise problem('review_report_invalid', 'summary.by_severity must be an object')
    counts = {severity: 0 for severity in REPORT_SEVERITIES}
    for key, value in reported.items():
        if key not in REPORT_SEVERITIES or type(value) is not int or value < 0:
            raise problem('review_report_invalid',
                          'summary.by_severity allows only non-negative counts for '
                          + ', '.join(REPORT_SEVERITIES))
        counts[key] = value
    computed = {severity: 0 for severity in REPORT_SEVERITIES}
    ids = set()
    unresolved = []
    for item in findings:
        if not isinstance(item, dict):
            raise problem('review_report_invalid', 'Each finding must be an object')
        finding_id = item.get('id')
        if not isinstance(finding_id, str) or not finding_id.strip() or finding_id in ids:
            raise problem('review_report_invalid',
                          'Finding id must be a unique non-empty string')
        ids.add(finding_id)
        severity = item.get('severity')
        if severity not in REPORT_SEVERITIES:
            raise problem('review_report_invalid',
                          'Finding severity must be ' + ', '.join(REPORT_SEVERITIES))
        disposition = item.get('disposition')
        if disposition not in REPORT_DISPOSITIONS:
            raise problem('review_report_invalid',
                          'Finding disposition must be ' + ', '.join(REPORT_DISPOSITIONS))
        path = item.get('path')
        if not isinstance(path, str) or not path.strip():
            raise problem('review_report_invalid', 'Finding requires a repository path')
        line = item.get('line')
        if line is not None and (type(line) is not int or line < 1):
            raise problem('review_report_invalid', 'Finding line must be a positive integer')
        if severity in ('important', 'nit') and line is None:
            raise problem('review_report_invalid',
                          'An important or nit finding requires a line')
        if not isinstance(item.get('claim'), str) or not item['claim'].strip():
            raise problem('review_report_invalid',
                          'Finding claim must be a non-empty string')
        if 'class' in item and (not isinstance(item['class'], str) or not item['class'].strip()):
            raise problem('review_report_invalid', 'Finding class must be a non-empty string')
        if severity == 'important' and (not isinstance(item.get('class'), str)
                                        or not item['class'].strip()):
            raise problem('review_report_invalid', 'An important finding requires a class')
        if severity == 'important' and (not isinstance(item.get('evidence'), str)
                                        or not item['evidence'].strip()):
            raise problem('review_report_invalid', 'An important finding requires evidence')
        if disposition in ('resolved', 'rejected') and (
                not isinstance(item.get('reason'), str) or not item['reason'].strip()):
            raise problem('review_report_invalid',
                          'Finding ' + disposition + ' requires a reason')
        computed[severity] += 1
        if severity == 'important' and disposition in REPORT_UNRESOLVED_DISPOSITIONS:
            unresolved.append(finding_id)
    if total != len(findings) or computed != counts:
        raise problem('review_report_invalid',
                      'summary counts do not match findings')
    return {'findings_total': len(findings), 'by_severity': computed,
            'unresolved_important': unresolved,
            'open_nits': sum(1 for item in findings if item['severity'] == 'nit'
                             and item['disposition'] == 'open')}


def review_role_check(profile, reviewers):
    """Roles and reviewer count required by the risk profile."""
    policy = REVIEW_ROLE_POLICY.get(profile)
    if policy is None:
        return {'passed': False, 'profile': profile, 'violations': [
            {'code': 'review_roles_incomplete', 'detail': 'review policy 2 requires a standard or critical profile'}]}
    roles = [item.get('role') if isinstance(item, dict) else None for item in reviewers]
    violations = []
    if len(reviewers) < policy['min_reviewers']:
        violations.append({'code': 'review_roles_incomplete',
                           'detail': f'{profile} requires at least {policy["min_reviewers"]} independent reviewers'})
    unknown = sorted({role for role in roles if not (isinstance(role, str) and role in REVIEW_ROLES)},
                     key=lambda role: '' if role is None else str(role))
    if unknown:
        violations.append({'code': 'review_role_unknown',
                           'detail': 'unknown reviewer role: ' + ', '.join(str(role) for role in unknown)})
    for group in policy['must_include']:
        if not any(role in group for role in roles):
            violations.append({'code': 'review_roles_incomplete',
                               'detail': f'{profile} requires a reviewer with role ' + ' or '.join(group)})
    return {'passed': not violations, 'profile': profile,
            'min_reviewers': policy['min_reviewers'],
            'allowed_roles': list(REVIEW_ROLES),
            'required_role_groups': [list(group) for group in policy['must_include']],
            'violations': violations}


def check_reviewer_routes(reviewers, execution):
    """Refuse reviewers sharing the executor provider or each other's provider."""
    executor = execution['providerInstanceId']
    executor_key = provider_key(executor)
    providers = []
    for item in reviewers:
        provider = provider_key((item.get('route') or {}).get('providerInstanceId')
                               if isinstance(item.get('route'), dict) else None)
        if not provider:
            raise problem('review_assignment_invalid',
                          f'Reviewer {item.get("id")} requires a nonempty string providerInstanceId')
        if provider == executor_key:
            raise problem('reviewer_route_not_independent',
                          f'Reviewer {item.get("id")} uses the executor provider {executor}; assign a different provider')
        providers.append(provider)
    if len(set(providers)) != len(providers):
        raise problem('reviewer_providers_not_distinct',
                      'Reviewers must use distinct provider instances')
    return True


def isolation_requirement(assignment):
    value = assignment.get('isolation')
    if (not isinstance(value, dict) or set(value) != {'context', 'filesystem'}
            or value.get('context') != 'separate'
            or value.get('filesystem') not in ('shared', 'worktree')):
        raise ValueError('Explicit isolation required: context=separate, filesystem=shared|worktree; migrate using a fresh assignment ID')
    return value


def isolation_check(root, assignment, launch, result, obs):
    required = isolation_requirement(assignment)
    # "shared" permits a shared directory; it does not claim one was observed.
    if required['filesystem'] == 'worktree':
        ref = obs.get('worktree', {})
        if ref.get('provenance') != 'local_git_worktree_observation':
            raise ValueError('Required worktree isolation needs a local Git observation; import result while worktree exists')
        observation = read_artifact(root, ref)
        validate_worktree(observation, assignment, launch, result, obs)
    return {'context': 'separate_child_session',
            'filesystem': 'worktree' if required['filesystem'] == 'worktree' else None,
            'required': required, 'filesystem_verified': required['filesystem'] == 'worktree',
            'basis': 'local_git_at_result_import' if required['filesystem'] == 'worktree' else None}


def worktree_git(path, *args, allowed_codes=(0,), input_text=None):
    # Read-only fixed argv, no inherited Git directory/config overrides or fsmonitor hook.
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    env['GIT_OPTIONAL_LOCKS'] = '0'
    try:
        completed = subprocess.run(['git', '-c', 'core.fsmonitor=false', '-C', str(path), *args],
                                   env=env, capture_output=True,
                                   input=None if input_text is None else input_text.encode('utf-8'), timeout=15)
        if completed.returncode not in allowed_codes:
            raise ValueError('Cannot observe reviewer Git worktree: Git command failed')
    except (OSError, subprocess.SubprocessError) as exc:
        raise ValueError('Cannot observe reviewer Git worktree: ' + str(exc)) from exc
    # NUL-delimited Git paths may contain CR/LF. Universal newline translation
    # would check attributes of a different file and could miss an active filter.
    return completed.stdout.decode('utf-8')


def worktree_git_state(path):
    path = Path(path).resolve(strict=True)
    # status can execute clean/process filters while refreshing the index.
    # Read names only (filter commands may contain credentials). A global LFS
    # registration is harmless unless attributes actually select it for a file.
    filters = worktree_git(path, 'config', '--null', '--name-only', '--get-regexp',
                           r'^filter\..*\.(clean|process)$', allowed_codes=(0, 1))
    # status can recurse into a submodule whose own config enables a filter.
    entries = worktree_git(path, 'ls-files', '--stage', '-z').split('\0')
    if any(entry.startswith('160000 ') for entry in entries):
        raise ValueError('Cannot observe reviewer worktree with submodules')
    paths = [entry.split('\t', 1)[1] for entry in entries if entry]
    if filters and paths:
        drivers = {key[len('filter.'):].rsplit('.', 1)[0] for key in filters.split('\0') if key}
        output = worktree_git(path, 'check-attr', '-z', '--stdin', 'filter',
                              input_text='\0'.join(paths) + '\0')
        fields = output.split('\0')
        if fields[-1] != '' or len(fields) != 3 * len(paths) + 1:
            raise ValueError('Incomplete Git filter attribute observation')
        for index, name in enumerate(paths):
            filename, attribute, driver = fields[index * 3:index * 3 + 3]
            if filename != name or attribute != 'filter':
                raise ValueError('Mismatched Git filter attribute observation')
            if driver in drivers:
                raise ValueError('Cannot observe reviewer worktree with active content filters')
    commands = [('top', ('rev-parse', '--show-toplevel')),
                ('git_dir', ('rev-parse', '--absolute-git-dir')),
                ('common_dir', ('rev-parse', '--git-common-dir')),
                ('head', ('rev-parse', 'HEAD')),
                ('tracked_status', ('status', '--porcelain=v1', '--untracked-files=no'))]
    raw = {key: worktree_git(path, *args) for key, args in commands}
    state = {key: value.strip() for key, value in raw.items()}
    for key in ('top', 'git_dir', 'common_dir'):
        state[key] = str((path / state[key]).resolve(strict=True))
    if state['top'] != str(path):
        raise ValueError('Reviewer worktree path must be its Git root')
    return {**state, 'commands': [{'argv': list(args), 'stdout': raw[key], 'returncode': 0}
                                for key, args in commands]}


def registered_worktrees(output):
    records = []
    current = {}
    for field in output.split('\0'):
        if not field:
            if current:
                records.append(current)
                current = {}
        else:
            key, _, value = field.partition(' ')
            current[key] = value
    if current:
        records.append(current)
    return records


def validate_worktree(value, assignment, launch, result, refs):
    thread = result['thread']
    sha = _result_sha(result, launch['childRunId'])
    expected = {'schema_version': 1, 'kind': 'local_git_worktree_observation',
                'assignment_id': assignment['id'], 'thread_id': launch['childThreadId'],
                'run_id': launch['childRunId'], 'reviewed_sha': sha,
                'launch_sha256': refs['launch']['sha256'], 'result_sha256': refs['result']['sha256'],
                'reported_path': thread.get('worktreePath')}
    if not sha or any(value.get(key) != val for key, val in expected.items()):
        raise ValueError('Worktree observation binding mismatch')
    if launch.get('isolation') not in (None, 'worktree'):
        raise ValueError('Launch contradicts required worktree isolation')
    parent, child = value['parent'], value['child']
    for state in (parent, child):
        if any(not isinstance(state.get(k), str) or not Path(state[k]).is_absolute()
               for k in ('top', 'git_dir', 'common_dir')) or state.get('head') != sha:
            raise ValueError('Worktree Git path or HEAD mismatch')
    if (parent['top'] == child['top'] or parent['git_dir'] == child['git_dir']
            or parent['common_dir'] != child['common_dir']):
        raise ValueError('Reviewer is not a separate worktree of the parent repository')
    if child.get('tracked_status') != '':
        raise ValueError('Reviewer worktree has modified tracked files or index')
    registered = [item for item in value['registered_worktrees'] if item.get('worktree') == child['top']]
    if len(registered) != 1 or registered[0].get('HEAD') != sha:
        raise ValueError('Reviewer worktree is not registered at reviewed SHA')
    if thread.get('branch') and registered[0].get('branch') != 'refs/heads/' + thread['branch']:
        raise ValueError('Reviewer worktree branch differs from T3 observation')


def capture_worktree(root, assignment, launch, result, refs):
    """Observe local Git now; never rewrite T3 metadata or parse an agent's prose."""
    thread = result['thread']
    run_id = launch['childRunId']
    # Entries of a paged T3 read are objects; anything else is damaged input and
    # must fail the completeness check below instead of raising AttributeError.
    runs = [run for run in result['recentRuns']
            if isinstance(run, dict) and run.get('runId') == run_id]
    messages = [item for item in result['items']
                if isinstance(item, dict) and item.get('type') == 'assistant_message'
                and item.get('runId') == run_id]
    if (thread.get('threadId') != launch['childThreadId']
            or thread.get('parentThreadId') != assignment['parent_session_id']
            or thread.get('relationshipToParent') != 'subagent'
            or thread.get('threadId') == assignment['parent_session_id']
            or thread.get('activeRunId') or thread.get('latestRunId') != run_id
            or thread.get('status') != 'completed' or len(runs) != 1 or runs[0].get('status') != 'completed'
            or result.get('hasMore') is not False or not messages or messages[-1].get('textTruncated') is not False):
        raise ValueError('Worktree capture requires a complete current child result')
    verdict = json.loads(messages[-1]['text'])
    if not isinstance(verdict, dict) or verdict.get('assignment_id') != assignment['id']:
        raise ValueError('Worktree result assignment mismatch')
    path = thread.get('worktreePath')
    if not isinstance(path, str) or not path.strip() or not Path(path).is_absolute():
        raise ValueError('T3 result missing absolute worktreePath')
    parent, child = worktree_git_state(root), worktree_git_state(path)
    output = worktree_git(root, 'worktree', 'list', '--porcelain', '-z')
    registered = registered_worktrees(output)
    for item in registered:
        if 'worktree' in item:
            item['worktree'] = str(Path(item['worktree']).resolve())
    value = {'schema_version': 1, 'kind': 'local_git_worktree_observation',
             'assignment_id': assignment['id'], 'thread_id': launch['childThreadId'],
             'run_id': run_id, 'reviewed_sha': _result_sha(result, run_id),
             'launch_sha256': refs['launch']['sha256'], 'result_sha256': refs['result']['sha256'],
             'reported_path': path, 'parent': parent, 'child': child,
             'registered_worktrees': registered, 'worktree_list_stdout': output}
    validate_worktree(value, assignment, launch, result, refs)
    # Recheck source state after collecting the registration to detect ordinary races.
    if worktree_git_state(root) != parent or worktree_git_state(path) != child:
        raise ValueError('Git worktree changed during observation; retry when stable')
    return value


def assignment_index(value, label):
    """Assignment records addressed by ID; malformed entries are refused, never guessed.

    IDs become dictionary keys, so a non-string ID would otherwise surface as
    `TypeError: unhashable type` or a silent overwrite instead of a diagnostic.
    """
    if isinstance(value, dict):
        items = list(value.values())
    elif isinstance(value, list):
        items = value
    else:
        raise problem('review_assignment_invalid',
                      f'Stored {label} must be a list or object of assignment records')
    index = {}
    for item in items:
        if (not isinstance(item, dict) or not isinstance(item.get('id'), str)
                or not item['id'].strip()):
            raise problem('review_assignment_invalid',
                          f'Stored {label} requires assignment records with string ids')
        index[item['id']] = item
    return index


def retired_ids(config):
    """Stored retired IDs; a damaged list is a diagnostic, never an empty set."""
    value = config.get('retired', [])
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise problem('review_assignment_invalid',
                      'Stored retired assignment IDs must be a list of strings')
    return set(value)


def stored_map(config, label):
    """Stored ID -> object map (observations, rounds); a damaged shape is a diagnostic."""
    value = config.get(label, {})
    if (not isinstance(value, dict)
            or any(not isinstance(key, str) or not isinstance(item, dict) for key, item in value.items())):
        raise problem('review_assignment_invalid',
                      f'Stored review {label} must map string IDs to objects')
    return value


def stored_reference(value, label):
    """Archived artifact reference; a damaged reference is a diagnostic, not a crash.

    The import path reads these references directly, so a hand-edited number or
    string used to surface as `TypeError: 'int' object is not subscriptable`
    with no error.code instead of `review_assignment_invalid`.
    """
    if (not isinstance(value, dict) or not isinstance(value.get('path'), str) or not value['path'].strip()
            or not isinstance(value.get('sha256'), str) or not value['sha256'].strip()):
        raise problem('review_assignment_invalid',
                      f'Stored {label} must be an archived artifact reference')
    return value


def stored_artifacts(value, label):
    """Artifact references of one observation or round record, validated."""
    if not isinstance(value, dict):
        raise problem('review_assignment_invalid', f'Stored {label} must be an object')
    for kind in ('launch', 'result', 'worktree'):
        if kind in value:
            stored_reference(value[kind], f'{label} {kind}')
    for kind in ('conflicts', 'supplemental'):
        items = value.get(kind, [])
        if not isinstance(items, list):
            raise problem('review_assignment_invalid', f'Stored {label} {kind} must be a list')
        for item in items:
            if not isinstance(item, dict):
                raise problem('review_assignment_invalid',
                              f'Stored {label} {kind} records must be objects')
            stored_reference(item.get('artifact'), f'{label} {kind} artifact')
    return value


def stored_round(value, label):
    """One archived review round; identity and archived references are validated."""
    stored_artifacts(value, label)
    for key in ('thread_id', 'run_id'):
        if not isinstance(value.get(key), str) or not value[key].strip():
            raise problem('review_assignment_invalid', f'Stored {label} requires {key}')
    return value


def stored_reviews(task, require_ids=False):
    """Normalize stored review state; tampered shapes become a diagnostic, not a crash.

    `assign` and `inspect` read retired IDs, bindings, assignments, observations
    and rounds from a locally editable JSON file. A hand-edited value must be
    refused with `review_assignment_invalid` instead of raising TypeError, and a
    damaged `retired` list must never be treated as empty: that would silently
    un-retire IDs and let status/resume accept an approval it should refuse.
    """
    value = task.get('reviews')
    if value is None:
        return None
    if not isinstance(value, dict):
        raise problem('review_assignment_invalid', 'Stored reviews must be an object')
    retired_ids(value)
    bindings = value.get('bindings', {})
    assignments = value.get('assignments', [])
    if not isinstance(bindings, dict) or any(not isinstance(item, dict) for item in bindings.values()):
        raise problem('review_assignment_invalid',
                      'Stored review bindings must be an object of assignment records')
    if not isinstance(assignments, list):
        raise problem('review_assignment_invalid',
                      'Stored review assignments must be a list of assignment records')
    if require_ids:
        assignment_index(assignments, 'review assignments')
        assignment_index(bindings, 'review bindings')
    for item in stored_map(value, 'observations').values():
        stored_artifacts(item, 'review observation')
    for item in stored_map(value, 'rounds').values():
        for key, record in item.items():
            if not isinstance(key, str) or not key.strip():
                raise problem('review_assignment_invalid',
                              'Stored review rounds require string round keys')
            stored_round(record, 'review round')
    return value


def stored_history(task):
    """Stored review history; a damaged container or record is a diagnostic.

    Assign and import append to this list. A hand-edited `null`, `{}`, `false`
    or a non-object record must not surface as AttributeError/TypeError from the
    CLI, and a non-object `previous` must not be read as an empty one: that would
    drop its bindings and let a retired assignment ID be bound again.
    """
    value = task.get('review_history', [])
    if not isinstance(value, list):
        raise problem('review_assignment_invalid', 'Stored review history must be a list')
    for record in value:
        if not isinstance(record, dict):
            raise problem('review_assignment_invalid', 'Stored review history records must be objects')
        prior = record.get('previous')
        if prior is not None and not isinstance(prior, dict):
            raise problem('review_assignment_invalid',
                          'Stored review history previous state must be an object')
    return value


def stored_index(value):
    """Assignment records of a stored state object, validated as a map by ID."""
    index = assignment_index(value.get('bindings', {}), 'review bindings')
    index.update(assignment_index(value.get('assignments', []), 'review assignments'))
    return index


def assign(task, document):
    mutable(task)
    if not isinstance(document, dict):
        raise ValueError("Assignment document must be an object")
    reviewers = document.get('reviewers')
    if not isinstance(document.get('agreement'), str) or not document['agreement'].strip():
        raise ValueError('Explicit assignment agreement reference required')
    if not isinstance(reviewers, list) or not reviewers:
        raise ValueError('At least one assigned independent reviewer required')
    policy = document.get('policy', task.get('review_policy') or 1)
    if type(policy) is not int or policy not in REVIEW_POLICIES:
        raise ValueError('Unsupported review policy')
    current_policy = task.get('review_policy')
    if current_policy == 2 and policy == 1:
        raise problem('review_policy_downgrade', 'Review policy cannot be downgraded; create a new task')
    ids = set()
    reviewers = copy.deepcopy(reviewers)
    for item in reviewers:
        if not isinstance(item, dict):
            raise ValueError('Invalid reviewer')
        for key in ('id', 'role', 'parent_session_id'):
            if not isinstance(item.get(key), str) or not item[key].strip():
                raise ValueError('Reviewer missing ' + key)
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', item['id']) or item['id'] in ids:
            raise ValueError('Duplicate or invalid assignment ID')
        route = item.get('route')
        if not isinstance(route, dict) or not isinstance(route.get('providerInstanceId'), str) or not route['providerInstanceId'].strip():
            raise ValueError('Explicit reviewer route with a string providerInstanceId required')
        route['providerInstanceId'] = route['providerInstanceId'].strip()
        option_map(route.get('options'))
        if policy == 1:
            if 'report_schema' in item:
                raise ValueError('Report schema requires review policy 2')
        else:
            schema = item.get('report_schema', REPORT_SCHEMA_FINDINGS)
            if type(schema) is not int or schema not in REPORT_SCHEMAS:
                raise ValueError('Reviewer report_schema must be 1 or 2')
        isolation_requirement(item)
        ids.add(item['id'])
    previous = stored_reviews(task, require_ids=True)
    retired = retired_ids(previous) if previous else set()
    # An ID cannot be rebound to a different reviewer. Use a fresh ID for reassignment.
    history = stored_history(task)
    old = {}
    for record in history:
        prior = record.get('previous')
        if prior is not None:
            old.update(stored_index(prior))
    if previous:
        old.update(stored_index(previous))

    if policy == 2:
        # A stored assignment keeps the schema it was created with. One saved
        # without the field stays legacy (schema 1) after the upgrade; only a
        # fresh ID starts on schema 2.
        for item in reviewers:
            if 'report_schema' in item:
                continue
            record = old.get(item['id'])
            if record is None:
                item['report_schema'] = REPORT_SCHEMA_FINDINGS
            elif 'report_schema' in record:
                item['report_schema'] = record['report_schema']
    for item in reviewers:
        if item['id'] in old and item != old[item['id']]:
            raise ValueError('Changed assignment requires a fresh ID')
    if policy == 2:
        execution = recorded_execution_route(task)
        if current_policy != 2:
            # Every ID bound under the previous policy is retired for good: a later
            # assignment call must not reintroduce it once the policy is already 2.
            retired.update(old)
        reintroduced = sorted(retired & {item['id'] for item in reviewers})
        if reintroduced:
            raise problem('review_assignment_retired',
                          'Assignment ID retired at policy migration; use a fresh ID: ' + ', '.join(reintroduced))
        check_reviewer_routes(reviewers, execution)
        roles = review_role_check(task.get('profile'), reviewers)
        if not roles['passed']:
            codes = [item['code'] for item in roles['violations']]
            raise problem('review_role_unknown' if 'review_role_unknown' in codes else 'review_roles_incomplete',
                          '; '.join(item['detail'] for item in roles['violations']))
    history.append({'event': 'assignments_changed', 'previous': copy.deepcopy(previous),
                    'agreement': document['agreement'],
                    'policy': policy})
    task['review_history'] = history
    task['review_policy'] = policy
    old.update({x['id']: x for x in reviewers})
    task['reviews'] = {'agreement': document['agreement'], 'assignments': copy.deepcopy(reviewers),
                       'bindings': copy.deepcopy(old),
                       'retired': sorted(retired),
                       'rounds': copy.deepcopy((previous or {}).get('rounds', {})),
                       'observations': {k: copy.deepcopy(v) for k, v in (previous or {}).get('observations', {}).items() if k in ids}}


def safe_path(root, filename):
    original_root = Path(root).absolute()
    root = original_root.resolve()
    p = Path(filename)
    if not p.is_absolute():
        p = root / p
    # Normalize an OS root alias (/tmp on macOS), then reject child symlinks.
    try:
        relative = p.relative_to(original_root)
    except ValueError:
        try:
            relative = p.relative_to(root)
        except ValueError:
            # CLI resolves --target, while a source path may retain an OS alias.
            anchor = next((parent for parent in p.parents if parent.resolve() == root), None)
            if anchor is None:
                raise ValueError('Review observation must be inside target')
            relative = p.relative_to(anchor)
    p = root / relative
    if '..' in relative.parts:
        raise ValueError('Unsafe review observation path')
    current = root
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError('Symlink review observation path')
    p.resolve().relative_to(root)
    return p


def artifact(root, filename):
    p = safe_path(root, filename)
    if not p.is_file() or not p.stat().st_size:
        raise ValueError('Nonempty tool observation required')
    return {'path': str(p.relative_to(Path(root).resolve())),
            'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}


def decode_observation(raw):
    if not isinstance(raw, dict):
        raise ValueError('Observation must be an object')
    if isinstance(raw.get('structuredContent'), dict):
        value = raw['structuredContent']
    elif isinstance(raw.get('content'), list):
        value = json.loads(raw['content'][0]['text'])
    else:
        value = raw
    if not isinstance(value, dict):
        raise ValueError('Observation must be an object')
    return value


def read_artifact(root, ref):
    p = safe_path(root, ref['path'])
    data = p.read_bytes()
    if hashlib.sha256(data).hexdigest() != ref['sha256']:
        raise ValueError('Review observation changed')
    return decode_observation(json.loads(data))


def snapshot(root, filename):
    """Archive the complete tool response before attaching it to task state.

    Atomic no-replace publication makes retries safe, including after a failed
    task CAS save. Unreferenced blobs are harmless and never overwrite history.
    """
    source = safe_path(root, filename)
    raw = json.loads(source.read_bytes())
    value = decode_observation(raw)
    return archive_observation(root, raw), value


def archive_observation(root, raw):
    """Store either an unchanged tool envelope or our separately labelled Git observation."""
    data = (json.dumps(raw, sort_keys=True, ensure_ascii=False, indent=2) + '\n').encode()
    digest = hashlib.sha256(data).hexdigest()
    folder = safe_path(root, '.agentic-kit/evidence/review-observations')
    folder.mkdir(parents=True, exist_ok=True)
    destination = safe_path(root, folder / (digest + '.json'))
    if destination.exists():
        if destination.read_bytes() != data:
            raise ValueError('Archived review observation changed')
    else:
        fd, temporary = tempfile.mkstemp(prefix='.observation-', dir=folder)
        try:
            with os.fdopen(fd, 'wb') as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            try:
                os.link(temporary, destination)  # atomic, fails if destination exists
            except FileExistsError:
                if safe_path(root, destination).read_bytes() != data:
                    raise ValueError('Archived review observation changed')
        finally:
            Path(temporary).unlink(missing_ok=True)
    return {'path': str(destination.relative_to(Path(root).resolve())), 'sha256': digest}


def _round_key(thread_id, run_id):
    return fingerprint({'thread_id': thread_id, 'run_id': run_id})


def _result_sha(value, run_id):
    messages = [x for x in value.get('items', []) if isinstance(x, dict)
                and x.get('type') == 'assistant_message' and x.get('runId') == run_id]
    try:
        result = json.loads(messages[-1].get('text', ''))
        sha = result.get('reviewed_sha')
        return sha if isinstance(sha, str) and re.fullmatch(r'[0-9a-f]{40,64}', sha) else None
    except (ValueError, IndexError, AttributeError, TypeError):
        return None


def comparable_observation(value):
    """Ignore only known delivery acknowledgements, never execution/verdict fields."""
    result = copy.deepcopy(value)
    containers = [result, result.get('thread')]
    containers.extend(result.get('recentRuns', []))
    for item in containers:
        if isinstance(item, dict):
            item.pop('settled', None)
            item.pop('settledAt', None)
    return result


def result_round(value, rounds, current_launch):
    """Resolve a result from its own identifiers, never the current launch pointer.

    A later read can include the previous run's answer plus a new active run.
    Associate that contradictory read with the known answer's round so it blocks
    approval. An unrelated thread/run is not silently rebound to any round.
    """
    thread = value['thread']
    thread_id = thread.get('threadId')
    latest = thread.get('latestRunId')
    if not isinstance(thread_id, str) or not thread_id:
        raise ValueError('Result missing reviewer thread identifier')
    known = {r['run_id']: r for r in rounds.values() if r['thread_id'] == thread_id}
    observed = {r.get('runId') for r in value['recentRuns'] if isinstance(r, dict)
                and isinstance(r.get('runId'), str)}
    if isinstance(latest, str) and latest in known and latest in observed:
        return thread_id, latest
    # The known current child can have moved on far enough that its previous
    # answer/run disappeared from this page. This is a supersession blocker,
    # never a result to attach to that old run. Keep the identity only so observe
    # archives the contradiction against its immutable prior result.
    current_run = current_launch['childRunId']
    if (thread_id == current_launch['childThreadId'] and current_run in known
            and ((isinstance(latest, str) and latest and latest != current_run)
                 or thread.get('activeRunId'))):
        return thread_id, current_run
    answers = {item.get('runId') for item in value['items'] if isinstance(item, dict)
               and item.get('type') == 'assistant_message' and isinstance(item.get('runId'), str)}
    candidates = set(known) & observed & answers
    if len(candidates) == 1:
        return thread_id, candidates.pop()
    raise ValueError('Result does not identify one imported reviewer run')


def observe(root, task, assignment_id, kind, filename, fixture=False):
    """Returns changed/conflict so CLI can persist rejection before exiting.

    A conflicting later observation invalidates the active approval while
    preserving both snapshots. Simply raising would leave stale approval valid.
    """
    mutable(task)
    if task.get('review_policy') not in REVIEW_POLICIES:
        raise ValueError('Migrate open legacy task through review assign first')
    config = stored_reviews(task, require_ids=True)
    if config is None:
        raise problem('review_assignment_invalid', 'Stored review assignments are missing')
    history = stored_history(task)
    # Only an active assignment accepts observations. Historical bindings name
    # reviewers that no longer act, and retired IDs must stay out of service.
    if assignment_id in retired_ids(config):
        raise problem('review_assignment_retired', 'Assignment ID retired: ' + assignment_id)
    assignment = assignment_index(config.get('assignments', []), 'review assignments').get(assignment_id)
    if assignment is None:
        raise problem('review_assignment_invalid', 'Unknown review assignment: ' + assignment_id)
    isolation_requirement(assignment)
    if 'review_history' not in task:
        task['review_history'] = history
    # Validate stored observations and rounds before creating an archive file:
    # a damaged nested record must refuse the import, not crash the command.
    obs = stored_artifacts(stored_map(config, 'observations').get(assignment_id, {}),
                           'review observation')
    rounds = {key: stored_round(record, 'review round')
              for key, record in stored_map(config, 'rounds').get(assignment_id, {}).items()}
    # Validate the input shape before creating an archive file.
    source = artifact(root, filename)
    try:
        value = read_artifact(root, source)
    except ValueError as exc:
        raise problem('review_observation_invalid', str(exc)) from None
    if kind == 'launch':
        if not all(isinstance(value.get(k), str) and value[k].strip()
                   for k in ('taskId', 'childThreadId', 'childRunId', 'providerInstanceId')):
            raise problem('review_observation_invalid',
                          'delegate_task response missing launch identifiers')
        thread_id, run_id = value['childThreadId'], value['childRunId']
    elif kind == 'result':
        if (not isinstance(value.get('thread'), dict)
                or not isinstance(value.get('recentRuns'), list)
                or not isinstance(value.get('items'), list)
                or any(not isinstance(entry, dict) for entry in value['recentRuns'])
                or any(not isinstance(entry, dict) for entry in value['items'])):
            raise problem('review_observation_invalid',
                          'Expected t3_thread_read response, not an agent assertion')
        if 'launch' not in obs:
            raise ValueError('Import launch before its result')
        current_launch = read_artifact(root, obs['launch'])
        thread_id, run_id = result_round(value, rounds, current_launch)
    else:
        raise ValueError('Unknown review observation kind')
    key = _round_key(thread_id, run_id)
    round_record = rounds.get(key)
    # Older observations cannot silently acquire archive/isolation guarantees.
    if obs and not rounds:
        raise ValueError('Legacy review observations require a fresh assignment and new run')
    if round_record is not None:
        for field in ('launch', 'result', 'worktree'):
            if field in round_record:
                read_artifact(root, round_record[field])
    ref, archived_value = snapshot(root, filename)
    if archived_value != value:
        raise ValueError('Observation changed during import; retry from stable input')
    ref['provenance'] = 'fixture' if fixture else 'agent_imported_t3_observation'
    previous = (round_record or {}).get(kind)
    if previous == ref:
        return {'changed': False, 'conflict': bool((round_record or {}).get('conflicts'))}
    if previous is not None and previous.get('provenance') == ref['provenance'] and (
            comparable_observation(read_artifact(root, previous)) == comparable_observation(value)):
        supplemental = {'kind': kind, 'artifact': ref}
        snapshots = round_record.setdefault('supplemental', [])
        if supplemental in snapshots:
            return {'changed': False, 'conflict': bool(round_record.get('conflicts'))}
        snapshots.append(supplemental)
        history.append({'event': 'observation_supplemented',
            'assignment_id': assignment_id, 'thread_id': thread_id, 'run_id': run_id,
            **copy.deepcopy(supplemental)})
        return {'changed': True, 'conflict': bool(round_record.get('conflicts'))}
    conflict = previous is not None
    # A run identifier cannot be rebound to a different thread inside an assignment.
    if kind == 'launch' and any(r['run_id'] == run_id and r['thread_id'] != thread_id for r in rounds.values()):
        conflict = True
    if kind == 'result':
        runs = [r for r in value['recentRuns'] if isinstance(r, dict) and r.get('runId') == run_id]
        terminal = (value['thread'].get('threadId') == thread_id and len(runs) == 1
                    and runs[0].get('status') in ('completed', 'failed', 'cancelled', 'interrupted')
                    and not value['thread'].get('activeRunId')
                    and value['thread'].get('latestRunId') == run_id)
        if not terminal and previous is None:
            raise ValueError('Import a complete terminal observation for the current run; unfinished reads are not review results')
    rounds = config.setdefault('rounds', {}).setdefault(assignment_id, {})
    if conflict:
        # The immutable original is untouched. The new contradictory observation
        # is a durable blocker, requiring a fresh real run, not a verdict rewrite.
        blocker = {'kind': kind, 'artifact': ref, 'thread_id': thread_id, 'run_id': run_id}
        current = config.setdefault('observations', {}).setdefault(assignment_id, {})
        conflicts = current.setdefault('conflicts', [])
        if blocker in conflicts:
            return {'changed': False, 'conflict': True}
        conflicts.append(blocker)
        if round_record is not None:
            round_record.setdefault('conflicts', []).append(copy.deepcopy(blocker))
        history.append({'event': 'observation_conflict',
            'assignment_id': assignment_id, **copy.deepcopy(blocker)})
        return {'changed': True, 'conflict': True, 'message': 'Conflicting observation preserved; use a new reviewer run'}
    worktree_ref = None
    if kind == 'result' and isolation_requirement(assignment)['filesystem'] == 'worktree':
        launch_ref = round_record['launch']
        worktree = capture_worktree(root, assignment, read_artifact(root, launch_ref), value,
                                    {'launch': launch_ref, 'result': ref})
        worktree_ref = archive_observation(root, worktree)
        worktree_ref['provenance'] = 'local_git_worktree_observation'
    if round_record is None:
        round_record = {'thread_id': thread_id, 'run_id': run_id}
        rounds[key] = round_record
    round_record[kind] = copy.deepcopy(ref)
    if worktree_ref:
        round_record['worktree'] = copy.deepcopy(worktree_ref)
    if kind == 'result':
        round_record['reviewed_sha'] = _result_sha(value, run_id)
    history.append({'event': 'observation_imported', 'assignment_id': assignment_id,
        'kind': kind, 'artifact': copy.deepcopy(ref), 'thread_id': thread_id, 'run_id': run_id,
        'reviewed_sha': round_record.get('reviewed_sha'), 'fixture': fixture})
    current = config.setdefault('observations', {}).setdefault(assignment_id, {})
    if kind == 'launch':
        current.clear()  # Fresh run invalidates current result, archived rounds remain.
    if kind == 'launch':
        current[kind] = copy.deepcopy(ref)
    else:
        current_launch = read_artifact(root, current['launch'])
        if (thread_id, run_id) == (current_launch['childThreadId'], current_launch['childRunId']):
            current[kind] = copy.deepcopy(ref)
            if worktree_ref:
                current['worktree'] = copy.deepcopy(worktree_ref)
    return {'changed': True, 'conflict': False}


def inspect(root, task, head):
    required = task.get('profile') in ('standard', 'critical')
    if not required:
        return {'passed': True, 'required': False, 'reviews': [], 'next_action': 'none'}
    policy = task.get('review_policy')
    if policy not in REVIEW_POLICIES:
        return {'passed': False, 'required': True, 'reviews': [], 'next_action': ('create_new_task' if task.get('status') == 'cancelled' else 'task_complete' if task.get('stage') == 'done' else 'migrate_open_task_review_assign'),
                'problems': ['legacy_review_policy_unconfirmed'], 'historical_terminal': task.get('stage') == 'done' or task.get('status') == 'cancelled'}
    execution = None
    if policy == 2:
        try:
            execution = recorded_execution_route(task)
        except ValueError as exc:
            return {'passed': False, 'required': True, 'policy': policy, 'reviews': [],
                    'problems': [getattr(exc, 'code', 'execution_route_invalid')],
                    'execution_route': None, 'next_action': 'record_execution_route',
                    'message': str(exc),
                    'trust': 'Locally recorded executor route; provider and session are not authenticated'}
    config = task.get('reviews')
    if config is not None and (not isinstance(config, dict)
                               or not isinstance(config.get('assignments'), list)):
        # Without a review object and an assignment list there are no rows to
        # report: refuse the whole state instead of crashing status/resume.
        value = {'passed': False, 'required': True, 'reviews': [],
                 'problems': ['review_assignment_invalid'], 'next_action': 'review_assign',
                 'message': 'Stored reviews must be an object with a list of assignments'}
        if policy == 2:
            value['policy'] = policy
            value['execution_route'] = execution
        return value
    rows = []
    config = config or {}
    assignments = config.get('assignments') or []
    if not assignments:
        if policy == 1:
            return {'passed': False, 'required': True, 'reviews': [],
                    'problems': ['review_assignments_missing'], 'next_action': 'review_assign'}
        return {'passed': False, 'required': True, 'policy': policy, 'reviews': [],
                'problems': ['review_assignments_missing'],
                'execution_route': execution, 'next_action': 'review_assign'}
    problems = []
    # Damage in one field must not hide the rows, and a damaged container must
    # never be used: replace it with an empty one after reporting the code.
    try:
        retired = retired_ids(config)
    except ValueError:
        problems.append('review_assignment_invalid')
        retired = set()
    try:
        observations = stored_map(config, 'observations')
    except ValueError:
        problems.append('review_assignment_invalid')
        observations = {}
    try:
        rounds = stored_map(config, 'rounds')
    except ValueError:
        problems.append('review_assignment_invalid')
        rounds = {}
    bindings = config.get('bindings')
    if not isinstance(bindings, dict) or any(not isinstance(item, dict) for item in bindings.values()):
        problems.append('review_assignment_invalid')
    role_check = None
    executor = None
    executor_key = None
    if policy == 2:
        executor = execution['providerInstanceId']
        executor_key = provider_key(executor)
        malformed = any(assignment_problem(item) for item in assignments)
        if malformed:
            problems.append('review_assignment_invalid')
        else:
            role_check = review_role_check(task.get('profile'), assignments)
            problems.extend(item['code'] for item in role_check['violations'])
            providers = [provider_key(item['route']['providerInstanceId']) for item in assignments]
            if len(set(providers)) != len(providers):
                problems.append('reviewer_providers_not_distinct')
        assigned_ids = {item['id'] for item in assignments
                        if isinstance(item, dict) and isinstance(item.get('id'), str)}
        if retired & assigned_ids:
            problems.append('review_assignment_retired')
    session_owners, run_owners = {}, {}
    for assigned in assignments:
        if assignment_problem(assigned):
            continue  # The row below reports the malformed entry.
        ref = observations.get(assigned['id'], {}).get('launch')
        if ref:
            try:
                value = read_artifact(root, ref)
                for key, owners in (('childThreadId', session_owners), ('childRunId', run_owners)):
                    identifier = value.get(key)
                    if isinstance(identifier, str) and identifier:
                        owners.setdefault(identifier, set()).add(assigned['id'])
            except (OSError, ValueError, KeyError, TypeError, AttributeError):
                pass  # The owning row below reports the invalid artifact.
    for assignment in assignments:
        row = {'assignment_id': assignment.get('id') if isinstance(assignment, dict) else None,
               'state': 'assigned', 'passed': False, 'next_action': 'delegate_assigned_reviewer'}
        obs = observations.get(row['assignment_id'], {}) if isinstance(row['assignment_id'], str) else {}
        try:
            malformed = assignment_problem(assignment)
            if malformed:
                raise problem(malformed, 'Malformed assignment: id, role, parent_session_id and route.providerInstanceId must be strings')
            isolation_requirement(assignment)
            if policy == 2 and provider_key(assignment['route']['providerInstanceId']) == executor_key:
                raise problem('reviewer_route_not_independent',
                              'Reviewer ' + assignment['id'] + ' uses the executor provider ' + executor)
            for record in rounds.get(assignment['id'], {}).values():
                for kind in ('launch', 'result', 'worktree'):
                    if kind in record:
                        read_artifact(root, record[kind])
                for observation in record.get('conflicts', []) + record.get('supplemental', []):
                    read_artifact(root, observation['artifact'])
            if obs.get('conflicts'):
                raise ValueError('Conflicting observation recorded; start a new reviewer run')
            if 'launch' not in obs:
                rows.append(row)
                continue
            if any(obs[key].get('provenance') != 'agent_imported_t3_observation' for key in ('launch', 'result') if key in obs):
                raise ValueError('Synthetic fixture cannot establish a real review')
            launch = read_artifact(root, obs['launch'])
            row.update(state='launched', session_id=launch['childThreadId'], run_id=launch['childRunId'],
                       route={k: launch.get(k) for k in ('providerInstanceId', 'model', 'executedModel', 'options')},
                       next_action='read_reviewer_thread')
            if len(session_owners.get(launch['childThreadId'], set())) > 1 or len(run_owners.get(launch['childRunId'], set())) > 1:
                raise ValueError('Reviewer session or run shared by multiple assignments')
            if launch['childThreadId'] == assignment['parent_session_id']:
                raise ValueError('Reviewer session must differ from assigning session')
            route = assignment['route']
            if (provider_key(launch.get('providerInstanceId')) != provider_key(route['providerInstanceId'])
                    or (route.get('model') and launch.get('model') != route['model'])):
                raise ValueError('Launch route differs from agreed assignment')
            expected_options = option_map(route.get('options'))
            actual_options = option_map(launch.get('options'))
            if any(key not in actual_options or actual_options[key] != value for key, value in expected_options.items()):
                raise ValueError('Launch options differ from agreed assignment')
            if 'result' not in obs:
                rows.append(row)
                continue
            result = read_artifact(root, obs['result'])
            thread = result['thread']
            if thread.get('threadId') != launch['childThreadId'] or thread.get('parentThreadId') != assignment['parent_session_id'] or thread.get('relationshipToParent') != 'subagent':
                raise ValueError('Reviewer session/parent mismatch')
            if (provider_key(thread.get('providerInstanceId')) != provider_key(launch['providerInstanceId'])
                    or thread.get('model') != launch.get('model')):
                raise ValueError('Result route mismatch')
            runs = [r for r in result['recentRuns'] if r.get('runId') == launch['childRunId']]
            if len(runs) != 1:
                raise ValueError('Concrete reviewer run not found')
            run = runs[0]
            if (not launch.get('model')
                    or provider_key(run.get('providerInstanceId')) != provider_key(launch['providerInstanceId'])
                    or run.get('model') != launch['model']):
                raise ValueError('Concrete reviewer run route missing or mismatched')
            row['run_status'] = run.get('status')
            if run.get('status') != 'completed' or thread.get('activeRunId') or thread.get('latestRunId') != run['runId'] or thread.get('status') != 'completed':
                raise ValueError('Reviewer run is not completed/current')
            row.update(state='completed', next_action='obtain_review_verdict')
            messages = [x for x in result['items'] if x.get('type') == 'assistant_message' and x.get('runId') == run['runId']]
            if result.get('hasMore') or not messages or messages[-1].get('textTruncated'):
                raise ValueError('Full final reviewer result missing')
            text = messages[-1].get('text') or ''
            verdict = json.loads(text)
            if not isinstance(verdict, dict):
                raise ValueError('Final verdict must be an object')
            row.update(verdict=verdict.get('verdict'), reviewed_sha=verdict.get('reviewed_sha'),
                       full_result=thread.get('link'), executedModel=run.get('executedModel'))
            if verdict.get('assignment_id') != assignment['id']:
                raise ValueError('Result assignment mismatch')
            if not thread.get('link') or not text.strip():
                raise ValueError('Full result reference missing')
            if policy == 2:
                schema = report_schema(assignment)
            else:
                schema = REPORT_SCHEMA_LEGACY
            report = None
            if schema == REPORT_SCHEMA_FINDINGS:
                row['report_schema'] = REPORT_SCHEMA_FINDINGS
                report = review_report(verdict)
                row['report'] = report
            if not isinstance(head, str) or not re.fullmatch(r'[0-9a-f]{40,64}', head) or verdict.get('reviewed_sha') != head:
                row['state'] = 'stale'
                raise ValueError('Reviewed SHA does not match current HEAD')
            if verdict.get('verdict') != 'approved':
                raise ValueError('Review verdict is not approved')
            if report and report['unresolved_important']:
                raise problem('review_findings_unresolved',
                              'Important findings need a resolution reason or a new review: '
                              + ', '.join(report['unresolved_important']))
            row['isolation'] = isolation_check(root, assignment, launch, result, obs)
            row.update(state='approved', passed=True, next_action='none')
        except (OSError, ValueError, KeyError, TypeError, IndexError, AttributeError) as exc:
            code = getattr(exc, 'code', None)
            action = 'resolve_review_blocker'
            if code == 'review_findings_unresolved':
                action = 'resolve_review_findings'
            elif code == 'review_report_invalid':
                action = 'start_new_reviewer_run'
            row.update(problem=str(exc), next_action=action)
            if code:
                row['code'] = code
        rows.append(row)
    passed = all(x['passed'] for x in rows) and not problems
    next_action = 'none'
    if not passed:
        next_action = ('review_assign' if {'review_roles_incomplete', 'review_role_unknown',
                                           'review_assignment_invalid', 'review_assignment_retired',
                                           'reviewer_providers_not_distinct'} & set(problems)
                       else 'resolve_independent_reviews')
    if policy == 1:
        # Policy 1 keeps the exact pre-existing representation: completed process
        # steps store this dict and compare it with the recomputed value.
        return {'required': True, 'passed': passed, 'reviews': rows, 'next_action': next_action,
                'trust': 'Local validation of agent-imported MCP observations; no MCP authentication or direct MCP access',
                'fingerprint': fingerprint({k: config.get(k) for k in ('agreement', 'assignments', 'observations')})}
    return {'required': True, 'passed': passed, 'policy': policy,
            'execution_route': execution, 'role_check': role_check,
            'problems': sorted(set(problems)), 'reviews': rows, 'next_action': next_action,
            'trust': 'Local validation of agent-imported MCP observations and a locally recorded executor route; no MCP authentication or direct MCP access',
            # Supplemental delivery snapshots do not stale a completed process step.
            'fingerprint': fingerprint({'policy': policy,
                                        'executor': (execution or {}).get('providerInstanceId'),
                                        **{k: config.get(k) for k in ('agreement', 'assignments', 'observations')}})}
