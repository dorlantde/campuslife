"""D.7.1-B one case only. Original broker healthcheck stays unchanged."""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time

sys.path.insert(0,str(Path(__file__).resolve().parent))
from measurement_resources import ResourceScope,atomic,docker,no_links,need,RELEASE
from measurement_runtime import host_memory,probe_unix
from rabbit_probe_diagnostics import ProbeBook,http,manual,resource_row,JWT

PROBE=['CMD-SHELL','gosu rabbitmq rabbitmq-diagnostics -q check_running && gosu rabbitmq rabbitmq-diagnostics -q check_port_connectivity']
SECRET_NAMES=['MYSQL_ROOT_PASSWORD','DB_PASSWORD','JWT_SECRET_BASE64','MANAGEMENT_PASSWORD','RATING_CACHE_PASSWORD','RATING_REDIS_OPS_PASSWORD','COUPON_REDIS_PASSWORD','COUPON_REDIS_OPS_PASSWORD','MESSAGING_PASSWORD','RABBIT_OPS_PASSWORD','RABBIT_ERLANG_COOKIE','FILE_S3_SECRET_KEY','S3_ADMIN_SECRET_KEY']

def case():return Path(os.environ['CASE_DIRECTORY'])
def remaining():return float(os.environ['CASE_WORK_DEADLINE_UNIX'])-time.time()
def inspect(ids,timeout=2):
    p=subprocess.run(['docker','inspect',*ids],capture_output=True,text=True,timeout=timeout);need(p.returncode==0,'INSPECT_FAILED');return json.loads(p.stdout)

def worker_prepare(scope,d,folder):
    classes=folder/'work/classes';classes.mkdir(mode=0o700);source=folder/'input/src/diagnostics/java/campuslife/diagnostics/CpuWindow.java'
    with (folder/'compile.log').open('wb') as out:
        p=subprocess.run(['javac','-J-Xmx128m','-J-XX:ActiveProcessorCount=1','-encoding','UTF-8','-d',str(classes),str(source)],stdout=out,stderr=subprocess.STDOUT,timeout=15)
    need(p.returncode==0,'WORKER_COMPILE')
    short=subprocess.run(['java','-XX:ActiveProcessorCount=1','-Xmx32m','-cp',str(classes),'campuslife.diagnostics.CpuWindow','1'],capture_output=True,text=True,timeout=5)
    need(short.returncode==0,'WORKER_SHORT_CHECK');data=json.loads(short.stdout);need(data['requestedSeconds']==1 and 1e9<=data['elapsedNanos']<3e9 and data['iterations']>0,'WORKER_SELF_DEADLINE')
    atomic(folder/'worker-short-check.json',data)
    item=scope.declare_tool();args=['create','--name',item['name'],'--network','none','--user',str(os.getuid())+':'+str(os.getgid()),'--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true',
        '--memory','128m','--memory-swap','128m','--cpuset-cpus','0','--cpus','1','--pids-limit','32','--tmpfs','/tmp:size=8m,mode=1777','--entrypoint','java',
        '--mount','type=bind,source='+str(classes)+',target=/tool/classes,readonly']
    for k,v in scope.tool_labels().items():args+=['--label',k+'='+v]
    args += [d.manifest['image'],'-XX:ActiveProcessorCount=1','-Xms16m','-Xmx32m','-cp','/tool/classes','campuslife.diagnostics.CpuWindow','60']
    scope.mark_tool_dispatched();identity=docker(*args);need(scope.adopt_tool()==identity,'WORKER_ADOPTION')
    info=inspect([identity])[0];limits=info['HostConfig']
    need(limits['Memory']==limits['MemorySwap']==128*1024**2 and limits['CpusetCpus']=='0' and limits['NanoCpus']==1000000000 and limits['NetworkMode']=='none' and limits['ReadonlyRootfs'],'WORKER_LIMITS')
    atomic(folder/'worker-config.json',{'imageId':info['Image'],'memoryBytes':limits['Memory'],'swapLimitBytes':limits['MemorySwap'],'cpuSet':limits['CpusetCpus'],'nanoCpus':limits['NanoCpus'],'network':limits['NetworkMode'],'mounts':[m['Destination'] for m in info['Mounts']],'readOnly':limits['ReadonlyRootfs']})
    return identity

class Collector:
    def __init__(self,scope,broker,worker,credentials):
        self.scope=scope;self.broker=broker;self.worker=worker;self.book=ProbeBook(broker,credentials);self.rows=[];self.errors=[];self.stop_code='';self.before=host_memory();self.anchors={}
        self.done=threading.Event();self.lock=threading.Lock();self.thread=threading.Thread(target=self.loop,daemon=True);self.tool=dict(scope.value['tools'][0])
    def collect(self,resources=True):
        at=time.monotonic_ns();infos=inspect([self.broker,self.worker]);brief=[]
        for info in infos:
            tool=info['Id']==self.worker
            if tool:self.scope.check_tool(info,self.tool)
            else:
                self.scope.check_base(info,'container');health=info['Config']['Healthcheck']
                need(health['Test']==PROBE and health['Interval']==health['Timeout']==5000000000 and health['StartPeriod']==15000000000 and health['Retries']==30,'ORIGINAL_PROBE_CHANGED');self.book.add(info)
            state=info['State'];row={'id':info['Id'],'role':'worker' if tool else 'rabbitmq','running':state['Running'],'status':state['Status'],'oomKilled':state['OOMKilled'],'health':state.get('Health',{}).get('Status'),'failingStreak':state.get('Health',{}).get('FailingStreak',0),'restartCount':info['RestartCount']}
            if state['OOMKilled']:self.stop_code=self.stop_code or 'CONTAINER_OOM'
            if info['RestartCount']:self.stop_code=self.stop_code or 'CONTAINER_RESTARTED'
            if not tool and not state['Running']:self.stop_code=self.stop_code or 'BROKER_EXITED'
            if resources and state['Running']:
                res=resource_row(info);row['resource']=res
                need(res['cpuset'] in (['0'] if tool else ['0-1','0,1']),'CPU_SCOPE')
                anchor=(res['id'],res['pid'],res['startTicks']);need(self.anchors.get(row['role'],anchor)==anchor,'RESOURCE_IDENTITY_CHANGED');self.anchors[row['role']]=anchor
                if res['events']['oom'] or res['events']['oom_kill']:self.stop_code=self.stop_code or 'CGROUP_OOM'
            brief.append(row)
        host=host_memory()
        if host['oomKillCount']!=self.before['oomKillCount']:self.stop_code=self.stop_code or 'HOST_OOM'
        return {'atUnixMillis':int(time.time()*1000),'monotonicNanos':at,'containers':brief,'host':host,'stopCode':self.stop_code}
    def loop(self):
        next_resource=0
        while not self.done.is_set():
            at=time.monotonic()
            try:
                row=self.collect(at>=next_resource)
                if at>=next_resource:next_resource=at+5
                with self.lock:self.rows.append(row)
            except BaseException as error:
                with self.lock:self.errors.append({'atUnixMillis':int(time.time()*1000),'type':type(error).__name__})
                # Missing evidence is preserved; identity/resource errors are not a zero observation.
                if isinstance(error,(ValueError,OSError)):self.stop_code=self.stop_code or 'OBSERVER_IDENTITY_OR_RESOURCE_ERROR'
            self.done.wait(max(0,1-(time.monotonic()-at)))
    def start(self):self.thread.start()
    def finish(self):
        self.done.set();self.thread.join(4);need(not self.thread.is_alive(),'COLLECTOR_NOT_EXITED')
        atomic(self.scope.folder/'native-probes.json',self.book.snapshot());atomic(self.scope.folder/'host-samples.json',self.rows);atomic(self.scope.folder/'observer-errors.json',self.errors)

def tcp(host):
    at=time.monotonic()
    try:
        with socket.create_connection((host,5672),timeout=.3):return {'available':True,'elapsedSeconds':time.monotonic()-at}
    except OSError:return {'available':False,'elapsedSeconds':time.monotonic()-at}

def slot_plan():
    return [{'window':window,'type':kind,'atSecond':offset,'state':'NOT_EXECUTED','reason':'CASE_NOT_REACHED'} for window in ['A','B'] for kind,offsets in [('http',[0,20,40]),('manual',[10,30,45])] for offset in offsets]

def window(label,host,vhost,password,collector,slots,worker):
    need(remaining()>60+45+10,'INSUFFICIENT_WINDOW_BUDGET')
    start=time.monotonic();unix=time.time()
    if label=='B':docker('start',worker)
    selected=sorted([s for s in slots if s['window']==label],key=lambda s:s['atSecond']);stop=''
    for slot in selected:
        due=start+slot['atSecond']
        while time.monotonic()<due and not collector.stop_code:time.sleep(min(.1,due-time.monotonic()))
        if collector.stop_code:stop=collector.stop_code;break
        if time.monotonic()>=start+60:stop='WINDOW_CUTOFF';break
        if time.monotonic()-due>=1:
            slot.update(reason='MISSED_FIXED_SLOT',latenessSeconds=time.monotonic()-due);continue
        slot.update(state='EXECUTED',reason='',actualUnix=time.time(),latenessSeconds=max(0,time.monotonic()-due))
        if slot['type']=='http':
            slot['ready']=http(host,'ready',vhost,password);slot['vhost']=http(host,'vhost',vhost,password);slot['tcp']=tcp(host)
        else:
            kind={10:'combined',30:'running',45:'ports'}[slot['atSecond']];result=manual(collector.broker,kind,collector.book.secrets);slot['result']=result
            if result['outerTimedOut'] or not result['outputReaderExited']:
                stop='MANUAL_OUTER_TIMEOUT' if result['outerTimedOut'] else 'MANUAL_READER_UNRESOLVED';time.sleep(5);break
        atomic(collector.scope.folder/'slots.json',slots)
    if not stop:
        while time.monotonic()<start+60 and not collector.stop_code:time.sleep(.1)
        stop=collector.stop_code
    end=time.time()
    return {'label':label,'startUnix':unix,'endUnix':end,'elapsedSeconds':time.monotonic()-start,'stopCode':stop,'completedFixedWindow':not stop}

def job():
    folder=case();scope=ResourceScope(folder,'probe');scope.begin();collector=None;result={'task':'D.7.1-B','schema':1,'windows':[],'slots':slot_plan(),'authSamples':[],'diagnosisStatus':'NOT_STARTED','businessRequests':0,'candidateInstalled':False};started=time.monotonic()
    try:
        need(not docker('ps','-aq') and not docker('volume','ls','-q'),'EXISTING_RESOURCES')
        d=scope.initialize();work=folder/'work'
        # Local-only primitives on the actual Python version; no Docker/protocol calls in unit tests.
        with (folder/'unit-checks.log').open('wb') as log:
            check=subprocess.run([sys.executable,'-m','unittest','discover','-s',str(folder/'input/scripts'),'-p','test_rabbit_probe_diagnostics.py','-v'],stdout=log,stderr=subprocess.STDOUT,timeout=8)
        need(check.returncode==0,'RELATED_UNIT_CHECKS')
        worker=worker_prepare(scope,d,folder);need(time.monotonic()-started<=45,'PREPARATION_BUDGET')
        credentials=[(d.folder/'secrets'/name).read_text().strip() for name in SECRET_NAMES];password=(d.folder/'secrets/RABBIT_OPS_PASSWORD').read_text().strip();vhost='campuslife_'+d.manifest['identity']
        configured=d.validate()['services']['rabbitmq'];need(configured['healthcheck']['test']==PROBE and int(configured['mem_limit'])==640*1024**2,'ORIGINAL_CONFIG')
        boot=time.monotonic();scope.compose('up','-d','--no-deps','rabbitmq',timeout=60,log=folder/'broker-start.log')
        brokers=d.resources('container');need(len(brokers)==1 and brokers[0]['Config']['Labels']['com.docker.compose.service']=='rabbitmq','ONLY_BROKER')
        broker=brokers[0];scope.check_base(broker,'container');core=d.manifest['project']+'_core';host=broker['NetworkSettings']['Networks'][core]['IPAddress']
        collector=Collector(scope,broker['Id'],worker,credentials);collector.start();boot_samples=[]
        while time.monotonic()-boot<90:
            if collector.stop_code:raise ValueError(collector.stop_code)
            a=http(host,'overview',vhost,password);b=http(host,'vhost',vhost,password);boot_samples.append({'overview':a,'vhost':b,'atUnix':time.time()})
            atomic(folder/'protocol-attempts.json',boot_samples)
            if a['available'] and b['available']:break
            time.sleep(1)
        else:raise ValueError('BROKER_PROTOCOL_READY_DEADLINE')
        atomic(folder/'protocol-ready.json',{'elapsedSeconds':time.monotonic()-boot,'samples':boot_samples,'healthyRequired':False})
        result['windows'].append(window('A',host,vhost,password,collector,result['slots'],worker))
        if not result['windows'][-1]['stopCode']:result['windows'].append(window('B',host,vhost,password,collector,result['slots'],worker))
        stop=next((w['stopCode'] for w in result['windows'] if w['stopCode']),'')
        if not stop:
            tail=time.monotonic();info=inspect([worker])[0]
            while info['State']['Running'] and time.monotonic()-tail<3:
                time.sleep(.1);info=inspect([worker])[0]
            need(not info['State']['Running'] and info['State']['ExitCode']==0,'WORKER_SELF_EXIT')
            result['workerExitObservationWaitSeconds']=time.monotonic()-tail
            output=docker('logs',worker);need(not JWT.search(output.encode()) and all(s not in output for s in credentials),'WORKER_OUTPUT_LEAK');worker_result=json.loads(output)
            need(worker_result['requestedSeconds']==60 and 60e9<=worker_result['elapsedNanos']<65e9,'WORKER_SELF_DEADLINE');result['workerResult']=worker_result
            for kind in ['ready','vhost']:
                for mode,credential in [('normal',password),('none',None),('wrong',secrets.token_hex(32))]:
                    result['authSamples'].append({'endpoint':kind,'credentialMode':mode,'result':http(host,kind,vhost,credential)})
            inventory=subprocess.run(['docker','exec',collector.broker,'sh','-c','for name in curl wget timeout; do if command -v "$name" >/dev/null 2>&1; then printf "%s=present\\n" "$name"; else printf "%s=absent\\n" "$name"; fi; done'],capture_output=True,text=True,timeout=5)
            need(inventory.returncode==0,'CLIENT_INVENTORY');result['clientInventory']={name:value for name,value in (line.split('=') for line in inventory.stdout.splitlines())}
            need(set(result['clientInventory'])=={'curl','wget','timeout'} and all(v in ('present','absent') for v in result['clientInventory'].values()),'INVENTORY_ALLOWLIST')
            need(time.monotonic()-tail<=45,'CANDIDATE_BUDGET');result['diagnosisStatus']='FIXED_WINDOWS_COMPLETE'
        else:
            result['diagnosisStatus']='TRUNCATED_ON_MANUAL_TIMEOUT' if stop=='MANUAL_OUTER_TIMEOUT' else 'TRUNCATED';result['stopCode']=stop
        result['originalConfig']={'healthcheck':configured['healthcheck'],'memoryMiB':640,'cpuset':'0,1','credentialMountsUnchanged':True}
        result['elapsedSeconds']=time.monotonic()-started
    except BaseException as error:
        result['diagnosisStatus']='TOOL_OR_PRECONDITION_FAILED';result['failureType']=type(error).__name__;result['failureCode']=str(error) if isinstance(error,ValueError) else 'DIAGNOSTIC_FAILED'
        raise
    finally:
        if collector:
            collector.finish()
            records=collector.book.snapshot()
            for w in result['windows']:
                rows=[p for p in records if w['startUnix']<=probe_unix(p['end'])<=w['endUnix']];w['nativeCompleted']=len(rows);w['predefinedFirstFiveKeys']=[p['start']+'|'+p['end'] for p in rows[:5]];w['firstFiveComplete']=len(rows)>=5
        for slot in result['slots']:
            if slot['state']=='NOT_EXECUTED':slot['reason']=result.get('stopCode',result.get('failureCode','CASE_NOT_REACHED'))
        result['elapsedSeconds']=time.monotonic()-started;atomic(folder/'diagnosis.json',result);atomic(folder/'slots.json',result['slots'])
        # No raw CLI/HTTP/service output is exported. Only fixed worker JSON and sanitized classifications.
        if collector:
            encoded=json.dumps(result).encode();need(not JWT.search(encoded) and all(s.encode() not in encoded for s in collector.book.secrets),'DIAGNOSIS_SECRET_LEAK')
            atomic(folder/'secret-checks.json',{'generatedValuesChecked':len(collector.book.secrets),'rawProbeOrHttpSaved':False,'jwtShapeAbsent':True})

def cleanup():
    folder=case();scope=ResourceScope(folder,'probe');scope.cleanup();work=folder/'work';no_links(work);need(work.resolve()==folder.resolve()/'work','WORK_SCOPE')
    if work.exists():shutil.rmtree(work)
    atomic(folder/'cleanup-proof.json',{'case_id':folder.name,'manifest_sha256':os.environ['CASE_MANIFEST_SHA256'],'clean':True,'resources_remaining':[]})

def main():
    p=argparse.ArgumentParser();p.add_argument('role',choices=['job','cleanup']);a=p.parse_args();os.umask(0o077);(job if a.role=='job' else cleanup)()

if __name__=='__main__':
    try:main()
    except BaseException as error:
        import traceback
        print(json.dumps({'code':'DIAGNOSIS_FAILED','failureType':type(error).__name__,'frames':[{'file':Path(f.filename).name,'line':f.lineno,'function':f.name} for f in traceback.extract_tb(error.__traceback__)]}));sys.exit(1)
