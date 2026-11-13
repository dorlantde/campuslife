"""C.2 standalone private-store observation checks, supervised by the original case runner."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

sys.path.insert(0,str(Path(__file__).resolve().parent))
from measurement_resources import ResourceScope, atomic, docker, no_links, need, sha, IMAGE_ID


def case():
    p=Path(os.environ['CASE_DIRECTORY']);need(p.name==os.environ['CASE_ID'],'CASE_SCOPE');return p


def read_info(d,service,password):
    raw=d.compose('exec','-T',service,'sh','-c','REDISCLI_AUTH="$(cat /run/secrets/'+password+')"; export REDISCLI_AUTH; exec redis-cli --user redis_ops --raw INFO server',timeout=10)
    fields=dict(line.split(':',1) for line in raw.splitlines() if ':' in line)
    need(len(fields.get('run_id',''))==40,'REDIS_RUN_ID');return fields['run_id']


def ready(d):
    end=time.monotonic()+90
    while time.monotonic()<end:
        infos=d.resources('container');services={i['Config']['Labels'].get('com.docker.compose.service'):i for i in infos}
        if all(services.get(s,{}).get('State',{}).get('Health',{}).get('Status')=='healthy' for s in ['mysql','rating-redis','coupon-redis']) and services.get('rabbitmq',{}).get('State',{}).get('Running'):
            broker=services['rabbitmq'];network=d.manifest['project']+'_core';address=broker['NetworkSettings']['Networks'][network]['IPAddress']
            password=(d.folder/'secrets/RABBIT_OPS_PASSWORD').read_text().strip()
            request=urllib.request.Request('http://'+address+':15672/api/overview',headers={'Authorization':'Basic '+base64.b64encode(('campuslife_ops:'+password).encode()).decode()})
            try:
                with urllib.request.urlopen(request,timeout=2) as response:
                    if response.status==200:return {'rabbitDockerHealth':broker['State'].get('Health',{}).get('Status'),'httpReady':True}
            except Exception:pass
        time.sleep(1)
    raise RuntimeError('PRIVATE_COMPONENTS_NOT_READY')


def job():
    folder=case();scope=ResourceScope(folder,'facts');scope.begin();d=scope.initialize()
    need(not docker('ps','-aq') and not docker('volume','ls','-q'),'NO_EXISTING_RESOURCES')
    scope.compose('up','-d','--no-deps','mysql','rating-redis','coupon-redis','rabbitmq',timeout=90,log=folder/'components-start.log')
    readiness=ready(d)
    from measurement_fixture import attest
    permit=attest(d,folder.name,'FORMAL');permit.update(namespace='deploy_'+d.manifest['identity'],vhost='campuslife_'+d.manifest['identity'],
        couponRun=read_info(d,'coupon-redis','COUPON_REDIS_OPS_PASSWORD'),ratingRun=read_info(d,'rating-redis','RATING_REDIS_OPS_PASSWORD'))
    work=folder/'work'
    for name in ['private','output','lib','classes']:(work/name).mkdir(mode=0o700)
    private=work/'private';atomic(private/'permit.json',permit)
    metrics_password=secrets.token_hex(32);(private/'METRICS_DB_PASSWORD').write_text(metrics_password)
    (private/'STUDENT_PASSWORD').write_text(secrets.token_hex(32))
    for name in ['DB_PASSWORD','RATING_REDIS_OPS_PASSWORD','COUPON_REDIS_OPS_PASSWORD','RABBIT_OPS_PASSWORD']:
        (private/name).write_bytes((d.folder/'secrets'/name).read_bytes())
    # Bootstrap only this new database's technical SELECT account; never expand the app account.
    sql="CREATE USER 'campuslife_measure'@'%' IDENTIFIED WITH caching_sha2_password BY '"+metrics_password+"';\nGRANT SELECT ON campuslife_deploy.* TO 'campuslife_measure'@'%';\nGRANT SELECT ON performance_schema.* TO 'campuslife_measure'@'%';"
    d.compose('exec','-T','mysql','sh','-c','MYSQL_PWD="$(cat /run/secrets/MYSQL_ROOT_PASSWORD)" exec mysql --protocol=socket --user=root --batch --skip-column-names',stdin=sql,timeout=15)
    release=Path('/home/campusdeploy/campuslife/releases/1cf0cb53a24a')
    with zipfile.ZipFile(release/'target/campuslife-0.0.1-SNAPSHOT.jar') as jar:
        jars=[n for n in jar.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')];need(len(jars)==156,'DEPENDENCY_COUNT')
        for name in jars:(work/'lib'/Path(name).name).write_bytes(jar.read(name))
    sources=list((folder/'input/src/measurement/java').rglob('*.java'));need(len(sources)==7,'SOURCE_ALLOWLIST')
    with (folder/'compile.log').open('wb') as log:
        p=subprocess.run(['javac','-J-Xmx256m','-J-XX:ActiveProcessorCount=2','-encoding','UTF-8','-cp',str(work/'lib/*'),'-d',str(work/'classes'),*map(str,sources)],stdout=log,stderr=subprocess.STDOUT,timeout=45)
    need(p.returncode==0,'TOOL_COMPILE');need(all(p.relative_to(work/'classes').as_posix().startswith('campuslife/measurement/') for p in (work/'classes').rglob('*.class')),'NO_BUSINESS_CLASS_OVERLAY')
    item=scope.declare_tool();args=['create','--name',item['name'],'--network',d.manifest['project']+'_core','--user',str(os.getuid())+':'+str(os.getgid()),
        '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','256m','--memory-swap','256m','--cpuset-cpus','0,1','--pids-limit','64','--tmpfs','/tmp:size=32m,mode=1777','--entrypoint','java']
    for k,v in scope.tool_labels().items():args+=['--label',k+'='+v]
    release=Path('/home/campusdeploy/campuslife/releases/1cf0cb53a24a')
    mounts=[(work/'classes','/tool/classes'),(work/'lib','/tool/lib'),(private,'/run/fixture'),(release/'src/main/resources/db/migration','/tool/migrations'),
            (release/'src/main/resources/redis/coupon','/tool/redis'),(folder/'input/src/measurement/resources/coupon-observe.lua','/tool/observe.lua')]
    for src,dest in mounts:args+=['--mount','type=bind,source='+str(src)+',target='+dest+',readonly']
    args+=['--mount','type=bind,source='+str(work/'output')+',target=/output',d.manifest['image'],'-XX:ActiveProcessorCount=2','-Xms32m','-Xmx128m','-cp','/tool/classes:/tool/lib/*','campuslife.measurement.MeasurementFactChecks','/run/fixture']
    scope.mark_tool_dispatched();identity=docker(*args);need(scope.adopt_tool()==identity,'TOOL_ADOPTION');docker('start',identity)
    until=time.monotonic()+90
    while not (work/'output/ready-for-resume.json').exists() and time.monotonic()<until:
        info=json.loads(docker('inspect',identity))[0]
        if not info['State']['Running']:break
        time.sleep(.5)
    need((work/'output/ready-for-resume.json').exists(),'SEED_PREPARATION_FAILED')
    # Existing maintenance main, one attempt with its unchanged 90-second budget.
    fd=scope.locked()
    try:
        scope.load();scope.value['pending_compose']=True;scope.save()
        cli_at=time.monotonic();d.maintenance('coupon','Resume',2000001,version=0)
        cli_seconds=time.monotonic()-cli_at
        scope.load();scope.value['pending_compose']=False;scope.save()
    finally:os.close(fd)
    atomic(work/'output/resume-done.json',{'originalResume':True,'elapsedSeconds':cli_seconds})
    waited=subprocess.run(['docker','wait',identity],capture_output=True,text=True,timeout=260)
    need(waited.returncode==0,'TOOL_WAIT_FAILED');code=waited.stdout.strip();logs=docker('logs',identity)
    for p in private.iterdir():
        if p.name!='permit.json':need(p.read_text().strip() not in logs,'SECRET_LOG_LEAK')
    (folder/'tool.log').write_text(logs)
    for name in ['checks.json','samples.json','failure.json','progress.json','ready-for-resume.json','resume-done.json']:
        if (work/'output'/name).exists():shutil.copyfile(work/'output'/name,folder/name)
    info=json.loads(docker('inspect',identity))[0]
    atomic(folder/'host-checks.json',{'readiness':readiness,'originalResumeCalls':1,'originalResumeElapsedSeconds':cli_seconds,
        'toolImageId':info['Image'],'toolLimitBytes':info['HostConfig']['Memory'],'toolOomKilled':info['State']['OOMKilled'],
        'toolMounts':[m['Destination'] for m in info['Mounts']],'privateCredentialFiles':[p.name for p in private.iterdir() if p.name!='permit.json'],
        'observerAccount':'SELECT campuslife_deploy.* + SELECT performance_schema.*','appPrivilegesChanged':False,'dependencyJars':len(jars)})
    need(code=='0' and (folder/'checks.json').exists(),'FACT_CHECKS_FAILED')


def cleanup():
    folder=case();scope=ResourceScope(folder,'facts');scope.cleanup()
    work=folder/'work';no_links(work);need(work.resolve()==folder.resolve()/'work','WORK_SCOPE')
    if work.exists():shutil.rmtree(work)
    atomic(folder/'cleanup-proof.json',{'case_id':folder.name,'manifest_sha256':os.environ['CASE_MANIFEST_SHA256'],'clean':True,'resources_remaining':[]})


def main():
    parser=argparse.ArgumentParser();parser.add_argument('role',choices=['job','cleanup']);a=parser.parse_args();os.umask(0o077)
    (job if a.role=='job' else cleanup)()


if __name__=='__main__':
    try:main()
    except Exception as error:
        import traceback
        frames=[{'file':Path(f.filename).name,'line':f.lineno,'function':f.name} for f in traceback.extract_tb(error.__traceback__)]
        print(json.dumps({'failureType':type(error).__name__,'code':'FACT_OBSERVATION_FAILED','frames':frames}));sys.exit(1)
