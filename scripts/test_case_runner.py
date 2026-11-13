"""Opt-in Linux integration checks; no Docker, business data, credentials or Maven."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
RUN = uuid.uuid4().hex
AREA = ROOT/'.cache/case-runner-tests'/RUN


def module(path):
    s = importlib.util.spec_from_file_location('case_runner_under_test', path)
    m = importlib.util.module_from_spec(s); s.loader.exec_module(m); return m


@unittest.skipUnless(sys.platform == 'linux', 'Real pidfd/session checks require Linux')
class CaseRunnerChecks(unittest.TestCase):
    def setUp(self):
        self.root = AREA/self._testMethodName
        (self.root/'scripts/diagnostics').mkdir(parents=True, mode=0o700)
        for name in ['scripts/case_runner.py', 'scripts/diagnostics/case_runner_fixture.py']:
            shutil.copyfile(ROOT/name, self.root/name)
        self.runner = self.root/'scripts/case_runner.py'; self.core = module(self.runner)
        self.identity = 'case-'+uuid.uuid4().hex[:16]
        self.folder = self.root/'.cache/measurement-cases'/self.identity
        self.prepared = False

    def call(self, *args, good=True):
        p = subprocess.run([sys.executable, '-I', str(self.runner), *args], capture_output=True, text=True, timeout=5)
        if good:
            self.assertEqual(p.returncode, 0, p.stdout+p.stderr)
        return p, json.loads(p.stdout)

    def prepare(self, job='success', cleanup='cleanup-ok', total=10, reserve=4):
        spec = {'case_id':self.identity, 'task':'M06.2-B.1', 'total_seconds':total,
                'cleanup_seconds':reserve, 'script':'scripts/diagnostics/case_runner_fixture.py',
                'args':[job], 'cleanup_script':'scripts/diagnostics/case_runner_fixture.py', 'cleanup_args':[cleanup]}
        source = self.root/'spec.json'; source.write_text(json.dumps(spec))
        self.call('prepare','--spec',str(source)); self.prepared = True

    def wait(self):
        end = time.monotonic()+15
        while time.monotonic()<end:
            _, value = self.call('status','--case',self.identity)
            if value['state'] in self.core.DONE:
                p, fetched = self.call('fetch','--case',self.identity,good=False)
                self.assertIn(p.returncode,[0,1]); return fetched['receipt']
            time.sleep(.05)
        self.fail('Short synthetic case did not finish')

    def run_case(self, **args):
        self.prepare(**args); self.call('start','--case',self.identity); return self.wait()

    def tearDown(self):
        # Negative tests deliberately retain work. Only test-owned paths are removed,
        # and failed receipts remain failed. Signal only validated test process sessions.
        if not self.folder.exists():
            return
        for role in ['job','cleanup']:
            p = self.folder/(role+'-process.json')
            if p.exists():
                anchor = json.loads(p.read_text())
                if self.core.same(self.core.proc_identity(anchor['pid']),anchor):
                    for value in self.core.members(anchor):
                        self.core.signal_exact(value,signal.SIGKILL)
        work = self.folder/'work'
        if os.path.lexists(work):
            self.assertEqual(work.parent.resolve(),self.folder.resolve())
            if work.is_symlink():work.unlink()
            else:shutil.rmtree(work)

    def test_success_verified_cleanup_and_frozen_sources(self):
        value = self.run_case()
        self.assertEqual(value['state'],'SUCCEEDED'); self.assertTrue(value['cleanup_verified'])
        self.assertEqual((self.folder/'executions.txt').read_text(),'one\n')
        self.assertTrue((self.folder/'runner-source.py').is_file())
        self.assertFalse(list(self.folder.glob('.pending-*')))

    def test_job_failure_is_not_transport_success(self):
        value = self.run_case(job='fail')
        self.assertEqual(value['state'],'FAILED'); self.assertEqual(value['job']['exit_code'],7)
        self.assertTrue(value['cleanup_verified'])

    def test_timeout_kills_term_resistant_parent_and_child(self):
        value = self.run_case(job='timeout',total=8,reserve=4)
        self.assertEqual(value['state'],'TIMED_OUT'); self.assertTrue(value['cleanup_verified'])
        pid = json.loads((self.folder/'child-pid.json').read_text())['pid']
        self.assertFalse(self.core.live(self.core.proc_identity(pid)))
        self.assertLess(value['finished_unix']-value['started_unix'],8.5)

    def test_orphaned_child_fails_job_even_if_parent_exits_zero(self):
        value = self.run_case(job='orphan')
        self.assertEqual(value['state'],'FAILED'); self.assertTrue(value['job']['orphaned'])
        self.assertTrue(value['cleanup_verified'])

    def test_supervisor_term_cancels_and_cleans(self):
        self.prepare(job='timeout',total=12,reserve=4)
        self.call('start','--case',self.identity)
        end=time.monotonic()+5
        while not (self.folder/'child-pid.json').exists() and time.monotonic()<end:time.sleep(.02)
        self.assertTrue((self.folder/'child-pid.json').exists())
        supervisor=json.loads((self.folder/'supervisor.json').read_text())
        self.assertTrue(self.core.signal_exact(supervisor,signal.SIGTERM))
        value=self.wait()
        self.assertEqual(value['state'],'CANCELED');self.assertTrue(value['cleanup_verified'])

    def test_zero_cleanup_exit_without_proof_is_not_success(self):
        value = self.run_case(cleanup='cleanup-no-proof')
        self.assertEqual(value['state'],'CLEANUP_FAILED'); self.assertEqual(value['cleanup']['exit_code'],0)

    def test_cleanup_failure_is_preserved(self):
        value = self.run_case(cleanup='cleanup-fail')
        self.assertEqual(value['task_outcome'],'SUCCEEDED'); self.assertEqual(value['state'],'CLEANUP_FAILED')

    def test_hanging_cleanup_is_bounded(self):
        value = self.run_case(cleanup='cleanup-hang',total=9,reserve=3)
        self.assertEqual(value['state'],'CLEANUP_FAILED'); self.assertTrue(value['cleanup']['processes_clean'])
        self.assertLess(value['finished_unix']-value['started_unix'],9.5)

    def test_concurrent_starts_and_repeated_fetch_execute_once(self):
        self.prepare(job='slow-success')
        with ThreadPoolExecutor(max_workers=2) as pool:
            values=list(pool.map(lambda _:self.call('start','--case',self.identity),range(2)))
        self.assertEqual(len(values),2); self.assertEqual(self.wait()['state'],'SUCCEEDED')
        before=(self.folder/'receipt.json').read_bytes()
        for _ in range(5):
            self.call('start','--case',self.identity); self.call('status','--case',self.identity)
            self.call('fetch','--case',self.identity)
        self.assertEqual((self.folder/'receipt.json').read_bytes(),before)
        self.assertEqual((self.folder/'executions.txt').read_text(),'one\n')

    def test_stdin_stdout_and_launcher_loss_does_not_restart_case(self):
        self.prepare(job='slow-success')
        # A long-lived transport parent starts the real detached supervisor, then dies.
        code='import subprocess,sys,time;subprocess.run(sys.argv[1:],check=True);time.sleep(60)'
        parent=subprocess.Popen([sys.executable,'-I','-c',code,sys.executable,'-I',str(self.runner),
                                 'start','--case',self.identity],stdin=subprocess.DEVNULL,
                                 stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
        anchor=self.core.proc_identity(parent.pid)
        try:
            end=time.monotonic()+5
            while not (self.folder/'supervisor.json').exists() and time.monotonic()<end:time.sleep(.02)
            self.assertTrue((self.folder/'supervisor.json').exists())
            self.core.signal_exact(anchor,signal.SIGTERM);parent.wait(timeout=2)
            self.assertEqual(self.wait()['state'],'SUCCEEDED')
            self.assertEqual((self.folder/'executions.txt').read_text(),'one\n')
        finally:
            if parent.poll() is None:self.core.signal_exact(anchor,signal.SIGKILL);parent.wait(timeout=2)
            parent.stdout.close();parent.stderr.close()

    def test_source_drift_refused_before_launch(self):
        self.prepare();p=self.root/'scripts/diagnostics/case_runner_fixture.py';p.write_text(p.read_text()+'\n# drift\n')
        process,value=self.call('start','--case',self.identity,good=False)
        self.assertEqual(process.returncode,2);self.assertEqual(value['code'],'SOURCE_DRIFT')
        self.assertFalse((self.folder/'launch.claim').exists())

    def test_snapshot_drift_refused_before_launch(self):
        self.prepare();p=self.folder/'input/scripts/diagnostics/case_runner_fixture.py';p.chmod(0o600);p.write_text('# drift\n')
        process,value=self.call('start','--case',self.identity,good=False)
        self.assertEqual(process.returncode,2);self.assertEqual(value['code'],'SNAPSHOT_DRIFT')
        self.assertFalse((self.folder/'executions.txt').exists())

    def test_path_escape_and_symlink_inputs_refused(self):
        self.prepare()
        spec=json.loads((self.root/'spec.json').read_text());spec['case_id']='case-'+uuid.uuid4().hex[:16]
        for name in ['../outside.py','scripts/link.py']:
            if name.endswith('link.py'):(self.root/name).symlink_to(self.runner)
            spec['script']=name;(self.root/'bad-spec.json').write_text(json.dumps(spec))
            process,_=self.call('prepare','--spec',str(self.root/'bad-spec.json'),good=False)
            self.assertEqual(process.returncode,2)

    def test_receipt_case_substitution_refused(self):
        self.run_case();p=self.folder/'receipt.json';value=json.loads(p.read_text());value['manifest_sha256']='0'*64;p.write_text(json.dumps(value))
        process,value=self.call('fetch','--case',self.identity,good=False)
        self.assertEqual(process.returncode,2);self.assertEqual(value['code'],'RECEIPT_IDENTITY_MISMATCH')

    def test_pid_birth_mismatch_never_signals_decoy(self):
        child=subprocess.Popen([sys.executable,'-I','-c','import time;time.sleep(60)'],start_new_session=True)
        value=self.core.proc_identity(child.pid)
        try:
            wrong=dict(value,start_ticks=value['start_ticks']+1)
            self.assertFalse(self.core.signal_exact(wrong,signal.SIGTERM));self.assertIsNone(child.poll())
        finally:
            self.core.signal_exact(value,signal.SIGKILL);child.wait(timeout=2)

    def test_pending_fetch_and_permanent_unknown_launch_never_start(self):
        self.prepare();self.core.exclusive(self.folder/'launch.claim')
        for _ in range(3):
            _,value=self.call('start','--case',self.identity);self.assertEqual(value['state'],'UNKNOWN_LAUNCH')
            process,_=self.call('fetch','--case',self.identity,good=False);self.assertEqual(process.returncode,2)
        self.assertFalse((self.folder/'executions.txt').exists())


def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    os.umask(0o077);AREA.mkdir(parents=True,mode=0o700)
    suite=unittest.defaultTestLoader.loadTestsFromTestCase(CaseRunnerChecks)
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    value={'testsRun':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),
           'skipped':len(result.skipped),'passed':result.wasSuccessful() and not result.skipped,
           'evidenceRoot':str(AREA),'workDirectoriesRemaining':[str(p) for p in AREA.glob('*/.cache/measurement-cases/*/work')]}
    args.output.write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps(value));return 0 if value['passed'] else 1


if __name__=='__main__':sys.exit(main())
