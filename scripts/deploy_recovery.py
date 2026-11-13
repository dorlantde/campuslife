"""Cold recovery rehearsal for generated test instances only; never mutates a real deployment."""
import datetime as dt
import csv
import hashlib
import json
import os
import re
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import uuid

from deploy_base import ROOT, Deployment, docker, initialize

VOLUMES = {'mysql-data','files','coupon-data','rabbit-data','seaweed-data'}
CONFIG = ['compose.yaml','components.yaml','storage.yaml','observability.yaml','restore.yaml','Dockerfile',
          'app-entrypoint.sh','app-health.sh','mysql-init.sh','rabbit-entrypoint.sh',
          'rabbitmq.conf','redis-rating.conf','redis-coupon.conf','nginx.conf']


def secure_directory(folder):
    """A backup contains signing keys; Windows chmod alone does not provide an NTFS ACL."""
    folder=Path(folder).resolve()
    if folder.parent != (ROOT/'.cache/deploy-recovery').resolve():
        raise RuntimeError('Only a newly generated recovery directory can be protected')
    if os.name=='nt':
        raw=subprocess.run(['whoami','/user','/fo','csv','/nh'],capture_output=True,text=True,check=True).stdout
        sid=next(csv.reader([raw.strip()]))[1]
        # Directory inheritance flags must NOT be applied to file ACEs: Windows can discard them.
        # Privileged local administrators/owners remain explicit, without broad Users/Everyone grants.
        for entry in [folder,*folder.rglob('*')]:
            if entry.is_symlink():raise RuntimeError('Unexpected backup link during ACL protection')
            flags='(OI)(CI)F' if entry.is_dir() else 'F'
            subprocess.run(['icacls',str(entry),'/inheritance:r','/grant:r',
                '*'+sid+':'+flags,'*S-1-5-18:'+flags,'*S-1-5-32-544:'+flags,'*S-1-3-4:'+flags],
                capture_output=True,check=True)
        return 'NTFS owner/current user, SYSTEM and local Administrators; no broad user grants'
    folder.chmod(0o700)
    return 'POSIX private directory 0700'


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            h.update(block)
    return h.hexdigest()


def volumes(deployment):
    result = {v['Labels']['com.docker.compose.volume']:v['Name'] for v in deployment.resources('volume')}
    if set(result) != VOLUMES:
        raise RuntimeError('Expected exactly the five owned persistent volumes')
    return result


def helper_args(deployment, volume, *, writing=False):
    if deployment.manifest['kind'] != 'test' or volume not in volumes(deployment).values():
        raise RuntimeError('Recovery helper accepts only exact owned test volumes')
    args = ['docker','run','--rm','-i','--network','none','--read-only','--user','0:0',
            '--cap-drop','ALL','--cap-add','DAC_OVERRIDE',
            '--label','campuslife.base.kind=test','--label','campuslife.base.identity='+deployment.manifest['identity'],
            '--label','com.docker.compose.project='+deployment.manifest['project'],
            '--label','campuslife.base.mode='+deployment.mode]
    if writing:
        args += ['--cap-add','CHOWN','--cap-add','FOWNER']
    args += ['--mount','type=volume,source='+volume+',target=/data'+('' if writing else ',readonly'),
             '--entrypoint','tar',deployment.manifest['image']]
    return args


def logical_digest(deployment):
    raw = deployment.compose('exec','-T','mysql','sh','-c',
        'MYSQL_PWD="$(cat /run/secrets/DB_PASSWORD)" exec mysqldump --protocol=TCP --host=127.0.0.1 '
        '--user=campuslife --compact --skip-comments --skip-dump-date --skip-triggers --skip-lock-tables '
        '--no-tablespaces --set-gtid-purged=OFF --order-by-primary --hex-blob --skip-extended-insert campuslife_deploy',timeout=40)
    return hashlib.sha256(raw.encode()).hexdigest()


def freeze(deployment):
    if deployment.resource_profile != 'standard':
        raise RuntimeError('Cold recovery rehearsal is validated only for the standard resource profile')
    deployment.validate()
    if deployment.manifest['kind'] != 'test' or deployment.mode != 'showcase':
        raise RuntimeError('Only new generated showcase test data is authorized')
    fd = os.open(deployment.folder/'recovery-freeze.json', os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o600)
    with os.fdopen(fd,'w') as out:
        json.dump({'pid':os.getpid(),'at':dt.datetime.now(dt.timezone.utc).isoformat()},out)
    deployment.compose('stop','nginx','app',timeout=90,log=deployment.folder/'freeze.log')
    if any(c['Config']['Labels']['com.docker.compose.service'] in ['app','nginx'] for c in deployment.resources('container',all_containers=False)):
        raise RuntimeError('Writers did not stop; freeze retained')


def backup(deployment):
    if deployment.resource_profile != 'standard':
        raise RuntimeError('Cold recovery rehearsal is validated only for the standard resource profile')
    deployment.validate(allow_frozen=True)
    if deployment.manifest['kind'] != 'test' or not (deployment.folder/'recovery-freeze.json').exists():
        raise RuntimeError('Freeze an owned test source first')
    expected_sql = logical_digest(deployment)
    mode = deployment.compose('exec','-T','mysql','sh','-c',
        'MYSQL_PWD="$(cat /run/secrets/MYSQL_ROOT_PASSWORD)" exec mysql --protocol=socket --user=root --batch --skip-column-names',
        stdin='SET GLOBAL innodb_fast_shutdown=0; SELECT @@GLOBAL.innodb_fast_shutdown;')
    if mode != '0':
        raise RuntimeError('MySQL slow shutdown was not selected')
    deployment.compose('stop',timeout=120,log=deployment.folder/'backup-stop.log')
    if deployment.resources('container',all_containers=False):
        raise RuntimeError('Persistent components still running; no archive taken')
    mysql = next(c for c in deployment.resources('container') if c['Config']['Labels'].get('com.docker.compose.service')=='mysql')
    if mysql['State']['ExitCode'] != 0:
        raise RuntimeError('MySQL did not stop cleanly; cold archive refused')
    folder = ROOT/'.cache/deploy-recovery'/uuid.uuid4().hex
    folder.mkdir(parents=True,mode=0o700)
    protection=secure_directory(folder)
    private = folder/'private';private.mkdir(mode=0o700)
    secrets = private/'secrets';secrets.mkdir(mode=0o700)
    for p in (deployment.folder/'secrets').iterdir():
        if not p.is_file() or p.is_symlink():
            raise RuntimeError('Unexpected source secret entry')
        shutil.copy2(p,secrets/p.name)
    for name in ['instance.json','storage-ready.json']:
        shutil.copy2(deployment.folder/name,private/name)
    config = private/'config';config.mkdir(mode=0o700)
    for name in CONFIG:
        shutil.copy2(ROOT/'deploy/base'/name,config/name)
    shutil.copy2(ROOT/'target/campuslife-0.0.1-SNAPSHOT.jar',private/'app.jar')
    if digest(private/'app.jar') != deployment.manifest['jarSha256']:
        raise RuntimeError('Runtime JAR differs from the frozen deployment')
    archives = {}
    for role, volume in sorted(volumes(deployment).items()):
        path = private/(role+'.tgz')
        with path.open('xb') as out:
            result = subprocess.run(helper_args(deployment,volume)+['--numeric-owner','--one-file-system','-czpf','-','-C','/data','.'],
                                    stdout=out,stderr=subprocess.PIPE,timeout=180)
        path.chmod(0o600)
        if result.returncode:
            raise RuntimeError('Cold archive failed for '+role+': '+result.stderr.decode(errors='replace')[-600:])
        archives[role]={'file':path.name,'sha256':digest(path),'bytes':path.stat().st_size}
    inventory = {'schema':1,'protection':protection,'source':deployment.manifest,'sourceVolumes':volumes(deployment),'archives':archives,'logicalSqlSha256':expected_sql,
                 'files':{p.relative_to(private).as_posix():digest(p) for p in private.rglob('*') if p.is_file()}}
    (folder/'inventory.json').write_text(json.dumps(inventory,indent=2),encoding='utf-8')
    (folder/'complete.json').write_text(json.dumps({'inventorySha256':digest(folder/'inventory.json')}),encoding='utf-8')
    secure_directory(folder)
    return folder


def verify_backup(folder):
    folder=Path(folder).resolve()
    if folder.parent != (ROOT/'.cache/deploy-recovery').resolve() or folder.is_symlink():
        raise RuntimeError('Only this workspace generated recovery backup is accepted')
    complete=json.loads((folder/'complete.json').read_text())
    if digest(folder/'inventory.json') != complete['inventorySha256']:
        raise RuntimeError('Backup inventory checksum mismatch')
    inventory=json.loads((folder/'inventory.json').read_text())
    if inventory.get('schema') != 1 or inventory.get('source',{}).get('kind') != 'test' or not re.fullmatch('[a-f0-9]{32}',inventory.get('source',{}).get('identity','')):
        raise RuntimeError('Only generated test backup identity/schema is accepted')
    if set(inventory['archives']) != VOLUMES:
        raise RuntimeError('Incomplete five-volume backup')
    for relative, expected in inventory['files'].items():
        path=PurePosixPath(relative)
        if path.is_absolute() or '..' in path.parts or not (folder/'private'/relative).is_file() or digest(folder/'private'/relative)!=expected:
            raise RuntimeError('Backup file checksum/path mismatch')
    for role, entry in inventory['archives'].items():
        if role not in VOLUMES or entry['file'] != role+'.tgz':
            raise RuntimeError('Unexpected archive role')
        with tarfile.open(folder/'private'/entry['file'],'r:gz') as archive:
            for member in archive:
                name=PurePosixPath(member.name)
                if name.is_absolute() or '..' in name.parts or member.isdev() or member.isfifo() or member.islnk():
                    raise RuntimeError('Unsafe archive member')
                if member.issym() and not (role=='mysql-data' and str(name)=='mysql.sock'
                                          and member.linkname=='/var/run/mysqld/mysqld.sock'):
                    raise RuntimeError('Unexpected archive symlink')
    return inventory


def restore(folder, port):
    inventory=verify_backup(folder);private=Path(folder)/'private';source=inventory['source']
    for name in CONFIG:
        if digest(ROOT/'deploy/base'/name)!=inventory['files'].get('config/'+name):
            raise RuntimeError('Deployment configuration differs; no implicit upgrade during restore')
    target_folder=initialize(port,docs=source['apiDocs'],test=True,mode='showcase',demo_money=source['demoMoney'],observe=source['observability'])
    target=Deployment(target_folder)
    try:
        if target.manifest['imageId'] != source['imageId'] or target.manifest['jarSha256'] != source['jarSha256']:
            raise RuntimeError('Exact original runtime artifact is required')
        m=target.manifest
        m.update(dataIdentity=source.get('dataIdentity',source['identity']),restoreOf=source['identity'],
                 restoreState='PREPARING',storage=source['storage'])
        for p in (private/'secrets').iterdir():
            dest=target.folder/'secrets'/p.name;dest.chmod(0o600);dest.write_bytes(p.read_bytes());dest.chmod(0o444)
        shutil.copy2(private/'storage-ready.json',target.folder/'storage-ready.json')
        (target.folder/'instance.json').write_text(json.dumps(m,indent=2),encoding='utf-8')
        target=Deployment(target.folder)
        target.validate()
        target.compose('create','--no-build',timeout=90,log=target.folder/'restore-create.log')
        target_volumes=volumes(target)
        if set(target_volumes.values()) & set(inventory['sourceVolumes'].values()):
            raise RuntimeError('Restore target must not reuse source volumes')
        for role, volume in sorted(target_volumes.items()):
            args=helper_args(target,volume,writing=True)
            # A second helper inspects only the exact new volume. Never empties or overwrites an existing volume.
            probe=args.copy();probe[probe.index('tar')]='sh'
            checked=subprocess.run(probe+['-c','test -z "$(find /data -mindepth 1 -print -quit)"'],capture_output=True,timeout=20)
            if checked.returncode:
                raise RuntimeError('Target volume is not empty: '+role)
            with (private/inventory['archives'][role]['file']).open('rb') as data:
                result=subprocess.run(args+['--numeric-owner','-xzpf','-','-C','/data'],stdin=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180)
            if result.returncode:
                raise RuntimeError('Restore extraction failed for '+role)
        m['restoreState']='READY';(target.folder/'instance.json').write_text(json.dumps(m,indent=2),encoding='utf-8')
        return Deployment(target.folder)
    except BaseException:
        try:target.destroy_test()
        finally:raise
