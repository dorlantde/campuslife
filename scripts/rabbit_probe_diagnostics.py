"""D.7.1 bounded diagnostic primitives. Never persist arbitrary probe/HTTP output."""
import base64
import hashlib
from http.client import HTTPConnection
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time

READY='/api/health/checks/ready-to-serve-clients'
VHOST_CONTROL='/api/vhosts?disable_stats=true'
JWT=re.compile(rb'\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b')
MARKER=re.compile(rb'^__D71__ (WRAPPER|RUNNING_BEGIN|RUNNING_END|PORTS_BEGIN|PORTS_END|END)(?: ([0-9]{1,12}))?$')
SENSITIVE=re.compile(rb'cookie|authorization|password|secret|bearer',re.I)

def need(value,code):
    if not value:raise ValueError(code)

def safe_output(raw,secrets=(),limit=4096):
    raw=bytes(raw);lower=raw.lower();categories=[]
    for tag,needles in [('DOCKER_TIMEOUT',[b'healthcheck exceeded timeout']),('CLI_TIMEOUT',[b'operation timed out',b'timeout waiting']),
                        ('AUTH_FAILURE',[b'authentication failed',b'invalid challenge reply']),('CONNECTION_FAILURE',[b'nodedown',b'econnrefused',b'unable to connect']),
                        ('APPLICATION_NOT_RUNNING',[b'not running',b'not booted'])]:
        if any(n in lower for n in needles):categories.append(tag)
    sensitive=bool(JWT.search(raw) or SENSITIVE.search(raw) or any(s and s.encode() in raw for s in secrets))
    markers=[]
    for line in raw.splitlines():
        match=MARKER.fullmatch(line)
        if match:markers.append({'stage':match[1].decode(),'value':int(match[2]) if match[2] else None})
    return {'observedBytes':len(raw),'mayBeTruncated':len(raw)>=limit,'categories':categories or ['EMPTY' if not raw else 'UNKNOWN'],
            'sensitiveSuppressed':sensitive,'outputSha256':None if sensitive else hashlib.sha256(raw).hexdigest(),'markers':markers}

def probe_record(container,entry,secrets):
    need(re.fullmatch('[a-f0-9]{64}',container),'CONTAINER_ID')
    return {'containerId':container,'start':entry['Start'],'end':entry['End'],'exitCode':entry['ExitCode'],
            'output':safe_output(entry.get('Output','').encode(),secrets)}

class ProbeBook:
    def __init__(self,container,secrets=()):self.container=container;self.secrets=secrets;self.entries={}
    def add(self,info):
        need(info['Id']==self.container,'PROBE_CONTAINER_CHANGED')
        for entry in info['State'].get('Health',{}).get('Log',[]):
            if entry['End'].startswith('0001-'):continue
            record=probe_record(self.container,entry,self.secrets);key=record['start']+'|'+record['end']
            need(key not in self.entries or self.entries[key]==record,'PROBE_RESULT_CHANGED');self.entries[key]=record
    def snapshot(self):return sorted(self.entries.values(),key=lambda e:(e['end'],e['start']))

def manual_script(kind):
    need(kind in ('combined','running','ports'),'MANUAL_KIND')
    lines=['printf "__D71__ WRAPPER %s\\n" "$$"']
    for stage,command in [('RUNNING','check_running'),('PORTS','check_port_connectivity')]:
        if kind=='ports' and stage=='RUNNING' or kind=='running' and stage=='PORTS':continue
        lines += ['printf "__D71__ '+stage+'_BEGIN\\n"','gosu rabbitmq rabbitmq-diagnostics -q '+command,'result=$?',
                  'printf "__D71__ '+stage+'_END %s\\n" "$result"','if [ "$result" -ne 0 ]; then exit "$result"; fi']
    return '\n'.join(lines+['printf "__D71__ END 0\\n"'])+'\n'

def manual(container,kind,secrets):
    """Five seconds from dispatch, including exec overhead. Kill only the owned client on expiry."""
    need(re.fullmatch('[a-f0-9]{64}',container),'CONTAINER_ID');script=manual_script(kind).encode();at=time.monotonic()
    p=subprocess.Popen(['docker','exec','-i',container,'sh','-s'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    chunks=bytearray();arrivals=[];count=[0];lock=threading.Lock()
    def read():
        try:
            while True:
                block=os.read(p.stdout.fileno(),1024)
                if not block:return
                with lock:
                    count[0]+=len(block);chunks.extend(block[:max(0,8192-len(chunks))])
                    if len(arrivals)<256:arrivals.append({'receivedNanos':time.monotonic_ns(),'bytes':len(block)})
        except (OSError,ValueError):return
    reader=threading.Thread(target=read,daemon=True);reader.start()
    p.stdin.write(script);p.stdin.close();expired=False
    try:p.wait(timeout=max(.001,5-(time.monotonic()-at)))
    except subprocess.TimeoutExpired:expired=True;p.kill();p.wait(timeout=1)
    reader.join(1);p.stdout.close()
    with lock:summary=safe_output(bytes(chunks),secrets,8192);total=count[0];received=list(arrivals)
    return {'kind':kind,'outerLimitSeconds':5,'hostElapsedSeconds':time.monotonic()-at,'clientExitCode':p.returncode,
            'outerTimedOut':expired,'clientExited':p.poll() is not None,'outputReaderExited':not reader.is_alive(),
            'totalOutputBytes':total,'output':summary,'outputArrivals':received,'containerChildrenExitProven':False}

def decode_http(status,body,kind,vhost):
    result={'httpStatus':status,'available':False}
    if status!=200:return result
    try:value=json.loads(body)
    except (ValueError,TypeError):return dict(result,error='INVALID_JSON')
    if kind=='vhost':
        # The single-vhost resource requires administrator in 4.3.6. The
        # collection is filtered to the management user's visible vhosts.
        if not isinstance(value,list) or not all(isinstance(v,dict) and isinstance(v.get('name'),str) for v in value):
            return dict(result,error='INVALID_SHAPE')
        matches=sum(v['name']==vhost for v in value)
        return dict(result,available=matches==1,nameMatches=matches==1)
    if not isinstance(value,dict):return dict(result,error='INVALID_SHAPE')
    if kind=='overview':return dict(result,available=value.get('rabbitmq_version')=='4.3.6',versionMatches=value.get('rabbitmq_version')=='4.3.6')
    return dict(result,available=value.get('status')=='ok',statusOk=value.get('status')=='ok')

def _http_result(host,kind,vhost,credential):
    conn=None
    try:
        path={'ready':READY,'vhost':VHOST_CONTROL,'overview':'/api/overview'}[kind]
        headers={'Accept':'application/json'}
        if credential is not None:headers['Authorization']='Basic '+base64.b64encode(('campuslife_ops:'+credential).encode()).decode()
        conn=HTTPConnection(host,15672,timeout=1.8);conn.request('GET',path,headers=headers);reply=conn.getresponse();body=reply.read(256*1024+1)
        result={'available':False,'error':'BODY_LIMIT'} if len(body)>256*1024 else decode_http(reply.status,body,kind,vhost)
        return result
    except BaseException as error:
        return {'available':False,'error':'HTTP_UNAVAILABLE','errorType':type(error).__name__}
    finally:
        if conn:conn.close()

def bounded_json_process(args,payload,seconds):
    at=time.monotonic();process=subprocess.Popen(args,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    try:
        out,_=process.communicate(payload,timeout=max(.001,seconds-(time.monotonic()-at)))
        if process.returncode!=0 or len(out)>8192:return {'available':False,'error':'HTTP_CHILD_FAILED'}
        result=json.loads(out);need(isinstance(result,dict) and isinstance(result.get('available'),bool),'HTTP_CHILD_SHAPE');return result
    except subprocess.TimeoutExpired:
        process.kill();process.communicate(timeout=.5);return {'available':False,'error':'HTTP_TOTAL_DEADLINE','childExited':True}
    except (ValueError,TypeError):return {'available':False,'error':'HTTP_CHILD_INVALID_JSON'}

def http(host,kind,vhost,credential):
    need(re.fullmatch(r'\d{1,3}(?:\.\d{1,3}){3}',host) and kind in ('ready','vhost','overview') and re.fullmatch('campuslife_[a-f0-9]{32}',vhost),'HTTP_SCOPE')
    at=time.monotonic();payload=json.dumps({'host':host,'kind':kind,'vhost':vhost,'credential':credential}).encode()
    result=bounded_json_process([sys.executable,'-I',str(Path(__file__).resolve()),'__http'],payload,2);result['hostElapsedSeconds']=time.monotonic()-at;return result

def process_rows(group):
    rows=[];unavailable=0
    for pid in (group/'cgroup.procs').read_text().splitlines()[:256]:
        try:
            text=(Path('/proc')/pid/'stat').read_text();parts=text[text.rfind(')')+2:].split()
            status=(Path('/proc')/pid/'status').read_text();ns=next((s.split()[1:] for s in status.splitlines() if s.startswith('NSpid:')),[])
            comm=text[text.find('(')+1:text.rfind(')')]
            rows.append({'pid':int(pid),'startTicks':int(parts[19]),'ppid':int(parts[1]),'comm':comm if re.fullmatch('[A-Za-z0-9_.:-]{1,32}',comm) else 'OTHER','namespacePid':int(ns[-1]) if ns else None})
        except (OSError,StopIteration,ValueError):unavailable+=1
    return {'processes':rows,'disappearedOrUnreadable':unavailable}

def resource_row(info):
    from measurement_runtime import group_path,start_ticks
    pid=info['State']['Pid'];need(pid>0 and info['State']['Running'],'RESOURCE_NOT_RUNNING');proc=Path('/proc')/str(pid)
    ticks=start_ticks((proc/'stat').read_text());group=group_path((proc/'cgroup').read_text(),info['Id'])
    scalar=lambda n:int((group/n).read_text());fields=lambda n:{k:int(v) for k,v in (s.split() for s in (group/n).read_text().splitlines())}
    need(scalar('memory.max')==info['HostConfig']['Memory'] and scalar('memory.swap.max')==0,'CGROUP_LIMIT')
    value={'id':info['Id'],'pid':pid,'startTicks':ticks,'memoryCurrentBytes':scalar('memory.current'),'memoryPeakBytes':scalar('memory.peak') if (group/'memory.peak').exists() else None,
           'memoryLimitBytes':info['HostConfig']['Memory'],'swapCurrentBytes':scalar('memory.swap.current'),'events':fields('memory.events'),'cpu':fields('cpu.stat'),
           'cpuset':(group/'cpuset.cpus.effective').read_text().strip(),'processes':process_rows(group)}
    need(ticks==start_ticks((proc/'stat').read_text()) and group==group_path((proc/'cgroup').read_text(),info['Id']),'PID_CHANGED_DURING_SAMPLE');return value

if __name__=='__main__':
    try:
        need(sys.argv[1:]==['__http'],'PRIVATE_CHILD_MODE');value=json.loads(sys.stdin.buffer.read(8192))
        need(re.fullmatch(r'\d{1,3}(?:\.\d{1,3}){3}',value['host']) and value['kind'] in ('ready','vhost','overview') and re.fullmatch('campuslife_[a-f0-9]{32}',value['vhost']),'HTTP_SCOPE')
        print(json.dumps(_http_result(value['host'],value['kind'],value['vhost'],value['credential'])))
    except BaseException:print('{"available":false,"error":"HTTP_CHILD_REFUSED"}')
