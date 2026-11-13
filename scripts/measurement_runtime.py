"""C.3 host health gate and read-only cgroup sampling. No secrets or Docker socket in tool."""
import datetime as dt
import json
import os
import re
from pathlib import Path
import time

SERVICES={'mysql','app','nginx','rating-redis','coupon-redis','rabbitmq','seaweed'}

def need(value,code):
    if not value:raise ValueError(code)

def host_memory():
    rows={line.split(':')[0]:int(line.split()[1])*1024 for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith(('MemTotal:','MemAvailable:','SwapTotal:','SwapFree:'))}
    rows['oomKillCount']=int(dict(line.split() for line in Path('/proc/vmstat').read_text().splitlines())['oom_kill'])
    return rows

def start_ticks(text):return int(text[text.rfind(')')+2:].split()[19])

def probe_unix(text):
    # Docker emits nanoseconds. Python 3.10 fromisoformat accepts at most microseconds.
    normalized=re.sub(r'(\.\d{6})\d+(?=Z|[+-])',r'\1',text)
    return dt.datetime.fromisoformat(normalized.replace('Z','+00:00')).timestamp()

def group_path(text,identity,root=Path('/sys/fs/cgroup')):
    lines=[line[3:] for line in text.splitlines() if line.startswith('0::')]
    need(len(lines)==1 and lines[0].startswith('/') and identity in Path(lines[0]).name,'CGROUP_IDENTITY')
    path=root/lines[0].lstrip('/');need(path.resolve().is_relative_to(root.resolve()) and '..' not in Path(lines[0]).parts,'CGROUP_SCOPE')
    return path

def cgroup_sample(info):
    identity=info['Id'];pid=info['State']['Pid'];need(pid>0,'CONTAINER_PID')
    proc=Path('/proc')/str(pid);before=start_ticks((proc/'stat').read_text());group=group_path((proc/'cgroup').read_text(),identity)
    scalar=lambda name:int((group/name).read_text().strip())
    fields=lambda name:{k:int(v) for k,v in (line.split() for line in (group/name).read_text().splitlines())}
    memory=scalar('memory.current');events=fields('memory.events');cpu=fields('cpu.stat')
    need(scalar('memory.max')==info['HostConfig']['Memory'] and scalar('memory.swap.max')==0,'CGROUP_LIMIT')
    need((group/'cpuset.cpus.effective').read_text().strip() in ('0-1','0,1'),'CGROUP_CPU')
    value={'pid':pid,'startTicks':before,'memoryCurrentBytes':memory,'memoryPeakBytes':scalar('memory.peak') if (group/'memory.peak').exists() else None,
           'swapCurrentBytes':scalar('memory.swap.current'),'oom':events['oom'],'oomKill':events['oom_kill'],'cpuUsageUsec':cpu['usage_usec'],'pidsCurrent':scalar('pids.current')}
    need(before==start_ticks((proc/'stat').read_text()) and group==group_path((proc/'cgroup').read_text(),identity),'PID_CHANGED_DURING_SAMPLE')
    return value

class HealthGate:
    """End-time deduplication; never count the same completed Docker probe twice."""
    def __init__(self):self.probes={};self.anchors={};self.stop_code=''
    def ingest(self,rows,require_all=True):
        names={r['service'] for r in rows}
        if require_all and (names!=SERVICES or len(rows)!=7):self.stop_code=self.stop_code or 'SERVICE_SET_CHANGED'
        for row in rows:
            service=row['service'];anchor=(row['id'],row['pid'],row['startTicks'],row['restartCount'])
            if service in self.anchors and self.anchors[service]!=anchor:self.stop_code=self.stop_code or 'SERVICE_IDENTITY_CHANGED'
            self.anchors.setdefault(service,anchor)
            if row['oomKilled'] or row['oom'] or row['oomKill']:self.stop_code=self.stop_code or 'CONTAINER_OOM'
            if not row['running']:self.stop_code=self.stop_code or 'CONTAINER_STOPPED'
            if service=='rabbitmq':
                for probe in row['probes']:
                    end=probe['end'];old=self.probes.get(end)
                    if old is not None and old['exitCode']!=probe['exitCode']:self.stop_code=self.stop_code or 'PROBE_IDENTITY_CHANGED'
                    self.probes[end]=probe
                ordered=sorted(self.probes.values(),key=lambda p:p['end'])
                if row['health']=='unhealthy' or len(ordered)>=2 and all(p['exitCode']!=0 for p in ordered[-2:]):self.stop_code=self.stop_code or 'BROKER_PROBE_FAILED'
        return self.stop_code
    def baseline(self,rows,start_unix,elapsed):
        if elapsed<60 or self.stop_code or {r['service'] for r in rows}!=SERVICES:return False
        if any(r['health']!='healthy' for r in rows):return False
        broker=next(r for r in rows if r['service']=='rabbitmq')
        probes=[p for p in sorted(self.probes.values(),key=lambda p:p['end']) if probe_unix(p['end'])>=start_unix]
        return broker['failingStreak']==0 and len(probes)>=3 and all(p['exitCode']==0 for p in probes[-3:])

class Monitor:
    def __init__(self,scope,atomic):
        self.scope=scope;self.atomic=atomic;self.rows=[];self.gate=HealthGate();self.before=host_memory();self.tool_anchor=None
    def collect(self,phase,strict=False):
        from measurement_resources import docker
        self.scope.load();instance=self.scope.value['instance'];ids=docker('ps','-aq','--filter','label=campuslife.base.identity='+instance['identity']).splitlines()
        tools=self.scope.value['tools']
        ids += [t['container_id'] for t in tools if t['container_id']]
        infos=json.loads(docker('inspect',*ids)) if ids else [];rows=[];tool=None
        for info in infos:
            is_tool=any(t['container_id']==info['Id'] for t in tools)
            if is_tool:self.scope.check_tool(info,next(t for t in tools if t['container_id']==info['Id']))
            else:self.scope.check_base(info,'container')
            oneoff=info['Config']['Labels'].get('com.docker.compose.oneoff','').lower()=='true'
            if oneoff:continue
            state=info['State'];running=state['Running'];health=state.get('Health',{})
            probe=[{'end':p['End'],'exitCode':p['ExitCode']} for p in health.get('Log',[]) if p.get('End') and not p['End'].startswith('0001-')]
            row={'id':info['Id'],'service':'tool' if is_tool else info['Config']['Labels']['com.docker.compose.service'],
                 'running':running,'oomKilled':state['OOMKilled'],'health':health.get('Status'),'failingStreak':health.get('FailingStreak',0),'restartCount':info['RestartCount'],
                 'probes':probe,'memoryLimitBytes':info['HostConfig']['Memory']}
            if running:row.update(cgroup_sample(info))
            else:row.update(pid=0,startTicks=0,oom=int(state['OOMKilled']),oomKill=int(state['OOMKilled']))
            if is_tool:
                tool=row
                if running:
                    anchor=(row['id'],row['pid'],row['startTicks'])
                    need(self.tool_anchor in (None,anchor),'TOOL_IDENTITY_CHANGED');self.tool_anchor=anchor
            else:rows.append(row)
        host=host_memory();stop=''
        if strict:
            stop=self.gate.ingest(rows)
            if tool and (tool['oomKilled'] or tool['oom'] or tool['oomKill']):stop=stop or 'TOOL_OOM'
            if host['oomKillCount']!=self.before['oomKillCount']:stop=stop or 'HOST_OOM'
        sample={'atUnixMillis':int(time.time()*1000),'monotonicNanos':time.monotonic_ns(),'phase':phase,'containers':rows,'tool':tool,'host':host,'stopCode':stop}
        self.rows.append(sample)
        self.atomic(self.scope.folder/'host-samples.json',self.rows)
        self.atomic(self.scope.folder/'work/output/host-state.json',{'caseId':self.scope.folder.name,'instanceId':instance['identity'],
                    'validUntilUnixMillis':int(time.time()*1000)+15000,'baselinePassed':phase=='RUN','stop':bool(stop),'code':stop})
        return sample
