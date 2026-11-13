"""Case-owned resource ledger for measurement. Linux/one case per process, no global prune.

Reuse the pinned deployment implementation with its *in-memory* output ROOT bound
to a pre-registered case subtree. Formal source/config/build records never change.
"""
import fcntl
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

RELEASE=Path('/home/campusdeploy/campuslife/releases/1cf0cb53a24a')
JAR_SHA='6311c16a12b2a756353150cefd0ac9a2e7074194a8012204326c85df3df3bd12'
IMAGE_ID='sha256:7656904023ca692349cf4606576c06d271ea9cd9588ec7c0000c3fbcfae291d6'


class Refused(ValueError):
    pass


def need(value,code):
    if not value:raise Refused(code)


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def atomic(path,value):
    need(not path.is_symlink(),'JOURNAL_SYMLINK')
    fd,temporary=tempfile.mkstemp(prefix='.resource-pending-',dir=path.parent)
    try:
        with os.fdopen(fd,'w',encoding='utf-8') as stream:
            json.dump(value,stream,sort_keys=True);stream.write('\n');stream.flush();os.fsync(stream.fileno())
        os.replace(temporary,path)
        fd=os.open(path.parent,os.O_DIRECTORY)
        try:os.fsync(fd)
        finally:os.close(fd)
    finally:
        if os.path.exists(temporary):os.unlink(temporary)


def docker(*args):
    p=subprocess.run(['docker',*args],cwd=RELEASE,capture_output=True,text=True,timeout=30)
    if p.returncode:raise Refused('DOCKER_OPERATION_FAILED')
    return p.stdout.strip()


def no_links(path):
    need(not path.is_symlink(),'RESOURCE_SYMLINK')
    if path.exists():
        for parent,dirs,files in os.walk(path,followlinks=False):
            for name in dirs+files:need(not (Path(parent)/name).is_symlink(),'RESOURCE_SYMLINK')


class ResourceScope:
    def __init__(self,folder,slot):
        self.folder=Path(folder)
        need(sys.platform=='linux' and re.fullmatch(r'[a-z][a-z0-9-]{0,23}',slot),'RESOURCE_SLOT')
        need(self.folder.name==os.environ.get('CASE_ID') and self.folder.parent.name=='measurement-cases','RESOURCE_CASE')
        need(self.folder.is_absolute() and self.folder==self.folder.resolve(),'RESOURCE_CASE_PATH')
        value=self.folder
        for _ in range(4):need(not value.is_symlink(),'RESOURCE_CASE_SYMLINK');value=value.parent
        info=self.folder.stat();need(info.st_uid==os.getuid() and info.st_mode&0o077==0,'PRIVATE_CASE_REQUIRED')
        self.manifest_sha=sha(self.folder/'manifest.json')
        need(self.manifest_sha==os.environ.get('CASE_MANIFEST_SHA256'),'RESOURCE_MANIFEST')
        self.slot=slot;self.root=self.folder/'work/scopes'/slot/'deployment-root'
        self.path=self.folder/('resources-'+slot+'.json')
        self.lock_path=self.folder/('resources-'+slot+'.lock')
        self.value=None

    def confined(self):
        value=self.folder
        for part in self.root.relative_to(self.folder).parts:
            value=value/part;need(not value.is_symlink(),'RESOURCE_SYMLINK')
        need(self.root.resolve().is_relative_to(self.folder/'work'),'ROOT_ESCAPES_CASE')

    def locked(self):
        fd=os.open(self.lock_path,os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
        try:fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BaseException:os.close(fd);raise Refused('RESOURCE_BUSY')
        return fd

    def load(self):
        need(not self.path.is_symlink(),'JOURNAL_SYMLINK')
        self.value=json.loads(self.path.read_text())
        need(self.value['schema']==1 and self.value['case_id']==self.folder.name
             and self.value['manifest_sha256']==self.manifest_sha and self.value['slot']==self.slot,'JOURNAL_IDENTITY')
        return self.value

    def save(self):atomic(self.path,self.value)

    def begin(self):
        fd=self.locked()
        try:
            self.confined()
            need(not self.path.exists() and not self.root.exists(),'RESOURCE_ALREADY_DECLARED')
            self.value={'schema':1,'case_id':self.folder.name,'manifest_sha256':self.manifest_sha,
                        'slot':self.slot,'state':'PREPARING','instance':None,'tools':[],'pending_compose':False}
            self.save() # Durable ownership precedes any directory or generated secret.
            self.root.mkdir(parents=True,mode=0o700)
        finally:os.close(fd)

    def context(self):
        source=json.loads((RELEASE/'source-manifest.json').read_text())
        need(len(source['files'])==463 and all(sha(RELEASE/i['path'])==i['sha256'] for i in source['files']),'FORMAL_SOURCE')
        need(sha(RELEASE/'target/campuslife-0.0.1-SNAPSHOT.jar')==JAR_SHA,'FORMAL_JAR')
        sys.path.insert(0,str(RELEASE/'scripts'))
        module=importlib.import_module('deploy_base')
        need(Path(module.__file__).resolve()==RELEASE/'scripts/deploy_base.py','DEPLOY_MODULE')
        previous=getattr(module,'_measurement_scope',None)
        need(previous in [None,str(self.root)],'ONE_SCOPE_PER_PROCESS')
        module._measurement_scope=str(self.root);module.ROOT=self.root
        # COMPOSE and BUILD_RECORD retain their original immutable absolute locations.
        need(module.COMPOSE==RELEASE/'deploy/base/compose.yaml' and module.BUILD_RECORD==RELEASE/'.cache/deploy-base-build.json','FORMAL_CONFIGURATION')
        return module

    def initialize(self,_after_generated=None):
        fd=self.locked()
        try:
            value=self.load();self.confined();need(value['state']=='PREPARING','INITIALIZATION_NOT_REPEATABLE');no_links(self.root)
            module=self.context()
            folder=module.initialize(18081,test=True,mode='showcase',observe=True,resource_profile='compact4g')
            if _after_generated:_after_generated(folder)
            deployment=module.Deployment(folder);config=deployment.validate();m=deployment.manifest
            images={name:docker('image','inspect',s['image'],'--format','{{.Id}}') for name,s in config['services'].items()}
            need(images['app']==IMAGE_ID and len(images)==7,'PINNED_SERVICES')
            value['instance']={'identity':m['identity'],'project':m['project'],'manifest_sha256':sha(folder/'instance.json'),
                               'services':images,'volumes':sorted(config['volumes']),'networks':sorted(config['networks'])}
            value['state']='REGISTERED';self.save()
            return deployment
        finally:os.close(fd)

    def deployment(self):
        value=self.load();need(value['state']=='REGISTERED' and value['instance'],'REGISTER_BEFORE_MUTATION')
        module=self.context();p=self.root/'.cache/deploy-base'/value['instance']['identity']
        need(sha(p/'instance.json')==value['instance']['manifest_sha256'],'INSTANCE_MANIFEST_DRIFT')
        return module.Deployment(p)

    def declare_tool(self):
        fd=self.locked()
        try:
            value=self.load();need(value['state']=='REGISTERED' and not value['tools'],'ONE_TOOL_INTENT')
            item={'name':'campuslife-measure-'+self.folder.name[5:]+'-'+self.slot,'image_id':IMAGE_ID,'container_id':None,'phase':'DECLARED'}
            value['tools'].append(item);self.save();return dict(item)
        finally:os.close(fd)

    def mark_tool_dispatched(self):
        fd=self.locked()
        try:
            value=self.load();need(value['state']=='REGISTERED' and len(value['tools'])==1,'TOOL_INTENT_REQUIRED')
            need(value['tools'][0]['phase']=='DECLARED','TOOL_DISPATCH_NOT_REPEATABLE')
            value['tools'][0]['phase']='DISPATCHED';self.save()
        finally:os.close(fd)

    def compose(self,*args,_after_return=None,**kwargs):
        fd=self.locked()
        try:
            value=self.load();need(value['state']=='REGISTERED' and not value['pending_compose'],'COMPOSE_RESULT_UNKNOWN')
            value['pending_compose']=True;self.save()
            result=self.deployment().compose(*args,**kwargs)
            if _after_return:_after_return()
            self.value['pending_compose']=False;self.save();return result
        finally:os.close(fd)

    def tool_labels(self):
        return {'campuslife.measurement.case':self.folder.name,'campuslife.measurement.scope':self.slot,'campuslife.measurement.kind':'tool'}

    def check_tool(self,info,item):
        need(info['Name']=='/'+item['name'] and info['Image']==item['image_id'],'TOOL_IDENTITY')
        need(all(info['Config']['Labels'].get(k)==v for k,v in self.tool_labels().items()),'TOOL_LABEL_IDENTITY')
        need(not item['container_id'] or info['Id']==item['container_id'],'TOOL_REPLACED')

    def adopt_tool(self):
        fd=self.locked()
        try:
            value=self.load();need(value['state']=='REGISTERED' and len(value['tools'])==1,'TOOL_INTENT_REQUIRED')
            item=value['tools'][0];ids=docker('ps','-aq','--filter','name=^/'+item['name']+'$').splitlines()
            if not ids:return None # Unknown means query, never repeat Docker create here.
            need(len(ids)==1,'ONE_TOOL_REQUIRED');info=json.loads(docker('inspect',ids[0]))[0];self.check_tool(info,item)
            need(item['phase'] in ['DISPATCHED','OBSERVED'],'TOOL_NOT_DISPATCHED')
            item['container_id']=info['Id'];item['phase']='OBSERVED';self.save();return info['Id']
        finally:os.close(fd)

    def check_base(self,info,kind):
        instance=self.value['instance'];labels=info['Config']['Labels'] if kind=='container' else info['Labels']
        need(all(labels.get(k)==v for k,v in [('campuslife.base.identity',instance['identity']),('campuslife.base.kind','test'),
             ('campuslife.base.mode','showcase'),('com.docker.compose.project',instance['project'])]),'BASE_LABEL_IDENTITY')
        if kind=='container':
            service=labels.get('com.docker.compose.service')
            need(service in instance['services'] and info['Image']==instance['services'][service]
                 and labels.get('campuslife.base.resources')=='compact4g','BASE_CONTAINER_IDENTITY')
        elif kind=='volume':
            name=labels.get('com.docker.compose.volume');need(name in instance['volumes'] and info['Name']==instance['project']+'_'+name,'BASE_VOLUME_IDENTITY')
        else:
            name=labels.get('com.docker.compose.network');need(name in instance['networks'] and info['Name']==instance['project']+'_'+name,'BASE_NETWORK_IDENTITY')

    def inventory(self):
        value=self.value;result={'container':[],'volume':[],'network':[]};instance=value['instance']
        if instance:
            p=self.root/'.cache/deploy-base'/instance['identity']
            need(p.is_dir() and sha(p/'instance.json')==instance['manifest_sha256'],'INSTANCE_MANIFEST_DRIFT')
            for kind in result:
                ids=docker('ps','-aq','--filter','label=campuslife.base.identity='+instance['identity']).splitlines() if kind=='container' else docker(kind,'ls','-q','--filter','label=campuslife.base.identity='+instance['identity']).splitlines()
                for identity in ids:
                    info=json.loads(docker('inspect',identity) if kind=='container' else docker(kind,'inspect',identity))[0]
                    self.check_base(info,kind);result[kind].append(info)
        else:
            # Pinned Init creates files, not Docker resources. Refuse unexpected resources,
            # even if a partial/generated manifest happens to be readable.
            for p in (self.root/'.cache/deploy-base').glob('*'):
                need(re.fullmatch('[a-f0-9]{32}',p.name) and p.is_dir(),'PARTIAL_DIRECTORY')
                for kind in result:
                    args=['ps','-aq'] if kind=='container' else [kind,'ls','-q']
                    need(not docker(*args,'--filter','label=campuslife.base.identity='+p.name),'UNREGISTERED_RESOURCES')
        declared={t['name']:t for t in value['tools']}
        candidates=set(docker('ps','-aq','--filter','label=campuslife.measurement.case='+self.folder.name,'--filter','label=campuslife.measurement.scope='+self.slot).splitlines())
        for item in value['tools']:
            found=docker('ps','-aq','--filter','name=^/'+item['name']+'$').splitlines()
            need(found or item['phase']!='DISPATCHED','TOOL_CREATE_UNRESOLVED')
            candidates.update(found)
        for identity in candidates:
            info=json.loads(docker('inspect',identity))[0];item=declared.get(info['Name'].lstrip('/'));need(item is not None,'UNREGISTERED_TOOL')
            self.check_tool(info,item);need(item['phase'] in ['DISPATCHED','OBSERVED'],'TOOL_NOT_DISPATCHED')
            if item['phase']=='DISPATCHED':item['container_id']=info['Id'];item['phase']='OBSERVED';self.save()
            result['container'].append(info)
        return result

    def cleanup(self):
        fd=self.locked()
        try:
            value=self.load();self.confined();no_links(self.root)
            need(not value['pending_compose'],'COMPOSE_RESULT_UNKNOWN')
            if value['state']=='CLEANED':
                need(not self.root.exists(),'CLEANED_ROOT_REAPPEARED')
                # Verify old IDs/labels are still absent without needing deleted files.
                saved=value['instance'];value['instance']=None
                try:
                    if saved:
                        for kind in ['container','volume','network']:
                            a=['ps','-aq'] if kind=='container' else [kind,'ls','-q']
                            need(not docker(*a,'--filter','label=campuslife.base.identity='+saved['identity']),'RESOURCE_REAPPEARED')
                    need(not self.inventory()['container'],'TOOL_REAPPEARED')
                finally:value['instance']=saved
                return {'containers':0,'volumes':0,'networks':0,'already_clean':True}
            # Preflight *all* identities and the complete private tree before deleting anything.
            inventory=self.inventory();counts={k+'s':len(v) for k,v in inventory.items()}
            for kind in ['container','network','volume']:
                for info in inventory[kind]:
                    identity=info['Id'] if kind in ['container','network'] else info['Name']
                    fresh=json.loads(docker('inspect',identity) if kind=='container' else docker(kind,'inspect',identity))[0]
                    if kind=='container' and info['Name'].lstrip('/') in {t['name'] for t in value['tools']}:
                        self.check_tool(fresh,next(t for t in value['tools'] if '/'+t['name']==info['Name']))
                    else:self.check_base(fresh,kind)
                    if kind=='volume':need(fresh['CreatedAt']==info['CreatedAt'] and fresh['Mountpoint']==info['Mountpoint'],'VOLUME_REPLACED')
                    docker('rm','-f',identity) if kind=='container' else docker(kind,'rm',identity)
            need(not any(self.inventory().values()),'RESOURCE_CLEANUP_INCOMPLETE')
            need(self.root.resolve().is_relative_to((self.folder/'work').resolve()),'ROOT_ESCAPES_CASE')
            no_links(self.root)
            if self.root.exists():shutil.rmtree(self.root)
            value['state']='CLEANED';value['cleanup']=counts;self.save();return counts
        finally:os.close(fd)
