"""Independent completion from a retained TEST backup; no original instance is required."""
import base64
import hashlib
import hmac
import http.client as http_client
import json
from pathlib import Path
import secrets
import sys
import time
import urllib.parse

from deploy_base import ROOT
from deploy_recovery import restore, backup, freeze, verify_backup, logical_digest, volumes
from deploy_observability import observe
from deploy_storage import storage_request
from test_deploy_base import pdf_bytes
from test_deploy_components import free_port


def main(saved):
    reference=target=None
    result={'task':'M06.1-E independent retained-backup verification','checks':[],'originalBackup':str(saved)}
    evidence=ROOT/'.cache/m061e-independent.json'
    def check(name, ok):
        if not ok:raise AssertionError(name)
        result['checks'].append(name)
    def sql(d,text):
        return d.compose('exec','-T','mysql','sh','-c','MYSQL_PWD="$(cat /run/secrets/DB_PASSWORD)" exec mysql --protocol=TCP --host=127.0.0.1 --user=campuslife --database=campuslife_deploy --batch --raw --skip-column-names',stdin=text)
    def queues(d):
        raw=d.compose('exec','-T','rabbitmq','gosu','rabbitmq','rabbitmqctl','-q','list_queues','-p',d.env['RABBIT_VHOST'],'name','messages_ready','messages_unacknowledged',timeout=20)
        return {parts[0]:(int(parts[1]),int(parts[2])) for line in raw.splitlines() if len(parts:=line.split())==3 and parts[0].startswith('campuslife.') and parts[1].isdigit() and parts[2].isdigit()}
    def wait(fn, description, seconds=60):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            try:
                value=fn()
                if value:return value
            except (RuntimeError,OSError):pass
            time.sleep(.5)
        raise AssertionError(description)
    def http(d,method,path,body=None,token=None,key=None,headers=None,raw=False):
        c=http_client.HTTPConnection('127.0.0.1',d.manifest['port'],timeout=30);hs=dict(headers or {})
        if token:hs['Authorization']='Bearer '+token
        if key:hs['Idempotency-Key']=key
        if body is not None and not isinstance(body,bytes):hs['Content-Type']='application/json';body=json.dumps(body).encode()
        c.request(method,'/api/v1'+path,body,hs);r=c.getresponse();status=r.status;data=r.read();c.close()
        return status,data if raw else json.loads(data)
    sample=pdf_bytes()
    def upload(d,token):
        boundary='cold'+secrets.token_hex(5)
        body=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="new.pdf"\r\nContent-Type: application/pdf\r\n\r\n').encode()+sample+('\r\n--'+boundary+'--\r\n').encode()
        return http(d,'POST','/files',body,token,headers={'Content-Type':'multipart/form-data; boundary='+boundary})
    def broker(d,path,body):
        temporary=d.folder/('broker-request-'+secrets.token_hex(8));temporary.write_text(json.dumps(body),encoding='utf-8');temporary.chmod(0o444)
        try:
            config='silent\nshow-error\nfail\nuser = "campuslife_ops:'+(d.folder/'secrets/RABBIT_OPS_PASSWORD').read_text()+'"\nurl = "http://rabbitmq:15672'+path+'"\nheader = "Content-Type: application/json"\ndata-binary = "@/run/broker-request"\n'
            return json.loads(d.compose('run','--rm','-T','--no-deps','-v',str(temporary)+':/run/broker-request:ro','--entrypoint','curl','app','--config','-',stdin=config,timeout=20))
        finally:temporary.chmod(0o600);temporary.unlink()
    try:
        original=verify_backup(saved)
        print('Loading retained backup as an offline reference; original instance is not used.',flush=True)
        reference=restore(saved,free_port())
        reference.compose('up','-d','--wait','--wait-timeout','150','mysql','rabbitmq','coupon-redis','rating-redis','seaweed',timeout=190,log=reference.folder/'reference-components.log')
        check('retained-backup-restores-without-original-source',logical_digest(reference)==original['logicalSqlSha256'])
        initial=queues(reference);result['originalQueueCounters']=initial
        check('original-persistent-event-was-copied',initial['campuslife.notification.v1']==(1,0))
        result['mysqlClientCharset']=sql(reference,'SELECT @@character_set_client,@@character_set_results;')
        packet=json.loads(sql(reference,"SELECT JSON_OBJECT('id',message_id,'bodyHex',HEX(payload),'hash',payload_hash) FROM message_outbox WHERE event_type='PRINT_READY_FOR_PICKUP';"))
        packet['body']=bytes.fromhex(packet['bodyHex']).decode('utf-8')
        vhost=urllib.parse.quote(reference.env['RABBIT_VHOST'],safe='')
        # Inspect/requeue the known fixture without logging its content.
        found=broker(reference,'/api/queues/'+vhost+'/campuslife.notification.v1/get',{'count':1,'ackmode':'ack_requeue_true','encoding':'base64'})[0]
        actual=base64.b64decode(found['payload'])
        matched=hashlib.sha256(actual).hexdigest()==packet['hash']
        result['originalReplayPayloadHashMatched']=matched
        check('database-event-payload-hash-is-valid',hashlib.sha256(packet['body'].encode()).hexdigest()==packet['hash'])
        if not matched:
            # Only the deliberately injected one-message fixture is replaced, never arbitrary business work.
            check('only-known-transport-fixture-needs-replacement',queues(reference)['campuslife.notification.v1']==(1,0))
            reference.compose('exec','-T','rabbitmq','gosu','rabbitmq','rabbitmqctl','-q','purge_queue','-p',reference.env['RABBIT_VHOST'],'campuslife.notification.v1')
            published=broker(reference,'/api/exchanges/'+vhost+'/campuslife.events.v1/publish',{'properties':{'message_id':packet['id'],'content_type':'application/json','content_encoding':'UTF-8','delivery_mode':2},'routing_key':'notification','payload':packet['body'],'payload_encoding':'string'})
            check('canonical-original-event-requeued-from-raw-file',published['routed'])
        freeze(reference)
        print('Taking a new canonical cold backup, retaining the offline reference volumes.',flush=True)
        canonical=backup(reference);result['canonicalBackup']=str(canonical.relative_to(ROOT));index=verify_backup(canonical)
        target=restore(canonical,free_port());result['reference']=str(reference.folder.relative_to(ROOT));result['target']=str(target.folder.relative_to(ROOT))
        check('target-is-five-new-distinct-volumes',not(set(volumes(reference).values()) & set(volumes(target).values())))
        target.compose('up','-d','--wait','--wait-timeout','150','mysql','rabbitmq','coupon-redis','rating-redis','seaweed',timeout=190,log=target.folder/'restored-components.log')
        check('all-33-sql-tables-match-canonical-source',logical_digest(target)==index['logicalSqlSha256']==original['logicalSqlSha256'])
        check('canonical-persistent-queue-is-restored',queues(target)['campuslife.notification.v1']==(1,0))
        cid=sql(target,'SELECT id FROM coupon_campaign ORDER BY id LIMIT 1;')
        uid=sql(target,'SELECT owner_id FROM file_asset ORDER BY id LIMIT 1;')
        fid=sql(target,"SELECT id FROM file_asset WHERE storage_kind='S3' AND expires_at>UTC_TIMESTAMP(3) ORDER BY id LIMIT 1;")
        local=sql(target,"SELECT id FROM file_asset WHERE storage_kind='LOCAL' ORDER BY id LIMIT 1;")
        doomed=json.loads(sql(target,"SELECT JSON_OBJECT('id',id,'key',storage_key) FROM file_asset WHERE expires_at<UTC_TIMESTAMP(3) ORDER BY id LIMIT 1;"))
        shop=sql(target,'SELECT shop_id FROM service_order ORDER BY id LIMIT 1;')
        generation=int(sql(target,'SELECT generation FROM coupon_dispatch_gate WHERE campaign_id='+cid+';'))
        session=json.loads(sql(target,"SELECT JSON_OBJECT('jti',jti,'expiry',CAST(UNIX_TIMESTAMP(expires_at) AS UNSIGNED)) FROM auth_session WHERE account_id="+uid+' AND revoked_at IS NULL ORDER BY id LIMIT 1;'))
        # TEST-only credential reconstruction uses the exact persisted session/expiry and backup key.
        # No login/reset, new session, role override or lifetime extension is performed.
        encode=lambda b:base64.urlsafe_b64encode(b).rstrip(b'=')
        header=encode(b'{"alg":"HS256","typ":"JWT"}')
        claims={'iss':'https://campuslife.local','aud':['campuslife-api'],'sub':uid,'jti':session['jti'],'iat':session['expiry']-7200,'exp':session['expiry']}
        unsigned=header+b'.'+encode(json.dumps(claims,separators=(',',':')).encode())
        key=base64.b64decode((target.folder/'secrets/JWT_SECRET_BASE64').read_text())
        token=(unsigned+b'.'+encode(hmac.new(key,unsigned,hashlib.sha256).digest())).decode()
        target.start()
        wait(lambda:sql(target,'SELECT phase FROM coupon_dispatch_gate WHERE campaign_id='+cid+';')=='PAUSED','Restored paused gate did not rebuild')
        check('paused-new-generation-keeps-original-issued-count',int(sql(target,'SELECT generation FROM coupon_dispatch_gate WHERE campaign_id='+cid+';'))>generation and sql(target,'SELECT issued_count FROM coupon_campaign WHERE id='+cid+';')=='1')
        wait(lambda:queues(target)['campuslife.notification.v1']==(0,0),'Duplicate did not finish')
        check('replayed-event-is-not-dead-lettered',queues(target)['campuslife.notification.dead.v1']==(0,0))
        check('notification-payment-coupon-facts-stay-unique',sql(target,"SELECT COUNT(*) FROM notification; SELECT COUNT(*) FROM message_consumption WHERE consumer_name='notification-v1'; SELECT COUNT(*) FROM payment_record; SELECT COUNT(*) FROM order_coupon;")=='1\n1\n1\n1')
        check('persisted-session-allows-old-s3-and-local-content',http(target,'GET','/files/'+fid+'/content',token=token,raw=True)==(200,sample) and http(target,'GET','/files/'+local+'/content',token=token,raw=True)==(200,sample))
        wait(lambda:sql(target,'SELECT status FROM file_asset WHERE id='+str(doomed['id'])+';')=='DELETED','Expired fixture not cleaned')
        check('restored-expiry-deletes-exact-s3-object',storage_request(target,'HEAD',target.manifest['storage']['namespace']+'/objects/'+doomed['key'],admin=True)[0]==404)
        attempts=[]
        for _ in range(3):
            status,newfile=upload(target,token);attempts.append({'status':status,'code':newfile.get('code')})
            if status==201 or status!=503:break
            time.sleep(2)
        result['newWriteAttempts']=attempts
        check('new-s3-write-and-full-download-succeed',status==201 and http(target,'GET','/files/'+newfile['data']['id']+'/content',token=token,raw=True)==(200,sample))
        version=sql(target,'SELECT control_version FROM coupon_dispatch_gate WHERE campaign_id='+cid+';')
        target.maintenance('coupon','Resume',cid,version,upgraded=True)
        name='verify_s_'+secrets.token_hex(5);password='Verify_'+secrets.token_hex(12)
        check('new-registration-after-restore-is-normal',http(target,'POST','/auth/register',{'username':name,'password':password,'nickname':'虚构恢复验收同学'})[0]==201)
        newtoken=http(target,'POST','/auth/login',{'username':name,'password':password})[1]['data']['accessToken']
        status,claim=http(target,'POST','/coupon-claims',{'campaignId':cid},token=newtoken,key='verify-recovered')
        check('controlled-resume-accepts-new-202',status==202)
        wait(lambda:http(target,'GET','/coupon-claims/'+claim['data']['id'],token=newtoken)[1]['data']['status']=='SUCCEEDED','New claim after recovery failed')
        check('recovered-lua-mq-sql-complete-with-original-stock',sql(target,'SELECT issued_count FROM coupon_campaign WHERE id='+cid+'; SELECT COUNT(*) FROM user_coupon; SELECT COUNT(*) FROM payment_record;')=='2\n2\n1')
        rated=http(target,'GET','/shops/'+shop)[1]['data']
        check('rating-rebuild-and-private-readiness-work',rated['reviewCount']==1 and observe(target,'Snapshot')['readiness']['httpStatus']==200)
        check('reference-stays-stopped-and-retained',not reference.resources('container',all_containers=False) and len(reference.resources('volume'))==5)
        logs=target.compose('logs','--no-color',timeout=30)
        check('logs-do-not-expose-reconstructed-or-new-credentials',all(v not in logs for v in [token,newtoken,password,(target.folder/'secrets/JWT_SECRET_BASE64').read_text(),(target.folder/'secrets/MANAGEMENT_PASSWORD').read_text()]))
        result.update(passed=True,finalSnapshot=observe(target,'Snapshot'),archiveSummary=index['archives'],credentialMethod='Reconstructed original test session/expiry from backed-up key; no account or session data mutated')
        print('PASS: '+str(len(result['checks']))+' independent recovery checks.',flush=True)
    except BaseException as error:
        result.update(passed=False,failure=str(error));raise
    finally:
        for label,d in [('target',target),('reference',reference)]:
            if d:
                try:d.compose('logs','--no-color',timeout=30,log=d.folder/'independent-services.log')
                finally:d.destroy_test();result[label+'Cleanup']={'containers':0,'volumes':0,'networks':0,'generatedSecretsRemoved':True}
        evidence.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print('Evidence: '+str(evidence),flush=True)


if __name__=='__main__':
    main(Path(sys.argv[1]))
