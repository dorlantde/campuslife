"""C.1 real Docker ownership tests, under case_runner. No business services, DB or HTTP."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

sys.path.insert(0,str(Path(__file__).resolve().parent))
from measurement_resources import ResourceScope, Refused, atomic, docker, need, no_links, sha, IMAGE_ID


def folder():
    path=Path(os.environ['CASE_DIRECTORY']);need(path.name==os.environ['CASE_ID'],'CASE_SCOPE');return path


def record(path,value):atomic(path,value)


def create_probe(scope,wrong=False):
    item=scope.declare_tool();labels=scope.tool_labels()
    if wrong:labels['campuslife.measurement.scope']='injected-wrong'
    image=json.loads((scope.context().BUILD_RECORD).read_text())['image']
    args=['create','--name',item['name'],'--network','none','--read-only','--memory','32m','--memory-swap','32m',
          '--cap-drop','ALL','--security-opt','no-new-privileges:true','--entrypoint','/bin/sh']
    for key,value in labels.items():args+=['--label',key+'='+value]
    scope.mark_tool_dispatched()
    return docker(*args,image,'-c','sleep 60')


def worker(role,slot):
    case=folder();scope=ResourceScope(case,slot)
    if role=='partial-secret':
        module=scope.context();original=module.write_secret;written=[0]
        def wrapped(*args):
            original(*args);written[0]+=1
            if written[0]==2:
                record(case/('ready-'+slot+'.json'),{'phase':'two-private-files','count':2})
                time.sleep(60)
        module.write_secret=wrapped
        scope.initialize();return
    if role=='generated-gap':
        def gap(path):
            record(case/('ready-'+slot+'.json'),{'phase':'generated-before-register','files':len(list((path/'secrets').iterdir()))})
            time.sleep(60)
        scope.initialize(_after_generated=gap);return
    deployment=scope.initialize()
    if role=='absent-tool':
        scope.declare_tool();scope.mark_tool_dispatched()
        record(case/('proof-'+slot+'.json'),{'controlledScenario':'no-Docker-create-was-executed'})
        return
    if role=='compose-gap':
        def returned():
            record(case/('ready-'+slot+'.json'),{'phase':'compose-returned-before-journal-commit'})
            time.sleep(60)
        scope.compose('create','--pull','never','mysql',timeout=60,log=case/('create-'+slot+'.log'),_after_return=returned)
        return
    if role in ['normal','wrong-label','manifest','sentinel']:
        scope.compose('create','--pull','never','mysql',timeout=60,log=case/('create-'+slot+'.log'))
    if role in ['normal','tool-gap','wrong-label']:
        identity=create_probe(scope,role=='wrong-label')
        record(case/('witness-'+slot+'.json'),{'containerId':identity,'kind':role})
        if role=='tool-gap':
            record(case/('ready-'+slot+'.json'),{'phase':'created-before-id-record'})
            time.sleep(60);return
        if role=='normal':
            need(scope.adopt_tool()==identity,'NORMAL_ADOPTION');docker('start',identity)


def launch(slot,role,kill=False):
    case=folder();scope=ResourceScope(case,slot);scope.begin()
    record(case/('test-'+slot+'.json'),{'slot':slot,'role':role})
    with (case/('worker-'+slot+'.log')).open('wb') as log:
        p=subprocess.Popen([sys.executable,'-I',str(Path(__file__).resolve()),'worker','--slot',slot,'--scenario',role],stdout=log,stderr=subprocess.STDOUT)
        try:
            if kill:
                until=time.monotonic()+30
                while not (case/('ready-'+slot+'.json')).exists() and p.poll() is None and time.monotonic()<until:time.sleep(.05)
                need((case/('ready-'+slot+'.json')).exists(),'KILL_CHECKPOINT_NOT_REACHED')
                fd=os.pidfd_open(p.pid)
                try:signal.pidfd_send_signal(fd,signal.SIGKILL)
                finally:os.close(fd)
                need(p.wait(timeout=3)==-9,'REAL_PROCESS_KILL')
            else:need(p.wait(timeout=70)==0,'RESOURCE_WORKER_FAILED')
        finally:
            if p.poll() is None:p.kill();p.wait(timeout=3)
    return scope


def object_ids(scope):
    scope.load();inventory=scope.inventory()
    return {kind:sorted(i['Name'] if kind=='volume' else i['Id'] for i in items) for kind,items in inventory.items()}


def safe_remove_injected(case,scope):
    # Test-only repair of a deliberately malformed fixture, never a generic cleanup fallback.
    scope.load()
    for item in scope.value['tools']:
        proof=case/('repaired-'+scope.slot+'.json')
        for identity in docker('ps','-aq','--filter','name=^/'+item['name']+'$').splitlines():
            info=json.loads(docker('inspect',identity))[0]
            labels=info['Config']['Labels']
            if labels.get('campuslife.measurement.scope')=='injected-wrong':
                need(info['Name']=='/'+item['name'] and info['Image']==IMAGE_ID
                     and labels.get('campuslife.measurement.case')==case.name
                     and labels.get('campuslife.measurement.kind')=='tool','INJECTED_REPAIR_SCOPE')
                record(proof,{'injectedContainerRemoved':info['Id']})
                docker('rm','-f',info['Id'])
        if proof.exists():
            known=json.loads(proof.read_text())['injectedContainerRemoved']
            need(known==json.loads((case/('witness-'+scope.slot+'.json')).read_text())['containerId'],'INJECTED_REPAIR_WITNESS')
            need(not docker('ps','-aq','--filter','id='+known),'INJECTED_CONTAINER_REMAINS')
            item['container_id']=known;item['phase']='OBSERVED';scope.save()


def sentinel_names(case):return ('campuslife-c1-sentinel-'+case.name[5:],'campuslife-c1-volume-'+case.name[5:])


def remove_sentinel(case):
    name,volume=sentinel_names(case)
    for identity in docker('ps','-aq','--filter','name=^/'+name+'$').splitlines():
        info=json.loads(docker('inspect',identity))[0]
        need(info['Name']=='/'+name and info['Image']==IMAGE_ID and info['Config']['Labels'].get('campuslife.measurement.sentinel')==case.name,'SENTINEL_CONTAINER_SCOPE')
        docker('rm','-f',info['Id'])
    if volume in docker('volume','ls','-q','--filter','name=^'+volume+'$').splitlines():
        info=json.loads(docker('volume','inspect',volume))[0]
        need(info['Name']==volume and info['Labels'].get('campuslife.measurement.sentinel')==case.name,'SENTINEL_VOLUME_SCOPE')
        docker('volume','rm',volume)


def cleanup_all():
    case=folder();repairs=[]
    for path in sorted(case.glob('resources-*.json')):
        scope=ResourceScope(case,path.stem.removeprefix('resources-'));value=scope.load()
        role=json.loads((case/('test-'+scope.slot+'.json')).read_text())['role'] if (case/('test-'+scope.slot+'.json')).exists() else None
        if role=='wrong-label':safe_remove_injected(case,scope);repairs.append(scope.slot)
        if role=='absent-tool' and value['state']!='CLEANED':
            need(json.loads((case/('proof-'+scope.slot+'.json')).read_text())['controlledScenario']=='no-Docker-create-was-executed','INJECTED_ABSENCE_PROOF')
            value['tools']=[];scope.save()
        if role=='compose-gap' and value['pending_compose']:
            need(json.loads((case/('ready-'+scope.slot+'.json')).read_text())['phase']=='compose-returned-before-journal-commit','INJECTED_COMPOSE_PROOF')
            value['pending_compose']=False;scope.save()
        backup=case/('manifest-backup-'+scope.slot+'.json')
        if role=='manifest' and value['instance'] and backup.exists() and scope.root.exists():
            need(sha(backup)==value['instance']['manifest_sha256'],'BACKUP_IDENTITY')
            (scope.root/'.cache/deploy-base'/value['instance']['identity']/'instance.json').write_bytes(backup.read_bytes())
        escape=scope.root/'injected-link'
        if role=='symlink' and escape.is_symlink():
            need(os.readlink(escape)==str(case/'outside-sentinel.txt'),'INJECTED_LINK_SCOPE');escape.unlink()
        scope.cleanup()
    remove_sentinel(case)
    need(not docker('ps','-aq','--filter','label=campuslife.measurement.case='+case.name),'CASE_TOOL_REMAINS')
    work=case/'work';no_links(work)
    if work.exists():shutil.rmtree(work)
    record(case/'cleanup-proof.json',{'case_id':case.name,'manifest_sha256':os.environ['CASE_MANIFEST_SHA256'],'clean':True,'resources_remaining':[]})


def suite():
    case=folder();checks=[]
    def check(name,condition):
        need(condition,name);checks.append(name);record(case/'progress.json',{'checks':checks})
    need(not docker('ps','-aq') and not docker('volume','ls','-q'),'NO_EXISTING_RESOURCES')
    # Normal partial Compose + running shell probe, not a running database or application.
    normal=launch('normal','normal');before=object_ids(normal)
    check('registered-seven-service-config-with-partial-real-resources',len(normal.value['instance']['services'])==7 and len(before['container'])==2 and len(before['volume'])>=1 and len(before['network'])>=1)
    check('running-owned-probe-before-cleanup',any(json.loads(docker('inspect',i))[0]['State']['Running'] for i in before['container']))
    counts=normal.cleanup();check('normal-containers-volumes-networks-removed',counts['containers']==2 and counts['volumes']>=1 and not normal.root.exists())
    check('cleanup-idempotent-and-no-recreation',normal.cleanup()['already_clean'])
    # Two real process kills, both before resource mutation can be authorized.
    for slot,role in [('partial','partial-secret'),('generated','generated-gap')]:
        scope=launch(slot,role,kill=True);v=scope.load()
        check(slot+'-durable-preparing-before-kill',v['state']=='PREPARING' and v['instance'] is None)
        files=list(scope.root.glob('.cache/deploy-base/*/secrets/*'))
        check(slot+'-real-private-files-present',len(files)==(2 if slot=='partial' else 24))
        scope.cleanup();check(slot+'-private-root-recovered-after-sigkill',not scope.root.exists())
    gap=launch('toolgap','tool-gap',kill=True);v=gap.load();witness=json.loads((case/'witness-toolgap.json').read_text())
    check('tool-intent-durable-before-unknown-result',len(v['tools'])==1 and v['tools'][0]['container_id'] is None)
    check('unknown-created-tool-adopted-by-name-label-image',gap.adopt_tool()==witness['containerId'])
    check('adoption-did-not-create-second-container',len(object_ids(gap)['container'])==1)
    gap.cleanup();check('unknown-created-tool-cleaned',not gap.root.exists())
    absent=launch('absent','absent-tool')
    try:absent.cleanup();raise AssertionError('UNKNOWN_ABSENCE_NOT_REJECTED')
    except Refused as error:check('absent-dispatched-tool-is-not-proof-of-no-create',str(error)=='TOOL_CREATE_UNRESOLVED')
    check('unknown-absence-preserves-private-state',absent.root.exists())
    absent.load();absent.value['tools']=[];absent.save();record(case/'repaired-absent.json',{'controlledNoCreateProof':True});absent.cleanup()
    check('no-create-fixture-repaired-only-with-controlled-proof',not absent.root.exists())
    uncertain=launch('composegap','compose-gap',kill=True);before=object_ids(uncertain)
    try:uncertain.cleanup();raise AssertionError('UNKNOWN_COMPOSE_NOT_REJECTED')
    except Refused as error:check('uncommitted-compose-result-refuses-cleanup',str(error)=='COMPOSE_RESULT_UNKNOWN')
    check('unknown-compose-preserves-resource-identities',object_ids(uncertain)==before)
    uncertain.load();uncertain.value['pending_compose']=False;uncertain.save();record(case/'repaired-composegap.json',{'controlledReturnCheckpoint':True});uncertain.cleanup()
    check('compose-fixture-repair-is-explicit',not uncertain.root.exists())
    # Refuse identity ambiguity before deleting any of the otherwise valid resources.
    wrong=launch('wrong','wrong-label');wrong.load()
    instance=wrong.value['instance'];base_before={k:docker('ps','-aq','--filter','label=campuslife.base.identity='+instance['identity']) if k=='container' else docker(k,'ls','-q','--filter','label=campuslife.base.identity='+instance['identity']) for k in ['container','volume','network']}
    try:wrong.cleanup();raise AssertionError('WRONG_LABEL_NOT_REJECTED')
    except Refused as error:check('mismatched-tool-label-refuses-cleanup',str(error)=='TOOL_LABEL_IDENTITY')
    base_after={k:docker('ps','-aq','--filter','label=campuslife.base.identity='+instance['identity']) if k=='container' else docker(k,'ls','-q','--filter','label=campuslife.base.identity='+instance['identity']) for k in ['container','volume','network']}
    check('negative-preflight-preserves-all-valid-resources',base_before==base_after and wrong.root.exists())
    safe_remove_injected(case,wrong);wrong.cleanup();check('test-repair-separate-from-normal-cleanup',not wrong.root.exists() and (case/'repaired-wrong.json').exists())
    drift=launch('drift','manifest');before=object_ids(drift);v=drift.load();path=drift.root/'.cache/deploy-base'/v['instance']['identity']/'instance.json'
    backup=case/'manifest-backup-drift.json';backup.write_bytes(path.read_bytes());data=json.loads(path.read_text());data['kind']='base';path.write_text(json.dumps(data))
    try:drift.cleanup();raise AssertionError('MANIFEST_DRIFT_NOT_REJECTED')
    except Refused as error:check('manifest-drift-refuses-cleanup',str(error)=='INSTANCE_MANIFEST_DRIFT')
    path.write_bytes(backup.read_bytes());check('manifest-refusal-preserves-resource-identities',object_ids(drift)==before);drift.cleanup()
    link=ResourceScope(case,'link');link.begin();record(case/'test-link.json',{'slot':'link','role':'symlink'})
    sentinel=case/'outside-sentinel.txt';sentinel.write_text('owned-test-sentinel');(link.root/'injected-link').symlink_to(sentinel)
    try:link.cleanup();raise AssertionError('SYMLINK_NOT_REJECTED')
    except Refused as error:check('symlink-refuses-private-tree-delete',str(error)=='RESOURCE_SYMLINK')
    check('outside-sentinel-unchanged',sentinel.read_text()=='owned-test-sentinel');(link.root/'injected-link').unlink();link.cleanup()
    isolated=launch('isolated','sentinel');name,volume=sentinel_names(case)
    image=json.loads((isolated.context().BUILD_RECORD).read_text())['image']
    external=docker('create','--name',name,'--network','none','--label','campuslife.measurement.sentinel='+case.name,'--entrypoint','/bin/sh',image,'-c','exit 0')
    docker('volume','create','--label','campuslife.measurement.sentinel='+case.name,volume)
    isolated.cleanup()
    check('cleanup-does-not-prune-other-container-or-volume',json.loads(docker('inspect',external))[0]['Id']==external and json.loads(docker('volume','inspect',volume))[0]['Name']==volume)
    remove_sentinel(case)
    check('test-owned-docker-resources-fully-gone',not docker('ps','-aq') and not docker('volume','ls','-q'))
    check('all-ten-scope-journals-cleaned',len(list(case.glob('resources-*.json')))==10 and all(json.loads(p.read_text())['state']=='CLEANED' for p in case.glob('resources-*.json')))
    record(case/'checks.json',{'task':'M06.2-B.2-C.1','passed':True,'checks':checks,'businessServicesStarted':False,'databaseConnected':False,'httpRequests':0,
                              'realSigkillWindows':4,'scopeJournals':10,'sourceConfigurationChanged':False})


def main():
    parser=argparse.ArgumentParser();parser.add_argument('role',choices=['job','cleanup','worker']);parser.add_argument('--slot');parser.add_argument('--scenario');a=parser.parse_args()
    os.umask(0o077)
    if a.role=='worker':worker(a.scenario,a.slot)
    elif a.role=='cleanup':cleanup_all()
    else:suite()


if __name__=='__main__':
    try:main()
    except Exception as error:
        print(json.dumps({'failureType':type(error).__name__,'code':str(error) if isinstance(error,Refused) else 'RESOURCE_CHECK_FAILED'}));sys.exit(1)
