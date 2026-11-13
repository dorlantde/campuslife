"""B.2-A only: one owned MySQL + standalone tool, supervised by case_runner. No HTTP load."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time
import zipfile

RELEASE = Path('/home/campusdeploy/campuslife/releases/1cf0cb53a24a')
JAR_SHA = '6311c16a12b2a756353150cefd0ac9a2e7074194a8012204326c85df3df3bd12'
IMAGE_ID = 'sha256:7656904023ca692349cf4606576c06d271ea9cd9588ec7c0000c3fbcfae291d6'


def need(value, code):
    if not value:
        raise ValueError(code)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def scope():
    need(sys.platform == 'linux', 'LINUX_ONLY')
    folder = Path(os.environ['CASE_DIRECTORY'])
    need(folder.name == os.environ['CASE_ID'] and folder.parent.name == 'measurement-cases'
         and not folder.is_symlink(), 'CASE_SCOPE')
    sys.path.insert(0, str(RELEASE/'scripts'))
    need(sha(RELEASE/'target/campuslife-0.0.1-SNAPSHOT.jar') == JAR_SHA, 'FORMAL_JAR')
    source = json.loads((RELEASE/'source-manifest.json').read_text())
    need(len(source['files']) == 463 and all(sha(RELEASE/i['path']) == i['sha256'] for i in source['files']), 'FORMAL_SOURCE')
    return folder


def tool_identity(info, marker):
    need(info['Name'] == '/'+marker['toolName'], 'TOOL_NAME')
    labels = info['Config']['Labels']
    need(labels.get('campuslife.measurement.case') == marker['caseId']
         and labels.get('campuslife.measurement.instance') == marker['identity']
         and labels.get('campuslife.measurement.kind') == 'fixture', 'TOOL_LABELS')
    need(info['Image'] == IMAGE_ID, 'TOOL_IMAGE')
    if marker.get('toolId'):
        need(info['Id'] == marker['toolId'], 'TOOL_ID')


def cleanup(folder):
    from deploy_base import Deployment, docker
    marker_path = folder/'owned-instance.json'
    if marker_path.exists():
        marker = json.loads(marker_path.read_text())
        need(marker['caseId'] == folder.name, 'CLEANUP_CASE')
        d = Deployment(Path(marker['folder']))
        need(d.manifest['identity'] == marker['identity'] and d.manifest['kind'] == 'test', 'CLEANUP_INSTANCE')
        matches = docker('ps', '-aq', '--filter', 'name=^/'+marker['toolName']+'$').splitlines()
        for identity in matches:
            info = json.loads(docker('inspect', identity))[0]; tool_identity(info, marker)
            docker('rm', '-f', identity)
        if (d.folder/'secrets').exists():
            d.destroy_test()
        need(not any(d.resources(k) for k in ['container','volume','network']), 'CLEANUP_RESOURCES')
        need(not (d.folder/'secrets').exists(), 'CLEANUP_SECRETS')
    work = folder/'work'
    if work.exists():
        need(work.resolve().parent == folder.resolve() and not work.is_symlink(), 'WORK_SCOPE')
        shutil.rmtree(work)
    (folder/'cleanup-proof.json').write_text(json.dumps({'case_id': folder.name,
        'manifest_sha256': os.environ['CASE_MANIFEST_SHA256'], 'clean': True, 'resources_remaining': []}))


def job(folder):
    from deploy_base import Deployment, docker, initialize
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from measurement_fixture import attest, resource_identity
    need(docker('ps','-aq') == '' and docker('volume','ls','-q') == '', 'NO_EXISTING_TEST_RESOURCES')
    work = folder/'work'
    # Persist ownership before creating Docker resources. Init itself does not create resources.
    instance = initialize(18081, test=True, mode='showcase', observe=True, resource_profile='compact4g')
    d = Deployment(instance)
    marker = {'caseId':folder.name,'identity':d.manifest['identity'],'folder':str(instance),
              'toolName':'campuslife-measure-'+d.manifest['identity']}
    (folder/'owned-instance.json').write_text(json.dumps(marker))
    d.validate()
    d.compose('up','-d','--no-deps','mysql',timeout=90,log=folder/'mysql-start.log')
    end = min(time.monotonic()+90,time.monotonic()+max(0,float(os.environ['CASE_WORK_DEADLINE_UNIX'])-time.time()))
    while time.monotonic() < end:
        items = d.resources('container')
        if len(items)==1 and items[0]['State']['Health']['Status']=='healthy':break
        time.sleep(.5)
    permit = attest(d, folder.name, 'FORMAL')
    # Host-side negative checks mutate copies of inspected metadata, never Docker resources.
    mysql = d.resources('container')[0]
    rejected=[]
    for key in ['campuslife.base.identity','campuslife.base.kind','com.docker.compose.project','com.docker.compose.service','campuslife.base.resources']:
        wrong=json.loads(json.dumps(mysql));wrong['Config']['Labels'][key]='other'
        try:resource_identity(wrong,d.manifest,'container')
        except ValueError:rejected.append(key)
        else:raise AssertionError('HOST_GUARD_MISSING')
    volume=json.loads(docker('volume','inspect',permit['volumeName']))[0]
    wrong=json.loads(json.dumps(volume));wrong['Labels']['campuslife.base.kind']='base'
    try:resource_identity(wrong,d.manifest,'volume')
    except ValueError:rejected.append('volume-kind')
    else:raise AssertionError('VOLUME_GUARD_MISSING')
    for name in ['private','output','lib','classes']:(work/name).mkdir(mode=0o700)
    (work/'private/permit.json').write_text(json.dumps(permit))
    (work/'private/DB_PASSWORD').write_bytes((d.folder/'secrets/DB_PASSWORD').read_bytes())
    (work/'private/STUDENT_PASSWORD').write_text(secrets.token_hex(32))
    with zipfile.ZipFile(RELEASE/'target/campuslife-0.0.1-SNAPSHOT.jar') as jar:
        names=[n for n in jar.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')]
        need(len(names)==156,'DEPENDENCY_COUNT')
        for name in names:(work/'lib'/Path(name).name).write_bytes(jar.read(name))
    sources=list((folder/'input/src/measurement/java').rglob('*.java'))
    need(len(sources)==2 and all('com/campuslife' not in p.as_posix() for p in sources),'TOOL_SOURCES')
    command=['javac','-encoding','UTF-8','-cp',str(work/'lib/*'),'-d',str(work/'classes'),*map(str,sources)]
    with (folder/'compile.log').open('wb') as log:
        p=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=45)
    need(p.returncode==0,'TOOL_COMPILE')
    classes=list((work/'classes').rglob('*.class'))
    need(len(classes)==3 and all('campuslife/measurement/' in p.as_posix() for p in classes),'TOOL_CLASS_ALLOWLIST')
    args=['create','--name',marker['toolName'],'--network',d.manifest['project']+'_core',
          '--user',str(os.getuid())+':'+str(os.getgid()),'--read-only','--cap-drop','ALL',
          '--security-opt','no-new-privileges:true','--memory','256m','--memory-swap','256m',
          '--cpuset-cpus','0,1','--pids-limit','64','--tmpfs','/tmp:size=32m,mode=1777',
          '--label','campuslife.measurement.case='+folder.name,'--label','campuslife.measurement.instance='+marker['identity'],
          '--label','campuslife.measurement.kind=fixture','--entrypoint','java']
    for source,target in [(work/'classes','/tool/classes'),(work/'lib','/tool/lib'),
                          (RELEASE/'src/main/resources/db/migration','/tool/migrations'),(work/'private','/run/fixture')]:
        args += ['--mount','type=bind,source='+str(source)+',target='+target+',readonly']
    args += ['--mount','type=bind,source='+str(work/'output')+',target=/output',d.manifest['image'],
             '-XX:ActiveProcessorCount=2','-Xms32m','-Xmx128m','-cp','/tool/classes:/tool/lib/*',
             'campuslife.measurement.V24FixtureChecks','/run/fixture/permit.json']
    marker['toolId']=docker(*args);(folder/'owned-instance.json').write_text(json.dumps(marker))
    info=json.loads(docker('inspect',marker['toolId']))[0];tool_identity(info,marker)
    need(info['HostConfig']['Memory']==256*1024**2 and info['HostConfig']['MemorySwap']==256*1024**2
         and info['HostConfig']['ReadonlyRootfs'] and not info['HostConfig'].get('PortBindings'),'TOOL_LIMITS')
    docker('start',marker['toolId'])
    status=docker('wait',marker['toolId'],timeout=160)
    logs=docker('logs',marker['toolId']);(folder/'tool.log').write_text(logs)
    passwords=[p.read_text() for p in (work/'private').iterdir() if p.name!='permit.json']
    need(not any(password and password in logs for password in passwords),'SECRET_LOG_LEAK')
    need(status=='0','TOOL_CHECKS_FAILED')
    result=json.loads((work/'output/checks.json').read_text())
    expected=json.loads((work/'output/expected.json').read_text())
    need(result['passed'] and len(expected['students'])==180 and len(expected['publicShopIds'])==600,'EXPECTED_DATA')
    need(expected['counts']['service_order']==11000 and expected['counts']['order_event']==55000,'HISTORY_COUNTS')
    for name in ['checks.json','expected.json']:shutil.copyfile(work/'output'/name,folder/name)
    (folder/'host-checks.json').write_text(json.dumps({'hostGuardRefusals':rejected,'permit':permit,
        'toolImageId':info['Image'],'toolMountTargets':[m['Destination'] for m in info['Mounts']],
        'toolLimitBytes':info['HostConfig']['Memory'],'sourceFiles':{p.name:sha(p) for p in sources},
        'dependencyJars':len(names),'classFiles':len(classes),'credentialsInToolLogs':False}))
    docker('rm',marker['toolId'])


def main():
    parser=argparse.ArgumentParser();parser.add_argument('role',choices=['job','cleanup']);a=parser.parse_args()
    os.umask(0o077);folder=scope()
    (job if a.role=='job' else cleanup)(folder)


if __name__=='__main__':
    try:main()
    except Exception as error:
        print(json.dumps({'error':type(error).__name__,'code':'FIXTURE_OPERATION_FAILED'}));sys.exit(1)
