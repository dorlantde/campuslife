"""D.6: reuse compact4g lifecycle on a separately verified native 2 CPU/4 GiB host."""
import json
import os
from pathlib import Path
import platform

from deploy_base import ROOT, docker
from test_deploy_compact4g import Monitor, config_checks
from test_deploy_showcase import main as showcase


def host_memory():
    memory = {line.split(':')[0]: int(line.split()[1]) * 1024
              for line in Path('/proc/meminfo').read_text().splitlines()
              if line.startswith(('MemTotal:', 'MemAvailable:', 'SwapTotal:', 'SwapFree:'))}
    vm = dict(line.split() for line in Path('/proc/vmstat').read_text().splitlines())
    memory['oomKillCount'] = int(vm['oom_kill'])
    return memory


def native_host():
    if platform.system() != 'Linux':
        raise RuntimeError('D.6 requires the separately verified native Linux ECS')
    kernel = Path('/proc/version').read_text()
    if 'microsoft' in kernel.lower() or 'wsl' in kernel.lower():
        raise RuntimeError('WSL does not satisfy this independent host acceptance')
    if Path('/.dockerenv').exists():
        raise RuntimeError('Run from the ECS host, not inside a container')
    endpoint = docker('context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}')
    if json.loads(endpoint) != 'unix:///var/run/docker.sock' or os.environ.get('DOCKER_HOST'):
        raise RuntimeError('Use the local rootful Engine verified in D.4')
    engine = json.loads(docker('info', '--format', '{{json .}}'))
    memory = host_memory()
    if (os.cpu_count() != 2 or engine['NCPU'] != 2 or engine['CgroupVersion'] != '2'
            or not 3 * 1024**3 <= memory['MemTotal'] <= 4 * 1024**3
            or abs(engine['MemTotal'] - memory['MemTotal']) > 32 * 1024**2):
        raise RuntimeError('Expected the verified two-CPU host with 3–4 GiB visible RAM and cgroup v2')
    return {'system': platform.system(), 'machine': platform.machine(), 'release': platform.release(),
            'cpus': os.cpu_count(), 'engineVersion': engine['ServerVersion'],
            'engineMemoryBytes': engine['MemTotal'], 'cgroupVersion': engine['CgroupVersion'],
            'memoryBefore': memory}


class CloudMonitor(Monitor):
    def __init__(self, host):
        super().__init__()
        self.host = host
        self.host_samples = []

    def collect(self, d):
        if super().collect(d) is not False:
            self.host_samples.append(dict(at=self.samples[-1]['at'], **host_memory()))

    def hook(self, stage, d, result):
        super().hook(stage, d, result)
        if stage == 'begin':
            result['task'] = 'M06.1-D.6 native ECS compact4g acceptance'
            result['validationScope'] = ('Single native ECS, two CPUs, actual visible RAM under 4 GiB; '
                                         'light transaction/restart acceptance, not capacity, HA or cold recovery.')
            result['nativeHost'] = self.host
            result['checks'].append('native-two-cpu-host-visible-ram-between-three-and-four-gib')
            (ROOT/'.cache/cloud-host-current.json').write_text(json.dumps({
                'instance': str(d.folder), 'identity': d.manifest['identity'],
                'kind': d.manifest['kind'], 'resourceProfile': d.resource_profile}, indent=2))
        elif stage == 'verified':
            after = host_memory()
            assert self.host_samples
            assert all(s['oomKillCount'] == self.host['memoryBefore']['oomKillCount'] for s in self.host_samples)
            assert after['oomKillCount'] == self.host['memoryBefore']['oomKillCount']
            result['checks'].append('host-oom-counter-unchanged-with-memory-and-swap-samples')
            # Values stay on the test host. Never print or persist the credential values in the report.
            names = ['MYSQL_ROOT_PASSWORD', 'DB_PASSWORD', 'JWT_SECRET_BASE64', 'MANAGEMENT_PASSWORD',
                     'RATING_CACHE_PASSWORD', 'RATING_REDIS_OPS_PASSWORD', 'COUPON_REDIS_PASSWORD',
                     'COUPON_REDIS_OPS_PASSWORD', 'MESSAGING_PASSWORD', 'RABBIT_OPS_PASSWORD',
                     'RABBIT_ERLANG_COOKIE', 'FILE_S3_SECRET_KEY', 'S3_ADMIN_SECRET_KEY']
            logs = d.compose('logs', '--no-color', timeout=30)
            assert all((d.folder/'secrets'/name).read_text() not in logs for name in names)
            result['checks'].append('service-logs-do-not-contain-generated-credential-values')
            result['generatedCredentialChecks'] = len(names)
        elif stage == 'end':
            result['hostObservation'] = {'before': self.host['memoryBefore'], 'after': host_memory(),
                'samples': self.host_samples,
                'minSampledAvailableBytes': min((s['MemAvailable'] for s in self.host_samples), default=None),
                'maxSampledHostSwapUsedBytes': max((s['SwapTotal'] - s['SwapFree'] for s in self.host_samples), default=None),
                'note': 'Host observations include unrelated host processes and caches. No fixed capacity/RTO claim.'}


def main():
    host = native_host()
    (ROOT/'.cache').mkdir(exist_ok=True)
    (ROOT/'.cache/cloud-host-preflight.json').write_text(json.dumps(host, indent=2))
    config_checks()
    monitor = CloudMonitor(host)
    showcase(resource_profile='compact4g', lifecycle_hook=monitor.hook)


if __name__ == '__main__':
    main()
