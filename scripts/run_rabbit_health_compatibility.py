"""D.7.1-C.1 only: secret-free image inventory before any deployment Init."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time

sys.path.insert(0,str(Path(__file__).resolve().parent))
RABBIT='rabbitmq:4.3.6-management@sha256:54cb013d76cd1fd6c0a0708f34062ad61c452bb6f016b434c6c33f1a765d2b58'
TOOLS=('curl','wget','timeout','sh','bash')
PROBE=['CMD-SHELL','gosu rabbitmq rabbitmq-diagnostics -q check_running && gosu rabbitmq rabbitmq-diagnostics -q check_port_connectivity']
INVENTORY='''for name in curl wget timeout sh bash; do
 p="$(command -v "$name" || true)"
 if [ -n "$p" ]; then printf '%s=%s\n' "$name" "$p"; else printf '%s=absent\n' "$name"; fi
done
for name in curl wget timeout; do
 if command -v "$name" >/dev/null 2>&1; then
  "$name" --version 2>/dev/null | { IFS= read -r line; printf 'version_%s=%s\n' "$name" "$line"; }
 fi
done
'''

def need(value,code):
    if not value:raise ValueError(code)

def decode_inventory(raw):
    need(len(raw)<=8192,'INVENTORY_LIMIT');values={}
    for line in raw.splitlines():
        key,sep,value=line.partition('=');need(sep and key not in values and key in TOOLS+tuple('version_'+x for x in TOOLS[:3]),'INVENTORY_SHAPE');values[key]=value
    need(all(k in values for k in TOOLS),'INVENTORY_MISSING')
    for k in TOOLS:need(values[k]=='absent' or re.fullmatch(r'/(usr/)?bin/'+k,values[k]),'INVENTORY_PATH')
    result={'paths':{k:values[k] for k in TOOLS},'versions':{}}
    patterns={'curl':r'^curl ([0-9]+\.[0-9]+\.[0-9]+)(?: |$)','wget':r'^GNU Wget ([0-9.]+)(?: |$)','timeout':r'^timeout \(GNU coreutils\) ([0-9.]+)$'}
    for k,pattern in patterns.items():
        match=re.match(pattern,values.get('version_'+k,''));result['versions'][k]=match[1] if match else None
    selected=next((k for k in ('curl','wget') if values[k]!='absent' and result['versions'][k]),None)
    result['selectedClient']=selected
    result['compatibleTools']=bool(selected and values['timeout']!='absent' and result['versions']['timeout'] and values['sh']!='absent')
    result['reason']='TOOLS_AVAILABLE' if result['compatibleTools'] else 'NO_HTTP_CLIENT' if selected is None else 'NO_TOTAL_DEADLINE_TOOL'
    return result

def inventory_scope(folder):
    from measurement_resources import ResourceScope
    class InventoryScope(ResourceScope):
        def declare_inventory(self,image_id):
            fd=self.locked()
            try:
                v=self.load();need(v['state']=='PREPARING' and v['instance'] is None and not v['tools'],'INVENTORY_ONLY')
                need(re.fullmatch('sha256:[a-f0-9]{64}',image_id),'IMAGE_ID')
                v['state']='REGISTERED';v['tools']=[{'name':'campuslife-measure-'+self.folder.name[5:]+'-'+self.slot,'image_id':image_id,'container_id':None,'phase':'DECLARED'}]
                self.save();return dict(v['tools'][0])
            finally:os.close(fd)
    return InventoryScope(folder,'http-tools')

def cmd(args,timeout=5):
    p=subprocess.run(args,capture_output=True,text=True,timeout=timeout);need(p.returncode==0,'BOUNDED_COMMAND_FAILED');return p.stdout

def image_inventory(folder,scope):
    from measurement_resources import RELEASE,JAR_SHA,sha,atomic
    started=time.monotonic();source=json.loads((RELEASE/'source-manifest.json').read_text())
    need(len(source['files'])==463 and all(sha(RELEASE/i['path'])==i['sha256'] for i in source['files']),'FORMAL_SOURCE')
    need(sha(RELEASE/'target/campuslife-0.0.1-SNAPSHOT.jar')==JAR_SHA,'FORMAL_JAR')
    image=json.loads(cmd(['docker','image','inspect',RABBIT]))[0]
    need(image['Architecture']=='amd64' and image['Os']=='linux' and any(x.endswith('@'+RABBIT.split('@')[1]) for x in image['RepoDigests']),'PINNED_IMAGE')
    need('RABBITMQ_VERSION=4.3.6' in image['Config']['Env'],'RABBIT_VERSION')
    item=scope.declare_inventory(image['Id']);scope.mark_tool_dispatched()
    args=['docker','create','--name',item['name'],'--network','none','--user','10001:10001','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','32m','--memory-swap','32m','--cpus','1','--pids-limit','16','--tmpfs','/var/lib/rabbitmq:size=1m,mode=1777','--entrypoint','/bin/sh']
    for k,v in scope.tool_labels().items():args+=['--label',k+'='+v]
    identity=cmd(args+[image['Id'],'-c',INVENTORY]).strip();need(scope.adopt_tool()==identity,'TOOL_ADOPTION')
    info=json.loads(cmd(['docker','inspect',identity]))[0];scope.check_tool(info,item)
    need(info['HostConfig']['NetworkMode']=='none' and info['HostConfig']['ReadonlyRootfs'] and info['HostConfig']['Memory']==info['HostConfig']['MemorySwap']==32*1024**2,'TOOL_LIMITS')
    need(not any(m['Type'] in ('bind','volume') for m in info['Mounts']) and not info['Config'].get('Healthcheck'),'NO_SECRET_OR_VOLUME_OR_BROKER')
    cmd(['docker','start',identity]);exit_code=cmd(['docker','wait',identity]).strip();need(exit_code=='0','INVENTORY_EXIT')
    text=cmd(['docker','logs',identity]);result=decode_inventory(text)
    result.update(imageId=image['Id'],repoDigest=RABBIT.split('@')[1],version='4.3.6',containerId=identity,network='none',secretMounts=0,brokerStarted=False,anonymousVolumes=0,elapsedSeconds=time.monotonic()-started)
    atomic(folder/'image-inventory.json',result);need(result['elapsedSeconds']<=20,'INVENTORY_BUDGET')
    scope.cleanup();return result

def container_request(broker,inventory):
    from rabbit_probe_diagnostics import decode_http
    client=inventory['selectedClient'];paths=inventory['paths'];url='http://127.0.0.1:15672/api/health/checks/ready-to-serve-clients'
    args=['docker','exec',broker,paths['timeout'],'--kill-after=0.2s','4s',paths[client]]
    if client=='curl':args+=['--disable','--noproxy','*','--silent','--connect-timeout','1','--max-time','3','--max-redirs','0','--max-filesize','4096','--write-out','\n__HTTP_CODE__%{http_code}',url]
    else:args+=['--no-config','--no-proxy','--no-hsts','--tries=1','--timeout=3','--max-redirect=0','--server-response','-O','-',url]
    started=time.monotonic();p=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE);buffers=[bytearray(),bytearray()];counts=[0,0]
    def reader(index,stream):
        while True:
            block=stream.read(1024)
            if not block:return
            counts[index]+=len(block);buffers[index].extend(block[:max(0,8192-len(buffers[index]))])
    threads=[threading.Thread(target=reader,args=(n,s),daemon=True) for n,s in enumerate([p.stdout,p.stderr])]
    for t in threads:t.start()
    expired=False
    try:p.wait(timeout=5)
    except subprocess.TimeoutExpired:expired=True;p.kill();p.wait(timeout=1)
    for t in threads:t.join(.5)
    need(not expired and all(not t.is_alive() for t in threads),'HTTP_CLIENT_OR_CHILD_UNRESOLVED')
    out,err=(bytes(b) for b in buffers);status=None;body=out
    if client=='curl':
        body,sep,tail=out.rpartition(b'\n__HTTP_CODE__');need(sep and re.fullmatch(b'[0-9]{3}',tail),'HTTP_RESULT_SHAPE');status=int(tail)
    else:
        codes=re.findall(rb'^\s*HTTP/[0-9.]+ ([0-9]{3})',err,re.M);need(len(codes)==1,'HTTP_REDIRECT_OR_SHAPE');status=int(codes[0])
    need(counts[0]<=8192 and counts[1]<=8192 and len(body)<=4096,'HTTP_BODY_LIMIT')
    value=decode_http(status,body,'ready','unused')
    return dict(value,client=client,clientVersion=inventory['versions'][client],exitCode=p.returncode,elapsedSeconds=time.monotonic()-started,credentialMode='none',bodyBytes=len(body),rawSaved=False)

def job():
    from measurement_resources import ResourceScope,atomic
    from rabbit_probe_diagnostics import http
    folder=Path(os.environ['CASE_DIRECTORY']);result={'task':'D.7.1-C.1','status':'STARTING','brokerStarted':False,'deploymentInitialized':False,'authSamples':[],'originalProbeUnchanged':True,'businessRequests':0};started=time.monotonic()
    scope=inventory_scope(folder);scope.begin()
    try:
        need(not cmd(['docker','ps','-aq']).strip() and not cmd(['docker','volume','ls','-q']).strip(),'EXISTING_RESOURCES')
        with (folder/'unit-checks.log').open('wb') as out:
            checks=subprocess.run([sys.executable,'-m','unittest','discover','-s',str(folder/'input/scripts'),'-p','test_rabbit_health_compatibility.py','-v'],stdout=out,stderr=subprocess.STDOUT,timeout=5)
        need(checks.returncode==0,'RELATED_UNIT_CHECKS')
        inventory=image_inventory(folder,scope);result['inventory']=inventory
        if not inventory['compatibleTools']:
            result.update(status='INCOMPATIBLE_TOOLS',stopReason=inventory['reason']);return
        base=ResourceScope(folder,'http-broker');base.begin();d=base.initialize();result['deploymentInitialized']=True
        configured=d.validate()['services']['rabbitmq'];need(configured['healthcheck']['test']==PROBE,'ORIGINAL_PROBE_CHANGED')
        boot=time.monotonic();base.compose('up','-d','--no-deps','rabbitmq',timeout=60,log=folder/'broker-start.log');result['brokerStarted']=True
        brokers=d.resources('container');need(len(brokers)==1,'ONLY_BROKER');broker=brokers[0];base.check_base(broker,'container');host=broker['NetworkSettings']['Networks'][d.manifest['project']+'_core']['IPAddress'];vhost='campuslife_'+d.manifest['identity'];password=(d.folder/'secrets/RABBIT_OPS_PASSWORD').read_text().strip()
        attempts=[]
        while time.monotonic()-boot<90:
            row={k:http(host,k,vhost,password) for k in ['overview','vhost','ready']};attempts.append(row);atomic(folder/'protocol-attempts.json',attempts)
            if all(v['available'] for v in row.values()):break
            time.sleep(1)
        else:raise ValueError('PROTOCOL_READY_DEADLINE')
        phase=time.monotonic();result['containerRequest']=container_request(broker['Id'],inventory)
        for kind in ['ready','vhost']:
            for mode,credential in [('normal',password),('none',None),('wrong','compatibility-invalid-only')]:
                result['authSamples'].append({'endpoint':kind,'credentialMode':mode,'result':http(host,kind,vhost,credential)})
        ready=[s for s in result['authSamples'] if s['endpoint']=='ready'];vhosts=[s for s in result['authSamples'] if s['endpoint']=='vhost']
        good=result['containerRequest']['available'] and result['containerRequest']['exitCode']==0 and all(s['result']['available'] for s in ready) and vhosts[0]['result']['available'] and all(s['result'].get('httpStatus') in (401,403) for s in vhosts[1:])
        need(time.monotonic()-phase<=40,'COMPATIBILITY_BUDGET');result['status']='COMPATIBLE' if good else 'INCOMPATIBLE_HTTP_OR_AUTH'
        result['protocolReadySeconds']=time.monotonic()-boot;result['secretChecks']={'rawSaved':False,'knownPasswordAbsent':password not in json.dumps(result)};need(result['secretChecks']['knownPasswordAbsent'],'SECRET_LEAK')
    except BaseException as error:
        result.update(status='TOOL_OR_PRECONDITION_FAILED',failureType=type(error).__name__,failureCode=str(error) if isinstance(error,ValueError) else 'COMPATIBILITY_FAILED');raise
    finally:
        result['elapsedSeconds']=time.monotonic()-started;atomic(folder/'compatibility.json',result)

def cleanup():
    from measurement_resources import ResourceScope,atomic,no_links
    folder=Path(os.environ['CASE_DIRECTORY'])
    for slot in ['http-broker','http-tools']:
        if (folder/('resources-'+slot+'.json')).exists():(inventory_scope(folder) if slot=='http-tools' else ResourceScope(folder,slot)).cleanup()
    work=folder/'work';no_links(work);need(work.resolve()==folder.resolve()/'work','WORK_SCOPE')
    if work.exists():shutil.rmtree(work)
    atomic(folder/'cleanup-proof.json',{'case_id':folder.name,'manifest_sha256':os.environ['CASE_MANIFEST_SHA256'],'clean':True,'resources_remaining':[]})

if __name__=='__main__':
    try:
        p=argparse.ArgumentParser();p.add_argument('role',choices=['job','cleanup']);args=p.parse_args();os.umask(0o077);(job if args.role=='job' else cleanup)()
    except BaseException as error:print(json.dumps({'code':'COMPATIBILITY_FAILED','failureType':type(error).__name__}));sys.exit(1)
