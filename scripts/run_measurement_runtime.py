"""C.3 only: original seven services, fixed health baseline and small wiring check."""
import argparse
import json
import os
import re
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time
import zipfile

sys.path.insert(0,str(Path(__file__).resolve().parent))
from measurement_resources import ResourceScope,atomic,docker,no_links,need,RELEASE
from measurement_runtime import Monitor,SERVICES,host_memory
from measurement_fixture import attest
from test_measurement_facts import ready,read_info

def case():return Path(os.environ['CASE_DIRECTORY'])
def left():return float(os.environ['CASE_WORK_DEADLINE_UNIX'])-time.time()
def wait_file(path,seconds,scope=None):
    end=time.monotonic()+seconds
    while time.monotonic()<end and left()>10:
        if path.exists():return
        if scope:
            info=json.loads(docker('inspect',scope.value['tools'][0]['container_id']))[0]
            need(info['State']['Running'],'TOOL_EXITED_DURING_PREPARATION')
        time.sleep(.5)
    raise ValueError('HANDSHAKE_DEADLINE')

def compile_tool(folder,work):
    with zipfile.ZipFile(RELEASE/'target/campuslife-0.0.1-SNAPSHOT.jar') as jar:
        jars=[n for n in jar.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')];need(len(jars)==156,'DEPENDENCIES')
        for name in jars:(work/'lib'/Path(name).name).write_bytes(jar.read(name))
    sources=sorted((folder/'input/src/measurement/java').rglob('*.java'));need(len(sources)==8,'SOURCE_ALLOWLIST')
    with (folder/'compile.log').open('wb') as log:
        code=subprocess.run(['javac','-J-Xmx256m','-J-XX:ActiveProcessorCount=2','-encoding','UTF-8','-cp',str(work/'lib/*'),'-d',str(work/'classes'),*map(str,sources)],stdout=log,stderr=subprocess.STDOUT,timeout=45).returncode
    need(code==0,'TOOL_COMPILE');need(all(p.relative_to(work/'classes').as_posix().startswith('campuslife/measurement/') for p in (work/'classes').rglob('*.class')),'BUSINESS_CLASS_OVERLAY')
    with (folder/'signal-checks.log').open('wb') as log:
        code=subprocess.run(['java','-XX:ActiveProcessorCount=2','-Xmx128m','-cp',str(work/'classes')+':'+str(work/'lib/*'),'campuslife.measurement.MeasurementRuntimeSignalChecks'],stdout=log,stderr=subprocess.STDOUT,timeout=15).returncode
    need(code==0,'HOST_SIGNAL_CHECKS')

def launch_tool(scope,d,folder,work):
    # StorageInit's original curl one-off already creates the app's edge/core/storage networks.
    scope.load();scope.check_base(json.loads(docker('network','inspect',d.manifest['project']+'_edge'))[0],'network')
    item=scope.declare_tool();args=['create','--name',item['name'],'--network',d.manifest['project']+'_core','--user',str(os.getuid())+':'+str(os.getgid()),
        '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','256m','--memory-swap','256m','--cpuset-cpus','0,1','--pids-limit','64','--tmpfs','/tmp:size=32m,mode=1777','--entrypoint','java']
    for k,v in scope.tool_labels().items():args+=['--label',k+'='+v]
    for src,dest in [(work/'classes','/tool/classes'),(work/'lib','/tool/lib'),(work/'private','/run/fixture'),(RELEASE/'src/main/resources/db/migration','/tool/migrations'),(RELEASE/'src/main/resources/redis/coupon','/tool/redis'),(folder/'input/src/measurement/resources/coupon-observe.lua','/tool/observe.lua')]:
        args+=['--mount','type=bind,source='+str(src)+',target='+dest+',readonly']
    args+=['--mount','type=bind,source='+str(work/'output')+',target=/output',d.manifest['image'],'-XX:ActiveProcessorCount=2','-Xms32m','-Xmx128m','-cp','/tool/classes:/tool/lib/*','campuslife.measurement.MeasurementRuntime','/run/fixture']
    scope.mark_tool_dispatched();identity=docker(*args);need(scope.adopt_tool()==identity,'TOOL_ADOPTION')
    # The known tool is stopped and durably OBSERVED before attaching the second private network.
    docker('network','connect',d.manifest['project']+'_edge',identity);docker('start',identity);return identity

def preserve(folder,scope,d):
    """Preserve allowlisted artifacts even after preparation/health failure; never copy secrets."""
    work=folder/'work';scope.load();tools=scope.value['tools']
    names=['MYSQL_ROOT_PASSWORD','DB_PASSWORD','JWT_SECRET_BASE64','MANAGEMENT_PASSWORD','RATING_CACHE_PASSWORD','RATING_REDIS_OPS_PASSWORD','COUPON_REDIS_PASSWORD','COUPON_REDIS_OPS_PASSWORD','MESSAGING_PASSWORD','RABBIT_OPS_PASSWORD','RABBIT_ERLANG_COOKIE','FILE_S3_SECRET_KEY','S3_ADMIN_SECRET_KEY']
    credentials=[(d.folder/'secrets'/name).read_text().strip() for name in names]
    credentials += [p.read_text().strip() for p in (work/'private').iterdir() if p.is_file() and p.name!='permit.json']
    texts=[]
    for label,args in [('service.log',['compose']),('tool.log',['tool'])]:
        if args==['compose']:
            try:log=d.compose('logs','--no-color',timeout=25)
            except Exception:continue
        else:
            if not tools or not tools[0]['container_id']:continue
            log=docker('logs',tools[0]['container_id'])
        need(all(not v or v not in log for v in credentials),'SECRET_LOG_LEAK')
        need(not re.search(r'\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b',log),'TOKEN_LOG_LEAK')
        (folder/label).write_text(log);texts.append(label)
    output=work/'output';allowed=['expected.json','progress.json','failure.json','checks.json','facts-samples.json','drain.json','seed-ready.json','facts-ready.json','cache-first.json','artifact-hashes.json','host-state.json','go.json','app-ready.json']
    for name in allowed:
        p=output/name
        if p.exists():
            need(all(not v or v not in p.read_text() for v in credentials),'SECRET_ARTIFACT_LEAK');shutil.copyfile(p,folder/name)
    if (output/'segment').exists():
        target=folder/'segment';target.mkdir(exist_ok=False)
        for name in ['manifest.json','requests.csv','observations.json','result.json']:
            p=output/'segment'/name
            if p.exists():need(all(not v or v not in p.read_text() for v in credentials),'SECRET_ARTIFACT_LEAK');shutil.copyfile(p,target/name)
    atomic(folder/'credential-checks.json',{'exactValuesChecked':len(credentials),'logs':texts,'rootJwtS3MountedToTool':False})

def job():
    folder=case();scope=ResourceScope(folder,'runtime');scope.begin();d=scope.initialize();work=folder/'work';monitor=None
    need(not docker('ps','-aq') and not docker('volume','ls','-q'),'NO_EXISTING_RESOURCES')
    for name in ['private','output','lib','classes']:(work/name).mkdir(mode=0o700)
    try:
        mem=host_memory();engine=json.loads(docker('info','--format','{{json .}}'))
        need(sys.platform=='linux' and not Path('/.dockerenv').exists() and 'microsoft' not in Path('/proc/version').read_text().lower() and os.cpu_count()==2 and engine['NCPU']==2 and engine['CgroupVersion']=='2' and 3*1024**3<=mem['MemTotal']<=4*1024**3,'NATIVE_HOST_BOUNDARY')
        scope.compose('up','-d','--no-deps','mysql','rating-redis','coupon-redis','rabbitmq','seaweed',timeout=90,log=folder/'components.log');ready(d)
        permit=attest(d,folder.name,'FORMAL');permit.update(namespace='deploy_'+d.manifest['identity'],vhost='campuslife_'+d.manifest['identity'],couponRun=read_info(d,'coupon-redis','COUPON_REDIS_OPS_PASSWORD'),ratingRun=read_info(d,'rating-redis','RATING_REDIS_OPS_PASSWORD'))
        atomic(work/'private/permit.json',permit);password=secrets.token_hex(32)
        (work/'private/METRICS_DB_PASSWORD').write_text(password);(work/'private/STUDENT_PASSWORD').write_text(secrets.token_hex(32))
        for name in ['DB_PASSWORD','RATING_REDIS_OPS_PASSWORD','COUPON_REDIS_OPS_PASSWORD','RABBIT_OPS_PASSWORD']:(work/'private'/name).write_bytes((d.folder/'secrets'/name).read_bytes())
        d.compose('exec','-T','mysql','sh','-c','MYSQL_PWD="$(cat /run/secrets/MYSQL_ROOT_PASSWORD)" exec mysql --protocol=socket --user=root --batch --skip-column-names',stdin="CREATE USER 'campuslife_measure'@'%' IDENTIFIED WITH caching_sha2_password BY '"+password+"'; GRANT SELECT ON campuslife_deploy.* TO 'campuslife_measure'@'%'; GRANT SELECT ON performance_schema.* TO 'campuslife_measure'@'%';",timeout=15)
        # Original S3 bucket/marker/policy initialization; durable pending protects unknown outcomes.
        from deploy_storage import initialize_storage
        fd=scope.locked()
        try:
            scope.load();scope.value['pending_compose']=True;scope.save();initialize_storage(d)
            scope.load();scope.value['pending_compose']=False;scope.save()
        finally:os.close(fd)
        compile_tool(folder,work);identity=launch_tool(scope,d,folder,work);monitor=Monitor(scope,atomic);monitor.collect('PREPARE')
        wait_file(work/'output/seed-ready.json',90,scope)
        fd=scope.locked()
        try:
            scope.load();scope.value['pending_compose']=True;scope.save();at=time.monotonic();d.maintenance('coupon','Resume',2000001,version=0);cli=time.monotonic()-at
            scope.load();scope.value['pending_compose']=False;scope.save()
        finally:os.close(fd)
        atomic(folder/'maintenance.json',{'originalMainCalls':1,'elapsedSeconds':cli,'originalLimitSeconds':90})
        scope.compose('up','-d','--no-deps','app','nginx',timeout=90,log=folder/'app-start.log')
        end=time.monotonic()+120
        while time.monotonic()<end and left()>200:
            sample=monitor.collect('STARTUP');rows=sample['containers']
            if {r['service'] for r in rows}==SERVICES and all(r['health']=='healthy' for r in rows):break
            time.sleep(5)
        else:raise ValueError('SEVEN_SERVICES_NOT_READY')
        atomic(work/'output/app-ready.json',{'originalSevenServices':True})
        wait_file(work/'output/facts-ready.json',25,scope)
        need(left()>200,'INSUFFICIENT_WIRING_BUDGET')
        baseline_start=time.time();baseline_mono=time.monotonic();valid=True
        while True:
            sample=monitor.collect('BASELINE',strict=True)
            valid=valid and all(r['health']=='healthy' for r in sample['containers'])
            if sample['stopCode']:break
            if time.monotonic()-baseline_mono>=60:break
            time.sleep(min(5,max(0,60-(time.monotonic()-baseline_mono))))
        passed=valid and monitor.gate.baseline(sample['containers'],baseline_start,time.monotonic()-baseline_mono)
        atomic(folder/'baseline.json',{'passed':passed,'startUnix':baseline_start,'elapsedSeconds':time.monotonic()-baseline_mono,'probeCompletions':sorted(monitor.gate.probes.values(),key=lambda p:p['end']),'stopCode':sample['stopCode'],'continuousHealthy':valid})
        need(passed,'HEALTH_BASELINE_REJECTED')
        monitor.collect('RUN',strict=True);atomic(work/'output/go.json',{'task':'C.3','foreground':8,'pilot':False,'formal':False})
        end=time.monotonic()+210
        while time.monotonic()<end and left()>10:
            sample=monitor.collect('RUN',strict=True)
            info=json.loads(docker('inspect',identity))[0]
            if not info['State']['Running']:break
            time.sleep(5)
        else:raise ValueError('WIRING_TOOL_DEADLINE')
        need(not sample['stopCode'],'RUNTIME_HEALTH_STOP')
        need(info['State']['ExitCode']==0 and (work/'output/checks.json').exists(),'RUNTIME_CHECKS_FAILED')
        atomic(folder/'host-checks.json',{'sevenServices':sorted(SERVICES),'toolImageId':info['Image'],'memoryLimitBytes':info['HostConfig']['Memory'],'memorySwapLimitBytes':info['HostConfig']['MemorySwap'],
            'cpuset':info['HostConfig']['CpusetCpus'],'toolOomKilled':info['State']['OOMKilled'],'toolNetworks':sorted(info['NetworkSettings']['Networks']),
            'toolMounts':[v['Destination'] for v in info['Mounts']],'hostOomBefore':monitor.before['oomKillCount'],'hostOomAfter':host_memory()['oomKillCount'],'appPrivilegesChanged':False,'sourceCount':8})
    except BaseException as error:
        atomic(work/'output/abort.json',{'stop':True});atomic(folder/'host-failure.json',{'type':type(error).__name__,'code':str(error) if isinstance(error,ValueError) else 'HOST_WIRING_FAILED'})
        raise
    finally:preserve(folder,scope,d)

def cleanup():
    folder=case();scope=ResourceScope(folder,'runtime');scope.cleanup();work=folder/'work';no_links(work);need(work.resolve()==folder.resolve()/'work','WORK_SCOPE')
    if work.exists():shutil.rmtree(work)
    atomic(folder/'cleanup-proof.json',{'case_id':folder.name,'manifest_sha256':os.environ['CASE_MANIFEST_SHA256'],'clean':True,'resources_remaining':[]})

def main():
    p=argparse.ArgumentParser();p.add_argument('role',choices=['job','cleanup']);args=p.parse_args();os.umask(0o077);(job if args.role=='job' else cleanup)()
if __name__=='__main__':
    try:main()
    except BaseException as error:
        import traceback
        print(json.dumps({'failureType':type(error).__name__,'code':'WIRING_FAILED','frames':[{'file':Path(f.filename).name,'line':f.lineno,'function':f.name} for f in traceback.extract_tb(error.__traceback__)]}));sys.exit(1)
