"""No business dependencies. Synthetic B.1 jobs and a strictly scoped cleanup adapter."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time


def main():
    p = argparse.ArgumentParser(); p.add_argument('mode'); a = p.parse_args()
    folder = Path(os.environ['CASE_DIRECTORY'])
    assert folder.name == os.environ['CASE_ID'] and folder.parent.name == 'measurement-cases'
    assert not folder.is_symlink()
    work = folder/'work'
    if a.mode.startswith('cleanup'):
        if a.mode == 'cleanup-fail':
            return 9
        if a.mode == 'cleanup-hang':
            signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)
        if work.exists():
            assert work.resolve().parent == folder.resolve() and not work.is_symlink()
            shutil.rmtree(work)
        if a.mode != 'cleanup-no-proof':
            (folder/'cleanup-proof.json').write_text(json.dumps({'case_id': folder.name,
                'manifest_sha256': os.environ['CASE_MANIFEST_SHA256'], 'clean': True, 'resources_remaining': []}))
        return 0
    with (folder/'executions.txt').open('a') as stream:
        stream.write('one\n'); stream.flush(); os.fsync(stream.fileno())
    (work/'owned-marker').write_text('synthetic')
    if a.mode in ['timeout', 'orphan']:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        child = subprocess.Popen([sys.executable, '-I', '-c',
            'import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(60)'])
        (folder/'child-pid.json').write_text(json.dumps({'pid': child.pid}))
        if a.mode == 'orphan':
            return 0
        time.sleep(60)
    if a.mode == 'slow-success':
        time.sleep(1)
    if a.mode == 'ssh-slow-success':
        time.sleep(12)
    return 7 if a.mode == 'fail' else 0


if __name__ == '__main__':
    sys.exit(main())
