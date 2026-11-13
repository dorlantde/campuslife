"""Managed single-instance Compose deployment. Never reads development credentials/data."""
import argparse
import base64
import datetime as dt
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ROOT / 'deploy/base/compose.yaml'
BUILD_RECORD = ROOT / '.cache/deploy-base-build.json'
BASE_SECRETS = ['MYSQL_ROOT_PASSWORD', 'DB_PASSWORD', 'JWT_SECRET_BASE64']
COMPONENT_SECRETS = ['RATING_CACHE_PASSWORD', 'RATING_REDIS_OPS_PASSWORD', 'COUPON_REDIS_PASSWORD',
                     'COUPON_REDIS_OPS_PASSWORD', 'MESSAGING_PASSWORD', 'RABBIT_OPS_PASSWORD', 'RABBIT_ERLANG_COOKIE',
                     'RATING_REDIS_ACL', 'COUPON_REDIS_ACL', 'RABBIT_DEFINITIONS']


def write_secret(directory, name, value):
    path = directory / name
    path.write_text(value, encoding='utf-8')
    path.chmod(0o444)


def component_secrets(directory, namespace, vhost):
    passwords = {name: secrets.token_hex(32) for name in COMPONENT_SECRETS[:7]}
    for name, value in passwords.items():
        write_secret(directory, name, value)
    for prefix, user, pattern, commands in [
            ('RATING', 'rating_app', 'campuslife:' + namespace + ':rating:*',
             '+get +set +del +ping +hello +auth +select +quit +client|setname +client|setinfo'),
            ('COUPON', 'coupon_app', 'coupon:' + namespace + ':*',
             '+@read +@write +@scripting +@connection +info -flushdb -flushall -config -shutdown -acl')]:
        app_password = passwords['RATING_CACHE_PASSWORD' if prefix == 'RATING' else 'COUPON_REDIS_PASSWORD']
        ops_password = passwords[prefix + '_REDIS_OPS_PASSWORD']
        acl = 'user default off\nuser ' + user + ' on #' + hashlib.sha256(app_password.encode()).hexdigest() + ' ~' + pattern + ' ' + commands + '\n'
        acl += 'user redis_ops on #' + hashlib.sha256(ops_password.encode()).hexdigest() + ' ~* +@all\n'
        write_secret(directory, prefix + '_REDIS_ACL', acl)
    def rabbit_hash(password):
        salt = secrets.token_bytes(4)
        return base64.b64encode(salt + hashlib.sha256(salt + password.encode()).digest()).decode()
    resources = r'^campuslife\.(events\.v1|dead\.v1|notification(\.dead)?\.v1|coupon\.issue(\.dead)?\.v1)$'
    definitions = {'users': [
        {'name': 'campuslife_app', 'password_hash': rabbit_hash(passwords['MESSAGING_PASSWORD']), 'hashing_algorithm': 'rabbit_password_hashing_sha256', 'tags': []},
        {'name': 'campuslife_ops', 'password_hash': rabbit_hash(passwords['RABBIT_OPS_PASSWORD']), 'hashing_algorithm': 'rabbit_password_hashing_sha256', 'tags': ['management']}],
        'vhosts': [{'name': vhost}], 'permissions': [
            {'user': 'campuslife_app', 'vhost': vhost, 'configure': resources, 'write': resources, 'read': resources},
            {'user': 'campuslife_ops', 'vhost': vhost, 'configure': '.*', 'write': '.*', 'read': '.*'}]}
    write_secret(directory, 'RABBIT_DEFINITIONS', json.dumps(definitions))


def run(args, *, env=None, stdin=None, timeout=180, log=None):
    result = subprocess.run(args, cwd=ROOT, env=env, input=stdin, capture_output=True,
                            text=True, encoding='utf-8', errors='replace', timeout=timeout)
    if log:
        Path(log).write_text(result.stdout + result.stderr, encoding='utf-8')
    if result.returncode:
        if log:
            raise RuntimeError('Command failed; inspect local log: ' + str(log))
        raise RuntimeError('Command failed: ' + str(args[0]) + ' ' + str(args[1]) + '\n' + result.stderr[-1800:])
    return result.stdout.strip()


def docker(*args, **kwargs):
    return run(['docker', *args], **kwargs)


def build():
    folder = ROOT / '.cache/deploy-base-build'
    folder.mkdir(parents=True, exist_ok=True)
    maven = shutil.which('mvn.cmd') or shutil.which('mvn')
    if not maven:
        raise RuntimeError('Maven is required')
    java_version = run(['java', '--version'])
    if not re.match(r'(openjdk|java) 21(?:[.\s])', java_version):
        raise RuntimeError('Build requires Java 21')
    build_env = dict(os.environ)
    build_env.pop('MAVEN_ARGS', None)
    print('Packaging the application; business tests are not rerun by this image command.', flush=True)
    run([maven, '-B', '-DskipTests', 'package'], env=build_env, timeout=600, log=folder / 'maven.log')
    jar = ROOT / 'target/campuslife-0.0.1-SNAPSHOT.jar'
    with zipfile.ZipFile(jar) as archive:
        if any('test-classes/' in name or name.endswith('Mysql84CompatibilityIT.class') for name in archive.namelist()):
            raise RuntimeError('Unexpected test classes in runtime artifact')
    sha = hashlib.sha256(jar.read_bytes()).hexdigest()
    inputs = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in [
        '.dockerignore', 'deploy/base/Dockerfile', 'deploy/base/app-entrypoint.sh', 'deploy/base/app-health.sh']}
    infra_hash = hashlib.sha256(json.dumps(inputs, sort_keys=True).encode()).hexdigest()
    image = 'campuslife-base:m061a-' + sha[:12] + '-' + infra_hash[:12]
    print('Building a non-root Java 21 image from the allowlisted context.', flush=True)
    docker('build', '-f', 'deploy/base/Dockerfile', '--build-arg', 'JAR_SHA256=' + sha,
           '-t', image, '.', timeout=600, log=folder / 'image.log')
    record = {'image': image, 'imageId': docker('image', 'inspect', '--format', '{{.Id}}', image),
              'jarSha256': sha, 'inputs': inputs, 'builtAt': dt.datetime.now(dt.timezone.utc).isoformat()}
    BUILD_RECORD.write_text(json.dumps(record, indent=2), encoding='utf-8')
    print('Built image: ' + image)
    return record


def initialize(port=18080, docs=False, *, test=False, mode='base', demo_money=False, observe=False, resource_profile='standard'):
    if not 1024 <= port <= 65535:
        raise ValueError('Use an explicit unprivileged loopback port')
    if mode not in ['base', 'components', 'showcase'] or (demo_money and mode == 'base'):
        raise ValueError('Demo money requires an explicit non-base mode')
    if resource_profile not in ['standard', 'compact4g'] or (resource_profile == 'compact4g' and mode != 'showcase'):
        raise ValueError('compact4g requires an explicit new showcase instance')
    record = json.loads(BUILD_RECORD.read_text(encoding='utf-8'))
    if docker('image', 'inspect', '--format', '{{.Id}}', record['image']) != record['imageId']:
        raise RuntimeError('Application image identity changed; build again explicitly')
    identity = uuid.uuid4().hex
    folder = ROOT / ('.cache/deploy-base' if test else '.local/deploy/base') / identity
    folder.mkdir(parents=True, mode=0o700)
    secret_dir = folder / 'secrets'
    secret_dir.mkdir(mode=0o700)
    values = {'MYSQL_ROOT_PASSWORD': secrets.token_hex(32), 'DB_PASSWORD': secrets.token_hex(32),
              'JWT_SECRET_BASE64': base64.b64encode(secrets.token_bytes(64)).decode()}
    for name, value in values.items():
        write_secret(secret_dir, name, value)
    if observe:
        write_secret(secret_dir, 'MANAGEMENT_PASSWORD', secrets.token_hex(32))
    if mode in ['components', 'showcase']:
        component_secrets(secret_dir, 'deploy_' + identity, 'campuslife_' + identity)
    manifest = {'identity': identity, 'kind': 'test' if test else 'base', 'project': 'campuslife-base-' + identity,
                'port': port, 'apiDocs': bool(docs), 'database': 'campuslife_deploy',
                'image': record['image'], 'imageId': record['imageId'], 'jarSha256': record['jarSha256'],
                'mode': mode, 'demoMoney': bool(demo_money), 'observability': bool(observe)}
    if resource_profile != 'standard':
        manifest['resourceProfile'] = resource_profile
    if mode == 'showcase':
        from deploy_storage import prepare_storage, STORAGE_SECRETS
        try:
            manifest['storage'] = prepare_storage(folder, identity)
        except Exception as error:
            # No Docker resource has been created during Init; remove only generated partial secrets.
            for name in BASE_SECRETS + COMPONENT_SECRETS + STORAGE_SECRETS + ['MANAGEMENT_PASSWORD']:
                path = secret_dir / name
                if path.is_file():
                    path.chmod(0o600); path.unlink()
            if not list(secret_dir.iterdir()):
                secret_dir.rmdir()
            for name in ['trust-build-password', 'server.csr']:
                (folder / name).unlink(missing_ok=True)
            (folder / 'initialization-failure.json').write_text(json.dumps({'identity': identity, 'mode': mode, 'error': type(error).__name__, 'generatedSecretsRemoved': True}), encoding='utf-8')
            raise
    (folder / 'instance.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    return folder


class Deployment:
    def __init__(self, folder):
        self.folder = Path(folder).resolve()
        self.manifest = json.loads((self.folder / 'instance.json').read_text(encoding='utf-8'))
        m = self.manifest
        self.mode = m.get('mode', 'base')
        self.resource_profile = m.get('resourceProfile', 'standard')
        if self.resource_profile not in ['standard', 'compact4g'] or (self.resource_profile == 'compact4g' and self.mode != 'showcase'):
            raise RuntimeError('Unsupported deployment resource profile')
        self.data_identity = m.get('dataIdentity', m['identity'])
        if not re.fullmatch('[a-f0-9]{32}', self.data_identity):
            raise RuntimeError('Invalid persistent data identity')
        if self.data_identity != m['identity'] and (m['kind'] != 'test' or not re.fullmatch('[a-f0-9]{32}', m.get('restoreOf', ''))):
            raise RuntimeError('Preserved data identity is restricted to explicit isolated restores')
        if self.mode not in ['base', 'components', 'showcase'] or (m.get('demoMoney', False) and self.mode == 'base'):
            raise RuntimeError('Unsupported deployment mode/money combination')
        if not re.fullmatch('[a-f0-9]{32}', m['identity']) or m['project'] != 'campuslife-base-' + m['identity']:
            raise RuntimeError('Invalid managed instance identity')
        if m['kind'] not in ['base', 'test'] or m['database'] != 'campuslife_deploy':
            raise RuntimeError('Unexpected base deployment scope')
        expected = ROOT / ('.cache/deploy-base' if m['kind'] == 'test' else '.local/deploy/base') / m['identity']
        if self.folder != expected.resolve() or (self.folder / 'secrets').is_symlink():
            raise RuntimeError('Instance must be an owned generated directory')
        self.env = dict(os.environ)
        for key in list(self.env):
            if key.startswith(('COMPOSE_', 'DEPLOY_', 'SPRING_', 'STORAGE_', 'MANAGEMENT_')) or key in ['APP_IMAGE', 'SECRET_DIR', 'DB_NAME', 'HTTP_PORT', 'API_DOCS_ENABLED', 'DATA_ID', 'DATA_NAMESPACE', 'RABBIT_VHOST', 'DEMO_MONEY_ENABLED']:
                self.env.pop(key)
        self.env.update(DEPLOY_ID=m['identity'], DEPLOY_KIND=m['kind'], DB_NAME=m['database'],
                        APP_IMAGE=m['image'], SECRET_DIR=(self.folder / 'secrets').as_posix(),
                        HTTP_PORT=str(m['port']), API_DOCS_ENABLED=str(m['apiDocs']).lower(), DEPLOY_MODE=self.mode,
                        DATA_ID=self.data_identity, DATA_NAMESPACE='deploy_' + self.data_identity, RABBIT_VHOST='campuslife_' + self.data_identity,
                        DEMO_MONEY_ENABLED=str(m.get('demoMoney', False)).lower())
        if self.mode == 'showcase':
            storage = m.get('storage', {})
            if not re.fullmatch(r'[a-f0-9-]{36}', storage.get('namespace', '')) or storage.get('bucket') != 'campuslife-' + self.data_identity:
                raise RuntimeError('Invalid owned storage identity')
            self.env.update(STORAGE_NAMESPACE=storage['namespace'], STORAGE_BUCKET=storage['bucket'],
                            STORAGE_UPLOAD_TARGET='S3', STORAGE_ENDPOINT='https://seaweed:8333')

    def compose(self, *args, timeout=180, stdin=None, log=None):
        files = ['-f', str(COMPOSE)]
        if self.mode in ['components', 'showcase']:
            files += ['-f', str(COMPOSE.parent / 'components.yaml')]
        if self.mode == 'showcase':
            files += ['-f', str(COMPOSE.parent / 'storage.yaml')]
        if self.manifest.get('observability', False):
            files += ['-f', str(COMPOSE.parent / 'observability.yaml')]
        if self.resource_profile == 'compact4g':
            files += ['-f', str(COMPOSE.parent / 'compact4g.yaml')]
        if self.manifest.get('restoreOf'):
            files += ['-f', str(COMPOSE.parent / 'restore.yaml')]
        return run(['docker', 'compose', '--env-file', os.devnull, '--project-name', self.manifest['project'],
                    *files, *args], env=self.env, timeout=timeout, stdin=stdin, log=log)

    def resources(self, kind, *, all_containers=True):
        filter_value = 'label=campuslife.base.identity=' + self.manifest['identity']
        if kind == 'container':
            ids = docker('ps', '-aq' if all_containers else '-q', '--filter', filter_value).splitlines()
        else:
            ids = docker(kind, 'ls', '-q', '--filter', filter_value).splitlines()
        result = []
        for resource in ids:
            info = json.loads(docker('inspect', resource) if kind == 'container' else docker(kind, 'inspect', resource))[0]
            labels = info['Config']['Labels'] if kind == 'container' else info['Labels']
            if labels.get('campuslife.base.identity') != self.manifest['identity'] or labels.get('campuslife.base.kind') != self.manifest['kind'] or labels.get('com.docker.compose.project') != self.manifest['project']:
                raise RuntimeError('Resource ownership mismatch')
            if labels.get('campuslife.base.mode', 'base') != self.mode:
                raise RuntimeError('Mode differs from existing owned resources; in-place mode changes are unsupported')
            if kind == 'container' and labels.get('campuslife.base.resources', 'standard') != self.resource_profile:
                raise RuntimeError('Resource profile differs from existing containers; in-place changes are unsupported')
            result.append(info)
        return result

    def validate(self, *, allow_frozen=False):
        if (self.folder / 'recovery-freeze.json').exists() and not allow_frozen:
            raise RuntimeError('Instance is frozen for isolated recovery; mutation/maintenance is blocked')
        config = json.loads(self.compose('config', '--format', 'json'))
        expected = {'app', 'mysql', 'nginx'} | ({'rating-redis', 'coupon-redis', 'rabbitmq'} if self.mode != 'base' else set()) | ({'seaweed'} if self.mode == 'showcase' else set())
        if set(config['services']) != expected:
            raise RuntimeError('Services do not match the explicit deployment mode')
        if any(config['services'][s].get('ports') for s in expected - {'nginx'}):
            raise RuntimeError('Private service ports must not be published')
        app_env = config['services']['app']['environment']
        if self.resource_profile == 'compact4g':
            limits = {'app': 896, 'mysql': 768, 'nginx': 64, 'rating-redis': 128,
                      'coupon-redis': 192, 'rabbitmq': 640, 'seaweed': 384}
            for name, mib in limits.items():
                service = config['services'][name]
                if (int(service.get('mem_limit', 0)) != mib * 1024**2 or int(service.get('memswap_limit', 0)) != mib * 1024**2
                        or service.get('cpuset') != '0,1'
                        or service.get('labels', {}).get('campuslife.base.resources') != 'compact4g'):
                    raise RuntimeError('compact4g resource boundaries differ for ' + name)
            if app_env.get('JAVA_TOOL_OPTIONS') != '-Xms128m -Xmx448m -XX:ActiveProcessorCount=2':
                raise RuntimeError('compact4g JVM budget differs')
        if self.manifest.get('observability', False):
            if app_env.get('MANAGEMENT_ENABLED') != 'true' or app_env.get('MANAGEMENT_PORT') != '8081':
                raise RuntimeError('Private management configuration differs')
            if not re.fullmatch('[a-f0-9]{64}', (self.folder / 'secrets/MANAGEMENT_PASSWORD').read_text()):
                raise RuntimeError('Generated operations identity is required')
        enabled = 'true' if self.mode != 'base' else 'false'
        s3_enabled, target = ('true', 'S3') if self.mode == 'showcase' else ('false', 'LOCAL')
        if any(app_env.get(key) != enabled for key in ['RATING_CACHE_ENABLED', 'COUPON_REDIS_ENABLED', 'MESSAGING_ENABLED']) or app_env.get('FILE_S3_ENABLED') != s3_enabled or app_env.get('FILE_UPLOAD_TARGET') != target:
            raise RuntimeError('Application dependency flags differ from the selected mode')
        if self.mode == 'showcase':
            if app_env.get('FILE_S3_ENDPOINT') != 'https://seaweed:8333' or app_env.get('FILE_S3_NAMESPACE') != self.manifest['storage']['namespace']:
                raise RuntimeError('Owned HTTPS storage endpoint/identity differs')
            for name, sha in self.manifest['storage']['pkiSha256'].items():
                if hashlib.sha256((self.folder / 'secrets' / name).read_bytes()).hexdigest() != sha:
                    raise RuntimeError('Recorded public trust material changed; refusing implicit replacement')
        if app_env.get('MOCK_PAYMENT_ENABLED') != str(self.manifest.get('demoMoney', False)).lower():
            raise RuntimeError('Money switch differs from explicit instance configuration')
        ports = config['services']['nginx']['ports']
        if len(ports) != 1 or ports[0]['host_ip'] != '127.0.0.1' or int(ports[0]['published']) != self.manifest['port']:
            raise RuntimeError('Gateway must use the explicit loopback port')
        if docker('image', 'inspect', '--format', '{{.Id}}', self.manifest['image']) != self.manifest['imageId']:
            raise RuntimeError('Pinned local application image no longer matches')
        for kind in ['container', 'volume', 'network']:
            self.resources(kind)
        return config

    def start(self):
        self.validate()
        if self.manifest.get('restoreOf') and self.manifest.get('restoreState') != 'READY':
            raise RuntimeError('Restore is incomplete; application startup is forbidden')
        if self.mode == 'showcase':
            from deploy_storage import storage_request
            receipt = self.folder / 'storage-ready.json'
            if not receipt.exists() or json.loads(receipt.read_text()) != {k: self.manifest['storage'][k] for k in ['bucket', 'namespace']}:
                raise RuntimeError('Run explicit StorageInit for this new owned storage first')
            self.compose('up', '-d', '--wait', '--wait-timeout', '90', 'seaweed', timeout=120, log=self.folder / 'storage-start.log')
            status, marker = storage_request(self, 'GET', '_storage-id')
            if status != 200 or marker != self.manifest['storage']['namespace']:
                raise RuntimeError('Storage marker mismatch/unavailable; no identity overwritten')
        self.compose('up', '-d', '--wait', '--wait-timeout', '150', timeout=200, log=self.folder / 'start.log')
        containers = self.resources('container')
        services = [c['Config']['Labels'].get('com.docker.compose.service') for c in containers]
        expected = ['app', 'mysql', 'nginx'] + (['rating-redis', 'coupon-redis', 'rabbitmq'] if self.mode != 'base' else []) + (['seaweed'] if self.mode == 'showcase' else [])
        if sorted(services) != sorted(expected):
            raise RuntimeError('Expected one instance per base service')
        for c in containers:
            service = c['Config']['Labels']['com.docker.compose.service']
            published = [(k, v) for k, v in c['NetworkSettings']['Ports'].items() if v]
            if service != 'nginx' and published:
                raise RuntimeError('Unexpected private-service port publication')
            if service == 'nginx' and (len(published) != 1 or published[0][1] != [{'HostIp': '127.0.0.1', 'HostPort': str(self.manifest['port'])}]):
                raise RuntimeError('Actual gateway port does not match owned loopback')
        return self.status()

    def status(self):
        result = []
        for c in self.resources('container'):
            result.append({'service': c['Config']['Labels'].get('com.docker.compose.service'),
                           'state': c['State']['Status'], 'health': c['State'].get('Health', {}).get('Status')})
        return {'instance': str(self.folder), 'url': 'http://127.0.0.1:' + str(self.manifest['port']),
                'services': result, 'volumes': len(self.resources('volume')), 'mode': self.mode,
                'dockerDesktopAndWsl': 'not stopped by this command'}

    def stop(self):
        self.validate(allow_frozen=True)
        self.compose('stop', timeout=120, log=self.folder / 'stop.log')
        if self.resources('container', all_containers=False):
            raise RuntimeError('Some owned containers are still running; inspect before continuing')
        return self.status()

    def maintenance(self, kind, action, identity, version=None, upgraded=False, reason=None):
        self.validate()
        if kind == 'coupon':
            if action not in ['Inspect', 'Pause', 'Async', 'Resume', 'Sync'] or not re.fullmatch('[1-9][0-9]{0,18}', str(identity)):
                raise ValueError('Invalid campaign action/identity')
            if action != 'Inspect' and (version is None or not re.fullmatch('[0-9]{1,19}', str(version))):
                raise ValueError('Use the latest controlVersion from Inspect')
            env = {'COUPON_MAINTENANCE_ACTION': action, 'COUPON_CAMPAIGN_ID': str(identity),
                   'COUPON_ALL_INSTANCES_UPGRADED': str(upgraded).lower()}
            if version is not None:
                env['COUPON_CONTROL_VERSION'] = str(version)
        else:
            if action not in ['Inspect', 'Replay'] or not re.fullmatch('[a-f0-9]{32}', str(identity)):
                raise ValueError('Invalid message action/identity')
            if action == 'Replay' and (reason is None or not 1 <= len(reason.strip()) <= 300):
                raise ValueError('Replay needs a local reason of 1..300 code points')
            env = {'MESSAGE_ACTION': action, 'MESSAGE_ID': str(identity)}
            if reason is not None:
                env['MESSAGE_REASON'] = reason
        # Only maintenance inputs and the existing DB password are child environment values.
        # Body/reason never become Docker argv; values are forwarded by variable name only.
        saved_env = self.env.copy()
        try:
            self.env.update(env)
            arguments = [x for key in env for x in ['-e', key]]
            return self.compose('run', '--rm', '-T', '--no-deps', *arguments, 'app', kind + '-maintenance',
                                timeout=90, log=self.folder / (kind + '-maintenance.log'))
        finally:
            self.env = saved_env

    def admin(self, username, nickname, password):
        self.validate()
        secret_dir = self.folder / ('admin-' + uuid.uuid4().hex)
        secret_dir.mkdir(mode=0o755)
        # The private instance parent protects host access; the bind-mounted child must be traversable by UID 10001 even with umask 0077.
        secret_dir.chmod(0o755)
        try:
            for key, value in [('CAMPUSLIFE_ADMIN_USERNAME', username), ('CAMPUSLIFE_ADMIN_NICKNAME', nickname), ('CAMPUSLIFE_ADMIN_PASSWORD', password)]:
                path = secret_dir / key
                path.write_text(value, encoding='utf-8')
                path.chmod(0o444)
            output = self.compose('run', '--rm', '-T', '--no-deps', '-v', str(secret_dir) + ':/run/admin:ro', 'app', 'admin-init',
                                  timeout=90, log=self.folder / 'admin.log')
            if 'Created ACTIVE ADMIN business account' not in output:
                raise RuntimeError('Admin CLI did not confirm creation')
            return 'Created ADMIN via the explicit non-HTTP entrypoint; see local admin.log.'
        finally:
            for path in secret_dir.iterdir():
                path.chmod(0o600)
                path.unlink()
            secret_dir.rmdir()

    def destroy_test(self):
        if self.manifest['kind'] != 'test':
            raise RuntimeError('DestroyTest is restricted to generated test instances; ordinary Stop keeps volumes')
        self.validate(allow_frozen=True)
        self.compose('down', '--volumes', '--remove-orphans', timeout=120, log=self.folder / 'destroy.log')
        if any(self.resources(kind) for kind in ['container', 'volume', 'network']):
            raise RuntimeError('Owned test cleanup did not finish')
        from deploy_storage import STORAGE_SECRETS
        for name in BASE_SECRETS + (COMPONENT_SECRETS if self.mode != 'base' else []) + (STORAGE_SECRETS if self.mode == 'showcase' else []) + (['MANAGEMENT_PASSWORD'] if self.manifest.get('observability', False) else []):
            path = self.folder / 'secrets' / name
            path.chmod(0o600)
            path.unlink()
        (self.folder / 'secrets').rmdir()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('action', choices=['build', 'init', 'start', 'status', 'stop', 'admin', 'destroytest', 'coupon', 'message', 'storageinit', 'observe'])
    p.add_argument('--instance')
    p.add_argument('--port', type=int, default=18080)
    p.add_argument('--docs', action='store_true')
    p.add_argument('--mode', choices=['base', 'components', 'showcase'], default='base')
    p.add_argument('--demo-money', action='store_true')
    p.add_argument('--observe', action='store_true')
    p.add_argument('--resource-profile', choices=['standard', 'compact4g'], default='standard')
    p.add_argument('--metric')
    p.add_argument('--operation')
    p.add_argument('--id')
    p.add_argument('--version')
    p.add_argument('--all-instances-upgraded', action='store_true')
    args = p.parse_args()
    if args.action != 'init' and args.resource_profile != 'standard':
        p.error('--resource-profile is selected only at Init; existing instances keep their recorded profile')
    if args.action == 'build':
        build()
    elif args.action == 'init':
        print('New managed instance: ' + str(initialize(args.port, args.docs, mode=args.mode, demo_money=args.demo_money, observe=args.observe, resource_profile=args.resource_profile)))
    else:
        if not args.instance:
            p.error('--instance is required; use the generated directory printed by Init')
        deployment = Deployment(args.instance)
        if args.action == 'admin':
            print(deployment.admin(input('ADMIN username: '), input('ADMIN nickname: '), getpass.getpass('New ADMIN password (local only): ')))
        elif args.action == 'observe':
            from deploy_observability import observe
            print(json.dumps(observe(deployment, args.operation, args.metric), ensure_ascii=False, indent=2))
        elif args.action == 'storageinit':
            from deploy_storage import initialize_storage
            initialize_storage(deployment)
            print('Owned bucket/marker/policy verified using the explicit initializer identity.')
        elif args.action in ['coupon', 'message']:
            reason = input('Local replay reason: ') if args.action == 'message' and args.operation == 'Replay' else None
            print(deployment.maintenance(args.action, args.operation, args.id, args.version, args.all_instances_upgraded, reason))
        elif args.action == 'destroytest':
            deployment.destroy_test()
            print('Owned test resources and generated secret files removed.')
        else:
            print(json.dumps(getattr(deployment, args.action)(), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
