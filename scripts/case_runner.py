"""Linux-only, one-shot local case supervision. Trusted pinned Python jobs, no shell/daemon.

B.1 owns process sessions and case/work only. Docker/data cleanup needs a pinned
case-specific adapter in B.2; no generic prune, PID-only kill, or automatic retry.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
CASES = ROOT / '.cache/measurement-cases'
SELF = Path(__file__).resolve()
DONE = {'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELED', 'CLEANUP_FAILED', 'REJECTED'}


class Refused(ValueError):
    """Only fixed, non-sensitive reason codes may be published."""


def require(value, code):
    if not value:
        raise Refused(code)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False)+'\n').encode()


def regular(path):
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and not path.is_symlink(), 'REGULAR_FILE_REQUIRED')
    require(info.st_size <= 1024*1024, 'INPUT_TOO_LARGE')
    return path


def checked(base, relative):
    name = Path(relative)
    require(not name.is_absolute() and name.parts and '..' not in name.parts, 'UNSAFE_PATH')
    value = base
    for part in name.parts:
        value = value/part
        require(not value.is_symlink(), 'SYMLINK_REFUSED')
    require(value.resolve().is_relative_to(base.resolve()), 'PATH_ESCAPES_ROOT')
    return value


def read(path):
    return json.loads(regular(path).read_text(encoding='utf-8'))


def atomic(path, value):
    require(not path.is_symlink(), 'SYMLINK_REFUSED')
    fd, name = tempfile.mkstemp(prefix='.pending-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(encoded(value)); stream.flush(); os.fsync(stream.fileno())
        os.replace(name, path)
        parent = os.open(path.parent, os.O_DIRECTORY)
        try:
            os.fsync(parent)
        finally:
            os.close(parent)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def exclusive(path):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
    fd = os.open(path.parent, os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def supported():
    require(sys.platform == 'linux' and hasattr(os, 'pidfd_open')
            and hasattr(signal, 'pidfd_send_signal') and hasattr(os, 'WNOWAIT'), 'LINUX_PIDFD_REQUIRED')


def case_path(identity):
    require(re.fullmatch(r'case-[a-f0-9]{12,32}', identity), 'INVALID_CASE_ID')
    path = checked(ROOT, '.cache/measurement-cases')
    if not path.exists():
        path.mkdir(parents=True, mode=0o700)
    info = path.stat()
    require(info.st_uid == os.getuid() and info.st_mode & 0o077 == 0, 'PRIVATE_CASE_ROOT_REQUIRED')
    return checked(path, identity)


def load(identity):
    folder = case_path(identity)
    info = folder.lstat()
    require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
            and info.st_mode & 0o077 == 0, 'PRIVATE_CASE_REQUIRED')
    manifest = read(folder/'manifest.json')
    require(manifest['schema'] == 1 and manifest['case_id'] == identity, 'CASE_MISMATCH')
    return folder, manifest, digest(folder/'manifest.json')


def proc_identity(pid):
    try:
        path = Path('/proc')/str(pid)
        text = (path/'stat').read_text()
        fields = text[text.rfind(')')+2:].split()
        return {'pid': pid, 'uid': path.stat().st_uid, 'state': fields[0],
                'parent': int(fields[1]), 'group': int(fields[2]), 'session': int(fields[3]),
                'start_ticks': int(fields[19]),
                'boot': Path('/proc/sys/kernel/random/boot_id').read_text().strip()}
    except (FileNotFoundError, ProcessLookupError):
        return None


def same(actual, expected):
    return bool(actual and all(actual.get(k) == expected.get(k)
                for k in ['pid', 'uid', 'group', 'session', 'start_ticks', 'boot']))


def live(value):
    return value and value['state'] not in ['Z', 'X']


def signal_exact(expected, number):
    """pidfd pins the kernel process; recheck birth/UID/session after opening it."""
    try:
        fd = os.pidfd_open(expected['pid'])
    except ProcessLookupError:
        return False
    try:
        if not same(proc_identity(expected['pid']), expected):
            return False
        signal.pidfd_send_signal(fd, number)
        return True
    except ProcessLookupError:
        return False
    finally:
        os.close(fd)


def members(anchor):
    require(same(proc_identity(anchor['pid']), anchor), 'SESSION_ANCHOR_CHANGED')
    values = []
    for p in Path('/proc').iterdir():
        if p.name.isdecimal():
            value = proc_identity(int(p.name))
            if (live(value) and value['uid'] == anchor['uid'] and value['session'] == anchor['session']
                    and value['start_ticks'] >= anchor['start_ticks']):
                values.append(value)
    return values


def peek(process):
    # Keep the child (even after exit) unreaped so its PID/session anchor cannot be reused.
    return os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)


def remaining(launch):
    return min(launch['deadline_mono']-time.monotonic(), launch['deadline_unix']-time.time())


def verify_inputs(folder, manifest, origin=False):
    require(digest(SELF) == manifest['runner_sha256'], 'RUNNER_DRIFT')
    require(digest(Path(sys.executable).resolve()) == manifest['python_sha256'], 'PYTHON_DRIFT')
    for item in manifest['inputs']:
        path = checked(folder/'input', item['path'])
        require(digest(regular(path)) == item['sha256'], 'SNAPSHOT_DRIFT')
        if origin:
            require(digest(regular(checked(ROOT, item['path']))) == item['sha256'], 'SOURCE_DRIFT')


def prepare(spec):
    allowed = {'case_id', 'task', 'total_seconds', 'cleanup_seconds', 'script', 'args',
               'cleanup_script', 'cleanup_args', 'inputs', 'metadata'}
    require(set(spec) <= allowed, 'UNKNOWN_SPEC_FIELD')
    require(re.fullmatch(r'[A-Za-z0-9_.-]{1,80}', spec['task']), 'INVALID_TASK')
    require(type(spec['total_seconds']) is int and 5 <= spec['total_seconds'] <= 1200, 'INVALID_TOTAL_BUDGET')
    require(type(spec['cleanup_seconds']) is int
            and 3 <= spec['cleanup_seconds'] <= min(120, spec['total_seconds']-1), 'INVALID_CLEANUP_BUDGET')
    names = set(spec.get('inputs', [])) | {spec['script'], spec['cleanup_script']}
    require(len(names) <= 32, 'TOO_MANY_INPUTS')
    require(spec['script'].endswith('.py') and spec['cleanup_script'].endswith('.py'), 'PYTHON_SCRIPTS_ONLY')
    inputs = []
    for name in sorted(names):
        path = regular(checked(ROOT, name))
        inputs.append({'path': name, 'bytes': path.stat().st_size, 'sha256': digest(path)})
    for key in ['args', 'cleanup_args']:
        values = spec.get(key, [])
        require(isinstance(values, list) and len(values) <= 64
                and all(isinstance(v, str) and '\0' not in v and len(v) <= 2048 for v in values), 'INVALID_ARGV')
    require(len(encoded(spec)) <= 32768, 'SPEC_TOO_LARGE')
    folder = case_path(spec['case_id']); folder.mkdir(mode=0o700, exist_ok=False)
    for item in inputs:
        path = folder/'input'/item['path']; path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        path.write_bytes(checked(ROOT, item['path']).read_bytes()); path.chmod(0o400)
        require(digest(path) == item['sha256'], 'SOURCE_CHANGED_DURING_PREPARE')
    manifest = dict(spec, schema=1, inputs=inputs, runner_sha256=digest(SELF),
                    python_sha256=digest(Path(sys.executable).resolve()))
    (folder/'runner-source.py').write_bytes(SELF.read_bytes())
    (folder/'runner-source.py').chmod(0o400)
    require(digest(folder/'runner-source.py') == manifest['runner_sha256'], 'RUNNER_DRIFT')
    atomic(folder/'manifest.json', manifest)
    return status(spec['case_id'])


def start(identity):
    folder, manifest, sha = load(identity)
    if (folder/'launch.claim').exists():
        return status(identity)
    verify_inputs(folder, manifest, origin=True)
    try:
        exclusive(folder/'launch.claim')
    except FileExistsError:
        return status(identity)
    launch = {'case_id': identity, 'manifest_sha256': sha, 'launch_id': uuid.uuid4().hex,
              'boot': proc_identity(os.getpid())['boot'], 'started_unix': time.time(),
              'deadline_unix': time.time()+manifest['total_seconds'],
              'deadline_mono': time.monotonic()+manifest['total_seconds']}
    atomic(folder/'launch.json', launch)
    with (folder/'supervisor.log').open('xb') as log:
        subprocess.Popen([sys.executable, '-I', str(SELF), '_supervise', '--case', identity],
                         stdin=subprocess.DEVNULL, stdout=log, stderr=log, close_fds=True,
                         start_new_session=True, env={'PATH': os.defpath, 'LANG': 'C.UTF-8'}, cwd=folder)
    return status(identity)


def spawn(folder, manifest, launch, role):
    verify_inputs(folder, manifest)
    with (folder/(role+'.log')).open('xb') as log:
        env = {'PATH': os.defpath, 'LANG': 'C.UTF-8', 'CASE_LAUNCH_ID': launch['launch_id']}
        child = subprocess.Popen([sys.executable, '-I', str(SELF), '_exec', '--case', manifest['case_id'],
                                  '--role', role], stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                 env=env, cwd=folder, start_new_session=True, close_fds=True)
    anchor = proc_identity(child.pid)
    try:
        require(anchor and anchor['session'] == child.pid and anchor['group'] == child.pid, 'CHILD_IDENTITY_UNAVAILABLE')
        atomic(folder/(role+'-process.json'), anchor)
    except BaseException:
        if anchor:
            end_session(child, anchor, time.monotonic()+1)
        raise
    return child, anchor


def execute(identity, role):
    import resource
    folder, manifest, sha = load(identity); launch = read(folder/'launch.json')
    supervisor = read(folder/'supervisor.json')
    require(os.getppid() == supervisor['pid'] and same(proc_identity(os.getppid()), supervisor), 'UNEXPECTED_PARENT')
    require(os.environ.get('CASE_LAUNCH_ID') == launch['launch_id'], 'LAUNCH_ID_MISMATCH')
    verify_inputs(folder, manifest); exclusive(folder/(role+'.exec.claim'))
    key = 'script' if role == 'job' else 'cleanup_script'
    args = manifest.get('args' if role == 'job' else 'cleanup_args', [])
    argv = [sys.executable, '-I', str(checked(folder/'input', manifest[key])),
            *[str(folder) if v == '{case_dir}' else v for v in args]]
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_FSIZE, (16*1024**2, 16*1024**2))
    env = {'PATH': os.defpath, 'LANG': 'C.UTF-8', 'CASE_DIRECTORY': str(folder),
           'CASE_ID': identity, 'CASE_MANIFEST_SHA256': sha,
           'CASE_DEADLINE_UNIX': str(launch['deadline_unix']),
           'CASE_WORK_DEADLINE_UNIX': str(launch['deadline_unix']-manifest['cleanup_seconds'])}
    atomic(folder/(role+'-exec.json'), {'process': proc_identity(os.getpid()),
           'argv_sha256': hashlib.sha256(encoded(argv)).hexdigest(), 'script': manifest[key]})
    os.execve(sys.executable, argv, env)


def wait_task(child, launch, stop_mono, stopped, reserve=0):
    while peek(child) is None:
        if stopped[0] or time.monotonic() >= stop_mono or remaining(launch) <= reserve:
            return True
        time.sleep(min(0.05, max(0.001, stop_mono-time.monotonic())))
    return False


def end_session(child, anchor, stop_mono):
    values = members(anchor)
    orphaned = any(v['pid'] != child.pid for v in values)
    for value in values:
        signal_exact(value, signal.SIGTERM)
    grace = min(stop_mono, time.monotonic()+0.25)
    while time.monotonic() < grace and members(anchor):
        time.sleep(0.02)
    while time.monotonic() < stop_mono:
        values = members(anchor)
        if not values:
            break
        for value in values:
            signal_exact(value, signal.SIGKILL)
        time.sleep(0.02)
    clean = not members(anchor)
    code = None
    if clean and peek(child) is not None:
        code = child.wait(timeout=0.1)
    return {'processes_clean': clean, 'orphaned': orphaned, 'exit_code': code}


def supervise(identity):
    folder, manifest, sha = load(identity)
    exclusive(folder/'supervision.claim')
    launch = read(folder/'launch.json')
    require(launch['manifest_sha256'] == sha and launch['boot'] == proc_identity(os.getpid())['boot'], 'STALE_LAUNCH')
    atomic(folder/'supervisor.json', proc_identity(os.getpid()))
    stopped = [False]
    signal.signal(signal.SIGTERM, lambda *_: stopped.__setitem__(0, True))
    signal.signal(signal.SIGINT, lambda *_: stopped.__setitem__(0, True))
    outcome = 'REJECTED'; job = cleanup = None
    job_end = cleanup_end = None; problem = None
    try:
        require(remaining(launch) > manifest['cleanup_seconds'], 'WORK_BUDGET_EXHAUSTED')
        verify_inputs(folder, manifest); (folder/'work').mkdir(mode=0o700)
        job = spawn(folder, manifest, launch, 'job')
        work_end = time.monotonic()+max(0, remaining(launch)-manifest['cleanup_seconds'])
        timed_out = wait_task(job[0], launch, work_end, stopped, manifest['cleanup_seconds'])
        outcome = 'CANCELED' if stopped[0] else ('TIMED_OUT' if timed_out else 'FAILED')
    except BaseException as failure:
        problem = type(failure).__name__
    finally:
        finish_by = time.monotonic()+max(0, min(manifest['cleanup_seconds'], remaining(launch)))
        try:
            if job:
                job_end = end_session(*job, min(finish_by, time.monotonic()+1))
                if outcome not in ['TIMED_OUT', 'CANCELED']:
                    outcome = 'SUCCEEDED' if job_end['exit_code'] == 0 and not job_end['orphaned'] else 'FAILED'
            if remaining(launch) > 0.5 and finish_by-time.monotonic() > 0.5:
                cleanup = spawn(folder, manifest, launch, 'cleanup')
                wait_task(cleanup[0], launch, max(time.monotonic(), finish_by-0.5), [False])
                cleanup_end = end_session(*cleanup, finish_by)
        except BaseException as failure:
            problem = type(failure).__name__
        proof_valid = False
        try:
            proof = read(folder/'cleanup-proof.json')
            proof_valid = (proof == {'case_id': identity, 'manifest_sha256': sha,
                                    'clean': True, 'resources_remaining': []})
        except (OSError, ValueError):
            pass
        verified = bool(job_end and job_end['processes_clean'] and cleanup_end
                        and cleanup_end['processes_clean'] and cleanup_end['exit_code'] == 0
                        and not cleanup_end['orphaned'] and proof_valid and not os.path.lexists(folder/'work'))
        result = {'schema': 1, 'case_id': identity, 'manifest_sha256': sha,
                  'launch_id': launch['launch_id'], 'task': manifest['task'],
                  'state': outcome if verified else 'CLEANUP_FAILED', 'task_outcome': outcome,
                  'job': job_end, 'cleanup': cleanup_end, 'cleanup_verified': verified,
                  'cleanup_proof_valid': proof_valid, 'failure_type': problem,
                  'started_unix': launch['started_unix'], 'finished_unix': time.time(),
                  'deadline_unix': launch['deadline_unix']}
        atomic(folder/'receipt.json', result)


def receipt(folder, manifest, sha):
    value = read(folder/'receipt.json'); launch = read(folder/'launch.json')
    require(value['schema'] == 1 and value['case_id'] == manifest['case_id']
            and value['manifest_sha256'] == sha and value['launch_id'] == launch['launch_id']
            and value['task'] == manifest['task'] and value['state'] in DONE, 'RECEIPT_IDENTITY_MISMATCH')
    require(value['state'] != 'SUCCEEDED' or (value['task_outcome'] == 'SUCCEEDED'
            and value['cleanup_verified'] is True and value['job']['exit_code'] == 0
            and value['job']['processes_clean'] and not value['job']['orphaned']
            and value['cleanup']['exit_code'] == 0 and value['cleanup']['processes_clean']
            and not value['cleanup']['orphaned'] and value['cleanup_proof_valid']
            and not os.path.lexists(folder/'work')), 'INVALID_SUCCESS_RECEIPT')
    require(digest(regular(folder/'runner-source.py')) == manifest['runner_sha256'], 'RECEIPT_RUNNER_SNAPSHOT_DRIFT')
    for item in manifest['inputs']:
        require(digest(regular(checked(folder/'input', item['path']))) == item['sha256'], 'RECEIPT_INPUT_SNAPSHOT_DRIFT')
    return value


def status(identity):
    folder, manifest, sha = load(identity)
    value = {'case_id': identity, 'task': manifest['task'], 'manifest_sha256': sha}
    if (folder/'receipt.json').exists():
        result = receipt(folder, manifest, sha)
        return dict(value, state=result['state'], receipt_sha256=digest(folder/'receipt.json'))
    if not (folder/'launch.claim').exists():
        return dict(value, state='PREPARED')
    if not (folder/'launch.json').exists():
        return dict(value, state='UNKNOWN_LAUNCH')
    if not (folder/'supervisor.json').exists():
        return dict(value, state='STARTING_OR_UNKNOWN')
    supervisor = read(folder/'supervisor.json')
    healthy = same(proc_identity(supervisor['pid']), supervisor) and live(proc_identity(supervisor['pid']))
    return dict(value, state='RUNNING' if healthy else 'UNKNOWN_SUPERVISOR', supervisor_alive=bool(healthy))


def main():
    supported(); os.umask(0o077)
    parser = argparse.ArgumentParser(description='Pinned one-shot Linux case supervision; trusted scripts only.')
    parser.add_argument('operation', choices=['prepare', 'start', 'status', 'fetch', '_supervise', '_exec'])
    parser.add_argument('--case'); parser.add_argument('--spec', type=Path)
    parser.add_argument('--role', choices=['job', 'cleanup'])
    args = parser.parse_args()
    if args.operation == 'prepare':
        result = prepare(read(args.spec))
    elif args.operation == 'start':
        result = start(args.case)
    elif args.operation == 'status':
        result = status(args.case)
    elif args.operation == 'fetch':
        folder, manifest, sha = load(args.case)
        if not (folder/'receipt.json').exists():
            print(json.dumps(status(args.case))); return 2
        result = {'receipt': receipt(folder, manifest, sha), 'receipt_sha256': digest(folder/'receipt.json')}
    elif args.operation == '_supervise':
        supervise(args.case); return 0
    else:
        execute(args.case, args.role); return 0
    print(json.dumps(result, sort_keys=True))
    if args.operation == 'fetch':
        return 0 if result['receipt']['state'] == 'SUCCEEDED' else 1
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as failure:
        # No arbitrary exception message, argv, payload or environment is published.
        print(json.dumps({'error': type(failure).__name__,
                          'code': str(failure) if isinstance(failure, Refused) else 'CASE_OPERATION_REFUSED'}))
        sys.exit(2)
