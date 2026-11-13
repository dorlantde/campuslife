"""Bounded compact profile preflight; same runtime JAR, only generated seven-service test data."""
import datetime as dt
import json
import re
import subprocess
import threading
import time

from deploy_base import ROOT, Deployment, docker, initialize
from test_deploy_components import free_port
from test_deploy_showcase import main as showcase


LIMITS = {'app': 896, 'mysql': 768, 'nginx': 64, 'rating-redis': 128,
          'coupon-redis': 192, 'rabbitmq': 640, 'seaweed': 384}


def config_checks():
    checks = []
    for mode in ['base', 'components', 'showcase']:
        folder = initialize(free_port(), test=True, mode=mode)
        d = Deployment(folder)
        try:
            config = d.validate()
            assert d.resource_profile == 'standard'
            assert int(config['services']['app']['mem_limit']) == 1536 * 1024**2
            assert all('campuslife.base.resources' not in s['labels'] for s in config['services'].values())
            assert 'JAVA_TOOL_OPTIONS' not in config['services']['app']['environment']
            checks.append(mode + '-default-config-unchanged')
        finally:
            d.destroy_test()
    for mode in ['base', 'components']:
        try:
            initialize(test=True, mode=mode, resource_profile='compact4g')
        except ValueError:
            checks.append(mode + '-compact-profile-rejected-before-init')
        else:
            raise AssertionError('Invalid mode/profile accepted')
    (ROOT / '.cache/compact4g-config.json').write_text(json.dumps({'passed': True, 'checks': checks}, indent=2))
    print('PASS: ' + str(len(checks)) + ' default/profile config groups.', flush=True)


class Monitor:
    def __init__(self):
        self.stop = threading.Event()
        self.rows = {}
        self.samples = []
        self.errors = []
        self.transient_list_retries = 0

    def collect(self, d):
        totals = {'residentCurrentBytes': 0, 'allOwnedCurrentBytes': 0, 'runningOwnedContainers': 0}
        containers = None
        for _ in range(2):
            try:
                containers = d.resources('container', all_containers=False)
                break
            except RuntimeError as error:
                if not re.search(r'error: no such object: [0-9a-f]+\s*$', str(error)):
                    raise
                # A --rm CLI may disappear after the label-filtered list and before inspect.
                self.transient_list_retries += 1
        if containers is None:
            return False
        for c in containers:
            limits = c['HostConfig']
            labels = c['Config']['Labels']
            service = labels['com.docker.compose.service']
            oneoff = labels.get('com.docker.compose.oneoff', '').lower() == 'true'
            assert limits['Memory'] == LIMITS[service] * 1024**2
            assert limits['MemorySwap'] == limits['Memory']
            assert limits['CpusetCpus'] == '0,1'
            row = self.rows.setdefault(c['Id'], {'service': service, 'oneOff': oneoff, 'memoryLimitBytes': limits['Memory'],
                                               'peakBytes': None, 'kernelPeakAvailable': False, 'sampledPeakBytes': 0,
                                               'successfulSamples': 0, 'oom': 0, 'oomKill': 0, 'swapBytes': 0, 'restartCount': 0})
            row['restartCount'] = max(row['restartCount'], c['RestartCount'])
            assert not c['State']['OOMKilled']
            out = subprocess.run(['docker', 'exec', c['Id'], 'sh', '-c',
                'set -eu; cat /sys/fs/cgroup/memory.current; '
                'if [ -r /sys/fs/cgroup/memory.peak ]; then cat /sys/fs/cgroup/memory.peak; else echo UNAVAILABLE; fi; '
                'cat /sys/fs/cgroup/memory.events; cat /sys/fs/cgroup/memory.swap.current; '
                'cat /sys/fs/cgroup/cpuset.cpus.effective'], capture_output=True, text=True, timeout=8)
            # One-off clients may exit between inspect and exec. They remain identified in the record.
            if out.returncode:
                continue
            lines = out.stdout.splitlines()
            current = int(lines[0])
            peak = None if lines[1] == 'UNAVAILABLE' else int(lines[1])
            events = dict(line.split() for line in lines[2:-2])
            assert lines[-1] in ['0-1', '0,1']
            row['kernelPeakAvailable'] = peak is not None
            if peak is not None:
                row['peakBytes'] = max(row['peakBytes'] or 0, peak)
            row['sampledPeakBytes'] = max(row['sampledPeakBytes'], current)
            row['successfulSamples'] += 1
            row['oom'] = max(row['oom'], int(events['oom']))
            row['oomKill'] = max(row['oomKill'], int(events['oom_kill']))
            row['swapBytes'] = max(row['swapBytes'], int(lines[-2]))
            totals['allOwnedCurrentBytes'] += current
            totals['residentCurrentBytes'] += 0 if oneoff else current
            totals['runningOwnedContainers'] += 1
        self.samples.append(dict(at=dt.datetime.now(dt.timezone.utc).isoformat(), **totals))
        return True

    def loop(self, d):
        while not self.stop.is_set():
            try:
                self.collect(d)
            except BaseException as error:
                self.errors.append(type(error).__name__ + ': ' + str(error)[:200])
            self.stop.wait(8)

    def hook(self, stage, d, result):
        if stage == 'begin':
            self.d = d
            engine = json.loads(docker('info', '--format', '{{json .}}'))
            assert engine['CgroupVersion'] == '2' and engine['NCPU'] >= 2
            result['resourceProfile'] = 'compact4g'
            result['task'] = 'M06.1-D.3 compact4g prevalidation'
            result['hostEngine'] = {'cpus': engine['NCPU'], 'memoryBytes': engine['MemTotal'], 'cgroupVersion': engine['CgroupVersion']}
            result['validationScope'] = 'Same two logical CPUs and per-container no-swap memory caps; host is not a 4 GiB VM. No cloud/capacity claim.'
            result['pdfBytes'] = 20 * 1024**2
            self.thread = threading.Thread(target=self.loop, args=(d,), daemon=True)
            self.thread.start()
        elif stage == 'started':
            sql = d.compose('exec', '-T', 'mysql', 'sh', '-c',
                'MYSQL_PWD="$(cat /run/secrets/DB_PASSWORD)" exec mysql --protocol=TCP --host=127.0.0.1 '
                '--user=campuslife --database=campuslife_deploy --batch --skip-column-names',
                stdin='SELECT @@innodb_buffer_pool_size,@@max_connections,@@innodb_flush_log_at_trx_commit,@@performance_schema;')
            assert sql == '134217728\t40\t1\t1', sql
            result['checks'].append('mysql-resource-parameters-effective-before-business')
        elif stage == 'verified':
            self.stop.set()
            self.thread.join(60)
            assert not self.thread.is_alive()
            self.collect(d)
            assert not self.errors, self.errors
            assert set(r['service'] for r in self.rows.values() if not r['oneOff']) == set(LIMITS)
            assert all(r['successfulSamples'] > 0 for r in self.rows.values() if not r['oneOff'])
            assert all(r['oom'] == r['oomKill'] == r['swapBytes'] == r['restartCount'] == 0 for r in self.rows.values())
            result['checks'].append('actual-cgroups-have-two-shared-cpus-no-swap-and-no-oom')
            sql = d.compose('exec', '-T', 'mysql', 'sh', '-c',
                'MYSQL_PWD="$(cat /run/secrets/DB_PASSWORD)" exec mysql --protocol=TCP --host=127.0.0.1 '
                '--user=campuslife --database=campuslife_deploy --batch --skip-column-names',
                stdin='SELECT @@innodb_buffer_pool_size,@@max_connections,@@innodb_flush_log_at_trx_commit,@@performance_schema;')
            assert sql == '134217728\t40\t1\t1', sql
            result['checks'].append('mysql-budget-and-original-commit-durability-effective')
            original = d.resource_profile
            try:
                d.resource_profile = 'standard'
                try:
                    d.resources('container')
                except RuntimeError:
                    result['checks'].append('existing-compact-containers-refuse-profile-reinterpretation')
                else:
                    raise AssertionError('Profile change not rejected')
            finally:
                d.resource_profile = original
            from deploy_recovery import freeze
            try:
                freeze(d)
            except RuntimeError:
                assert not (d.folder/'recovery-freeze.json').exists()
                result['checks'].append('unverified-compact-cold-recovery-refused-before-freeze')
            else:
                raise AssertionError('Unverified recovery accepted')
        elif stage == 'end':
            self.stop.set()
            if hasattr(self, 'thread'):
                self.thread.join(60)
            result['resourceObservation'] = {'containerRecords': list(self.rows.values()), 'samples': self.samples,
                'errors': self.errors, 'transientContainerListRetries': self.transient_list_retries,
                'residentLimitSumBytes': sum(LIMITS.values()) * 1024**2,
                'maxSampledResidentCurrentBytes': max((s['residentCurrentBytes'] for s in self.samples), default=0),
                'maxSampledIncludingOneOffCurrentBytes': max((s['allOwnedCurrentBytes'] for s in self.samples), default=0),
                'note': 'Sequential cgroup samples include cache. Missing memory.peak is reported as null, not zero; sampledPeakBytes is sampled current usage only. Sampling may miss short-lived CLI containers; per-container peaks are not a simultaneous total.'}


def main():
    config_checks()
    monitor = Monitor()
    showcase(resource_profile='compact4g', lifecycle_hook=monitor.hook)


if __name__ == '__main__':
    main()
