"""E: real cold five-volume recovery, same facts/identities, duplicate delivery, new writes."""
import hashlib
import http.client
import json
from pathlib import Path
import secrets
import time
import urllib.parse

from deploy_base import ROOT, Deployment
from deploy_recovery import backup, freeze, restore, verify_backup, logical_digest, volumes
from deploy_observability import observe
from deploy_storage import storage_request
from test_deploy_components import free_port
from test_deploy_showcase import main as source_fixture


def exercise(ctx):
    source=ctx['deployment'];result=ctx['result'];check=ctx['check'];target=None;backup_folder=None
    result['recovery']={}
    info=result['recovery']

    def sql(d, text):
        return d.compose('exec','-T','mysql','sh','-c','MYSQL_PWD="$(cat /run/secrets/DB_PASSWORD)" exec mysql --protocol=TCP --host=127.0.0.1 --user=campuslife --database=campuslife_deploy --batch --raw --skip-column-names',stdin=text)

    def request(d, method, path, body=None, token=None, key=None, raw=False, headers=None):
        conn=http.client.HTTPConnection('127.0.0.1',d.manifest['port'],timeout=30)
        supplied=dict(headers or {})
        if token:supplied['Authorization']='Bearer '+token
        if key:supplied['Idempotency-Key']=key
        if body is not None and not isinstance(body,bytes):
            supplied['Content-Type']='application/json';body=json.dumps(body).encode()
        conn.request(method,'/api/v1'+path,body,supplied);reply=conn.getresponse();data=reply.read();status=reply.status;conn.close()
        return status,data if raw else json.loads(data)

    def upload(d, name):
        boundary='recovery'+secrets.token_hex(5)
        body=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+name+'"\r\nContent-Type: application/pdf\r\n\r\n').encode()+ctx['sample']+('\r\n--'+boundary+'--\r\n').encode()
        return request(d,'POST','/files',body,ctx['token'],headers={'Content-Type':'multipart/form-data; boundary='+boundary})

    def wait(probe, description, seconds=60):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            try:
                value=probe()
                if value:return value
            except (RuntimeError,OSError):pass
            time.sleep(.4)
        raise AssertionError(description)

    def broker(d, path, body=None):
        password=(d.folder/'secrets/RABBIT_OPS_PASSWORD').read_text()
        config='silent\nshow-error\nfail\nmax-time = 10\nuser = "campuslife_ops:'+password+'"\nurl = "http://rabbitmq:15672'+path+'"\n'
        temporary=None;mount=[]
        try:
            if body is not None:
                temporary=d.folder/('broker-request-'+secrets.token_hex(8))
                temporary.write_text(json.dumps(body),encoding='utf-8');temporary.chmod(0o444)
                mount=['-v',str(temporary)+':/run/broker-request:ro']
                config+='header = "Content-Type: application/json"\nrequest = "POST"\ndata-binary = "@/run/broker-request"\n'
            return json.loads(d.compose('run','--rm','-T','--no-deps',*mount,'--entrypoint','curl','app','--config','-',stdin=config,timeout=20))
        finally:
            if temporary:temporary.chmod(0o600);temporary.unlink()

    def gate(d):
        raw=sql(d,'SELECT JSON_OBJECT("mode",mode,"phase",phase,"generation",generation,"version",control_version,"issued",(SELECT issued_count FROM coupon_campaign WHERE id=campaign_id)) FROM coupon_dispatch_gate WHERE campaign_id='+ctx['campaign']+';')
        return json.loads(raw)

    try:
        print('Preparing real LOCAL history and an expiring S3 fixture, with one app at a time.',flush=True)
        source.env['STORAGE_UPLOAD_TARGET']='LOCAL'
        source.compose('up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','60','app',timeout=90,log=source.folder/'local-fixture.log')
        status, local=upload(source,'old-local.pdf');assert status==201;local_id=local['data']['id']
        source.env['STORAGE_UPLOAD_TARGET']='S3'
        source.compose('up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','60','app',timeout=90,log=source.folder/'s3-fixture.log')
        status, doomed=upload(source,'expiring.pdf');assert status==201;doomed_id=doomed['data']['id']
        doomed_key=sql(source,'SELECT storage_key FROM file_asset WHERE id='+doomed_id+';')
        original_gate=gate(source)
        check('source-is-paused-and-drained-before-freeze', original_gate['phase']=='PAUSED' and sql(source,"SELECT COUNT(*) FROM coupon_claim_request WHERE status='PENDING'; SELECT COUNT(*) FROM message_outbox WHERE status<>'SENT';")=='0\n0')
        freeze(source)
        for attempt in [source.start, lambda:source.maintenance('coupon','Inspect',ctx['campaign'])]:
            try:attempt();raise AssertionError('Frozen source unexpectedly allowed an operation')
            except RuntimeError as error:assert 'frozen' in str(error).lower()
        check('freeze-blocks-supported-start-and-maintenance',True)
        # One authentic, already consumed event is deliberately queued again while consumers are stopped.
        packet=json.loads(sql(source,"SELECT JSON_OBJECT('id',message_id,'bodyHex',HEX(payload),'hash',payload_hash) FROM message_outbox WHERE event_type='PRINT_READY_FOR_PICKUP';"))
        packet['body']=bytes.fromhex(packet['bodyHex']).decode('utf-8')
        assert hashlib.sha256(packet['body'].encode()).hexdigest()==packet['hash']
        vhost=urllib.parse.quote(source.env['RABBIT_VHOST'],safe='')
        routed=broker(source,'/api/exchanges/'+vhost+'/campuslife.events.v1/publish',{'properties':{'message_id':packet['id'],'content_type':'application/json','content_encoding':'UTF-8','delivery_mode':2},'routing_key':'notification','payload':packet['body'],'payload_encoding':'string'})
        wait(lambda:broker(source,'/api/queues/'+vhost+'/campuslife.notification.v1').get('messages_ready',-1)==1,'Duplicate queue metric did not converge',15)
        check('known-duplicate-is-durably-queued-with-no-consumers', routed['routed'])
        sql(source,'UPDATE file_asset SET expires_at=DATE_ADD(created_at,INTERVAL 1 SECOND) WHERE id='+doomed_id+';')
        print('Taking cold backup of all five stopped volumes and private configuration.',flush=True)
        backup_folder=backup(source);info['backup']=str(backup_folder.relative_to(ROOT))
        inventory=verify_backup(backup_folder)
        check('complete-backup-has-five-archives-config-and-runtime', len(inventory['archives'])==5 and 'app.jar' in inventory['files'] and len(list((backup_folder/'private/secrets').iterdir()))==24)
        check('source-stays-stopped-and-retained', not source.resources('container',all_containers=False) and len(source.resources('volume'))==5)
        corrupted=backup_folder/'private/files.tgz'
        with corrupted.open('r+b') as f:
            byte=f.read(1);f.seek(0);f.write(bytes([byte[0]^1]))
        try:
            try:verify_backup(backup_folder);raise AssertionError('Corruption accepted')
            except RuntimeError:check('corrupt-archive-is-rejected-before-restore',True)
        finally:
            with corrupted.open('r+b') as f:f.write(byte)
        print('Restoring to a new project and five new empty volumes, keeping original data identities.',flush=True)
        target=restore(backup_folder,free_port())
        info['target']=str(target.folder.relative_to(ROOT))
        manifest_path=target.folder/'instance.json';ready_manifest=manifest_path.read_bytes()
        try:
            incomplete=dict(target.manifest,restoreState='PREPARING')
            manifest_path.write_text(json.dumps(incomplete),encoding='utf-8')
            try:Deployment(target.folder).start();raise AssertionError('Incomplete restore started')
            except RuntimeError as error:assert 'incomplete' in str(error).lower()
            check('incomplete-restore-refuses-application-start',True)
        finally:manifest_path.write_bytes(ready_manifest)
        check('restore-target-project-and-volumes-are-distinct', target.manifest['project']!=source.manifest['project'] and not (set(volumes(target).values()) & set(volumes(source).values())))
        check('persistent-node-acl-and-storage-identities-are-preserved',target.data_identity==source.data_identity and target.env['RABBIT_VHOST']==source.env['RABBIT_VHOST'] and target.manifest['storage']==source.manifest['storage'])
        # Inspect data and the queued duplicate BEFORE the application can consume or mutate it.
        target.compose('up','-d','--wait','--wait-timeout','150','mysql','rabbitmq','coupon-redis','rating-redis','seaweed',timeout=190,log=target.folder/'restored-components.log')
        check('all-33-sql-tables-match-before-application-start',logical_digest(target)==inventory['logicalSqlSha256'])
        wait(lambda:broker(target,'/api/queues/'+vhost+'/campuslife.notification.v1').get('messages_ready',-1)==1,'Restored queue metric did not converge',20)
        check('durable-duplicate-survives-cold-broker-copy',True)
        target.start()
        wait(lambda:gate(target)['phase']=='PAUSED' and gate(target)['generation']>original_gate['generation'],'Paused restoration generation did not rebuild')
        check('restored-gate-stays-paused-in-new-generation',gate(target)['mode']=='ASYNC' and gate(target)['issued']==1)
        wait(lambda:(lambda q:q.get('messages_ready',-1)==0 and q.get('messages_unacknowledged',-1)==0)(broker(target,'/api/queues/'+vhost+'/campuslife.notification.v1')),'Duplicate not acknowledged')
        queue_rows=target.compose('exec','-T','rabbitmq','gosu','rabbitmq','rabbitmqctl','-q','list_queues','-p',target.env['RABBIT_VHOST'],'name','messages_ready','messages_unacknowledged')
        check('redelivered-original-message-is-not-dead-lettered',any(line.split()==['campuslife.notification.dead.v1','0','0'] for line in queue_rows.splitlines()))
        check('duplicate-redelivery-keeps-one-notification-and-consumption',sql(target,"SELECT COUNT(*) FROM notification; SELECT COUNT(*) FROM message_consumption WHERE consumer_name='notification-v1'; SELECT COUNT(*) FROM payment_record; SELECT COUNT(*) FROM order_coupon;")=='1\n1\n1\n1')
        check('old-session-s3-and-local-content-are-restored',request(target,'GET','/files/'+ctx['file']['id']+'/content',token=ctx['token'],raw=True)[1]==ctx['sample'] and request(target,'GET','/files/'+local_id+'/content',token=ctx['token'],raw=True)[1]==ctx['sample'])
        wait(lambda:sql(target,'SELECT status FROM file_asset WHERE id='+doomed_id+';')=='DELETED','Original cleaner did not process elapsed fixture')
        check('elapsed-original-file-deadline-cleans-exact-restored-object',storage_request(target,'HEAD',target.manifest['storage']['namespace']+'/objects/'+doomed_key,admin=True)[0]==404)
        attempts=[]
        for _ in range(3):
            status,newfile=upload(target,'restored-new-write.pdf');attempts.append({'status':status,'code':newfile.get('code')})
            if status==201:break
            if status!=503:break
            time.sleep(2)
        info['newWriteAttempts']=attempts
        check('restored-new-s3-write-is-fully-readable-before-release',status==201 and request(target,'GET','/files/'+newfile['data']['id']+'/content',token=ctx['token'],raw=True)[1]==ctx['sample'])
        current=gate(target)
        target.maintenance('coupon','Resume',ctx['campaign'],str(current['version']),upgraded=True)
        name='recover_s_'+secrets.token_hex(5)
        assert request(target,'POST','/auth/register',{'username':name,'password':ctx['password'],'nickname':'虚构恢复同学'})[0]==201
        token=request(target,'POST','/auth/login',{'username':name,'password':ctx['password']})[1]['data']['accessToken']
        status,accepted=request(target,'POST','/coupon-claims',{'campaignId':ctx['campaign']},token,'recover-claim')
        check('controlled-resume-accepts-a-new-202',status==202)
        cid=accepted['data']['id']
        wait(lambda:request(target,'GET','/coupon-claims/'+cid,token=token)[1]['data']['status']=='SUCCEEDED','New issuance after full recovery failed')
        check('new-issuance-completes-with-original-stock-and-funds',sql(target,'SELECT issued_count FROM coupon_campaign WHERE id='+ctx['campaign']+'; SELECT COUNT(*) FROM user_coupon; SELECT COUNT(*) FROM payment_record;')=='2\n2\n1')
        rated=request(target,'GET','/shops/'+ctx['shop']['shopId'])[1]['data']
        check('disposable-rating-cache-rebuilds-and-management-is-ready',rated['reviewCount']==1 and observe(target,'Snapshot')['readiness']['httpStatus']==200)
        check('source-stays-offline-retained-and-backup-verified-after-target-writes',not source.resources('container',all_containers=False) and len(source.resources('volume'))==5 and verify_backup(backup_folder)['logicalSqlSha256']==inventory['logicalSqlSha256'])
        info['finalSnapshot']=observe(target,'Snapshot');info['archiveSummary']=inventory['archives']
        result.update(passed=True,jarSha256=source.manifest['jarSha256'],operations=75)
        print('PASS: '+str(len(result['checks']))+' source/recovery groups.',flush=True)
    finally:
        if target:
            try:target.compose('logs','--no-color',timeout=30,log=target.folder/'recovery-services.log')
            finally:
                target.destroy_test();info['targetCleanup']={'containers':0,'volumes':0,'networks':0,'generatedSecretsRemoved':True}


def compatibility_regression():
    from deploy_base import initialize
    for mode, count, secrets_count in [('base',3,3),('components',6,13),('showcase',7,23)]:
        folder=initialize(free_port(),test=True,mode=mode)
        deployment=Deployment(folder);record={'task':'M06.1-E identity compatibility','mode':mode}
        try:
            config=deployment.validate()
            assert len(config['services'])==count and len(list((folder/'secrets').iterdir()))==secrets_count
            assert deployment.data_identity==deployment.manifest['identity']
            if mode!='base':
                assert config['services']['rabbitmq']['hostname']=='campuslife-'+deployment.manifest['identity']
            path=folder/'instance.json';original=path.read_bytes()
            try:
                path.write_text(json.dumps(dict(deployment.manifest,dataIdentity='a'*32)),encoding='utf-8')
                try:Deployment(folder);raise AssertionError('Unapproved data identity accepted')
                except RuntimeError as error:assert 'explicit isolated restores' in str(error)
            finally:path.write_bytes(original)
            record.update(passed=True,checks=['ordinary-mode-keeps-original-identity-and-rejects-implicit-swap'])
        finally:
            deployment.destroy_test();record['cleanup']={'containers':0,'volumes':0,'networks':0,'generatedSecretsRemoved':True}
            (folder/'identity-compatibility.json').write_text(json.dumps(record),encoding='utf-8')
            print('Compatibility evidence: '+str(folder/'identity-compatibility.json'),flush=True)


if __name__=='__main__':
    source_fixture(exercise)
    compatibility_regression()
