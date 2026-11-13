"""D.6i: new formal artifact restart/claim/resource closure, never the full print lifecycle."""
import argparse
import datetime as dt
import hashlib
import http.client
import json
from pathlib import Path
import re
import secrets
import sys
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--release', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    release, output = args.release.resolve(), args.output_dir.resolve()
    if not output.is_relative_to(release/'.cache/acceptance'):
        raise ValueError('Use a fresh child of the release acceptance directory')
    output.mkdir(parents=True, exist_ok=False)
    sys.path.insert(0, str(release/'scripts'))
    from deploy_base import Deployment, docker, initialize
    from deploy_storage import initialize_storage, storage_request
    from deploy_observability import management_request, observe
    from test_deploy_cloud import CloudMonitor, native_host
    from test_deploy_compact4g import config_checks
    from test_deploy_components import free_port

    host = native_host()
    if docker('ps', '-aq') or docker('volume', 'ls', '-q'):
        raise RuntimeError('Expected no pre-existing container/volume resources')
    record = json.loads((release/'.cache/deploy-base-build.json').read_text())
    jar = release/'target/campuslife-0.0.1-SNAPSHOT.jar'
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    assert digest(jar) == record['jarSha256'] == '6311c16a12b2a756353150cefd0ac9a2e7074194a8012204326c85df3df3bd12'
    assert record['imageId'] == 'sha256:7656904023ca692349cf4606576c06d271ea9cd9588ec7c0000c3fbcfae291d6'
    config_checks()
    config = json.loads((release/'.cache/compact4g-config.json').read_text())
    folder = initialize(free_port(), docs=True, test=True, mode='showcase', observe=True,
                        resource_profile='compact4g')
    d = Deployment(folder)
    monitor = CloudMonitor(host)
    result = {'task': 'M06.1-D.6i', 'checks': [], 'passed': False, 'businessClassOverlay': False,
              'fullPrintLifecycleRun': False, 'configChecks': config['checks'], 'instance': str(folder),
              'jarSha256': record['jarSha256'], 'imageId': record['imageId'],
              'startedAt': dt.datetime.now(dt.timezone.utc).isoformat()}
    (output/'active.json').write_text(json.dumps({'instance': str(folder), 'identity': d.manifest['identity']}))
    password = 'Remaining_'+secrets.token_hex(14)
    tokens = []

    def phase(value):
        (output/'progress.json').write_text(json.dumps({'phase': value, 'instance': str(folder)}))
        print('PHASE '+value, flush=True)

    def check(name, condition):
        assert condition, name
        assert name not in result['checks']
        result['checks'].append(name)

    def wait(predicate, name, seconds=60):
        end = time.monotonic()+seconds
        while time.monotonic()<end:
            if predicate(): return
            time.sleep(.4)
        raise AssertionError('Observation timeout: '+name)

    def request(method, path, body=None, token=None, key=None):
        conn = http.client.HTTPConnection('127.0.0.1', d.manifest['port'], timeout=30)
        headers = {}
        if body is not None:
            body = json.dumps(body).encode()
            headers['Content-Type'] = 'application/json'
        if token: headers['Authorization'] = 'Bearer '+token
        if key: headers['Idempotency-Key'] = key
        conn.request(method, path, body, headers)
        response = conn.getresponse()
        value = json.loads(response.read())
        status = response.status
        conn.close()
        return status, value

    def api(method, path, body=None, token=None, key=None, expected=200):
        status, value = request(method, '/api/v1'+path, body, token, key)
        if status != expected:
            raise AssertionError('API status/code: '+str(status)+'/'+str(value.get('code')))
        return value['data']

    def sql(statement):
        return d.compose('exec', '-T', 'mysql', 'sh', '-c',
            'MYSQL_PWD="$(cat /run/secrets/DB_PASSWORD)" exec mysql --protocol=TCP --host=127.0.0.1 '
            '--user=campuslife --database=campuslife_deploy --batch --skip-column-names',
            stdin=statement, timeout=30)

    def redis(*commands):
        return d.compose('exec', '-T', 'coupon-redis', 'sh', '-c',
            'REDISCLI_AUTH="$(cat /run/secrets/COUPON_REDIS_OPS_PASSWORD)"; export REDISCLI_AUTH; '
            'exec redis-cli --user redis_ops --raw "$@"', 'remaining-read', *commands, timeout=20)

    def gate(cid):
        return sql("SELECT mode,phase,generation,control_version,COALESCE(redis_run_id,''),"
                   "rebuild_token IS NOT NULL FROM coupon_dispatch_gate WHERE campaign_id="+cid+';').split('\t')

    def ready(cid):
        def matches():
            g = gate(cid)
            run = re.search(r'^run_id:([a-f0-9]{40})\r?$', redis('INFO', 'server'), re.M)
            control = 'coupon:'+d.env['DATA_NAMESPACE']+':{'+cid+'}:control'
            return g[:2] == ['ASYNC','PAUSED'] and g[5]=='0' and run and run.group(1)==g[4] and redis(
                'HMGET', control, 'generation', 'run', 'phase').splitlines()==[g[2],g[4],'PAUSED']
        started = time.monotonic()
        wait(matches, 'SQL/Redis paused generation readiness')
        result.setdefault('readinessWaitSeconds', []).append(round(time.monotonic()-started,3))

    def action(cid, name):
        if name=='Resume': ready(cid)
        g = gate(cid)
        started = time.monotonic()
        try:
            out = d.maintenance('coupon', name, cid, g[3], upgraded=True)
        except BaseException as failure:
            # Only controlled metadata and direct SQL facts survive fixture cleanup.
            # Never persist arbitrary exception messages, command arguments or secrets.
            evidence = {'action':name,'beforeGate':g,'failureType':type(failure).__name__,
                        'elapsedSeconds':round(time.monotonic()-started,3)}
            try:
                evidence['afterGate'] = gate(cid)
                evidence['issuedCouponsPending'] = sql('SELECT issued_count FROM coupon_campaign WHERE id='+cid+'; '
                    'SELECT COUNT(*) FROM user_coupon WHERE campaign_id='+cid+'; '
                    "SELECT COUNT(*) FROM coupon_claim_request WHERE campaign_id="+cid+" AND status='PENDING';")
            except BaseException as snapshot_failure:
                evidence['snapshotFailureType'] = type(snapshot_failure).__name__
            result.setdefault('failedMaintenance', []).append(evidence)
            raise
        assert password not in out and all(t not in out for t in tokens)
        m = re.search(r'campaign=(\d+) mode=(\w+) phase=(\w+) generation=(\d+) controlVersion=(\d+) pending=(\d+) issued=(\d+) actualCoupons=(\d+)', out)
        assert m, 'Original packaged maintenance output missing'
        state = dict(zip(['id','mode','phase','generation','version','pending','issued','actual'],m.groups()))
        result.setdefault('maintenance', []).append({'action':name,'state':state,'elapsedSeconds':round(time.monotonic()-started,3)})
        return state

    def login(name):
        token = api('POST','/auth/login',{'username':name,'password':password})['accessToken']
        tokens.append(token)
        return token

    def student():
        name = 'rem_s_'+secrets.token_hex(5)
        api('POST','/auth/register',{'username':name,'password':password,'nickname':'虚构剩余验收学生'},expected=201)
        return login(name)

    def claim(cid, token, key):
        accepted = api('POST','/coupon-claims',{'campaignId':cid},token,key,expected=202)
        assert accepted['status']=='PENDING'
        rid = accepted['id']
        assert re.fullmatch('[1-9][0-9]*',rid)
        check(key+'-durable-202',sql('SELECT COUNT(*) FROM coupon_claim_request WHERE id='+rid+';')=='1')
        wait(lambda: api('GET','/coupon-claims/'+rid,token=token)['status']=='SUCCEEDED','normal asynchronous issuance')
        return api('GET','/coupon-claims/'+rid,token=token)

    try:
        monitor.hook('begin',d,result)
        result.update(task='M06.1-D.6i',validationScope='Formal packaged artifact, targeted restart/claim/resource closure; not a fourth print lifecycle or capacity test.')
        result.pop('pdfBytes',None)
        phase('initializing-and-starting-new-packaged-artifact')
        initialize_storage(d)
        d.start()
        monitor.hook('started',d,result)
        states = d.status()
        check('seven-healthy-formal-artifact-services',len(states['services'])==7 and all(s['state']=='running' and s['health']=='healthy' for s in states['services']))
        check('five-volumes-and-three-networks',states['volumes']==5 and len(d.resources('network'))==3)
        check('new-jar-image-used-without-business-overlay',all(c['Image']==record['imageId'] for c in d.resources('container') if c['Config']['Labels']['com.docker.compose.service']=='app'))
        check('original-v24-32-tables-and-no-default-admin',sql("SELECT COUNT(*) FROM account; SELECT COUNT(*) FROM flyway_schema_history WHERE success=1; SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history';")=='0\n24\n32')
        env = d.validate()['services']['app']['environment']
        check('mock-funds-remain-off',env['MOCK_PAYMENT_ENABLED']=='false')
        document = request('GET','/v3/api-docs')[1]
        operations = sum(k in ['get','post','put','patch','delete','head','options'] for v in document['paths'].values() for k in v)
        check('openapi-75-operations',operations==75)
        initial = observe(d,'Snapshot')
        check('private-observation-ready',initial['readiness']['httpStatus']==200 and initial['database']['available'] and isinstance(initial['queues'],list))
        check('management-denies-anonymous',management_request(d,'/actuator/health',authorization='')[0] in [401,403])
        deny = ['MYSQL_ROOT_PASSWORD','RABBIT_OPS_PASSWORD','COUPON_REDIS_OPS_PASSWORD','RATING_REDIS_OPS_PASSWORD','RABBIT_ERLANG_COOKIE','S3_ADMIN_SECRET_KEY','S3_CA_KEY','S3_SERVER_KEY']
        command = 'set -eu; '+''.join('test ! -e /run/secrets/'+name+'; ' for name in deny)+'echo isolated'
        check('app-does-not-mount-eight-ops-or-root-secrets',d.compose('exec','-T','app','sh','-c',command)=='isolated')

        phase('normal-virtual-shop-review-and-original-maintenance')
        token = student()
        check('student-jwt-cannot-authorize-management',management_request(d,'/actuator/health',authorization='Bearer '+token)[0] in [401,403])
        admin_name = 'rem_a_'+secrets.token_hex(5)
        d.admin(admin_name,'虚构剩余验收管理员',password)
        admin = login(admin_name)
        category = next(c['id'] for c in api('GET','/shop-categories') if c['code']=='PRINT')
        merchant_name = 'rem_m_'+secrets.token_hex(5)
        shop = api('POST','/merchant-applications',{'username':merchant_name,'password':password,'nickname':'虚构店主','categoryId':category,'shopName':'虚构定向验收店','locationText':'虚构校区'},expected=201)
        merchant = login(merchant_name)
        detail = api('GET','/admin/shops/'+shop['shopId'],token=admin)
        api('POST','/admin/shops/'+shop['shopId']+'/approve',{'expectedVersion':detail['version']},admin)
        now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
        campaign = api('POST','/merchant/coupon-campaigns',{'name':'虚构重启领券','thresholdAmount':20,'reductionAmount':5,'totalQuantity':2,
            'claimStartAt':(now-dt.timedelta(seconds=30)).isoformat(),'claimEndAt':(now+dt.timedelta(hours=1)).isoformat(),
            'useStartAt':(now-dt.timedelta(seconds=60)).isoformat(),'useEndAt':(now+dt.timedelta(hours=2)).isoformat()},merchant,expected=201)
        cid = campaign['id']
        api('POST','/merchant/coupon-campaigns/'+cid+'/publish',{'expectedVersion':campaign['version']},merchant)
        check('new-campaign-default-sync',gate(cid)[:2]==['SYNC','OPEN'])
        action(cid,'Pause'); action(cid,'Async'); action(cid,'Resume')
        first = claim(cid,token,'remaining-first')
        wait(lambda: sql("SELECT COUNT(*) FROM message_outbox WHERE status<>'SENT';")=='0','outbox publication drain')
        paused = action(cid,'Pause')
        old_gate = gate(cid)
        volumes = sorted(v['Name'] for v in d.resources('volume'))
        fingerprints = {p.name:digest(p) for p in (folder/'secrets').iterdir()}
        app_before = next(c['State']['StartedAt'] for c in d.resources('container') if c['Config']['Labels']['com.docker.compose.service']=='app')
        local_marker = d.compose('exec','-T','app','cat','/var/lib/campuslife/files/.storage-id')
        result['beforeStopSnapshot'] = observe(d,'Snapshot')
        phase('restarting-original-five-volumes')
        stopped = d.stop()
        check('all-seven-stop-retains-five-volumes',len(stopped['services'])==7 and all(s['state']=='exited' for s in stopped['services']) and stopped['volumes']==5)
        d.start()
        check('same-five-volumes-and-24-credential-fingerprints',len(fingerprints)==24 and sorted(v['Name'] for v in d.resources('volume'))==volumes and fingerprints=={p.name:digest(p) for p in (folder/'secrets').iterdir()})
        check('new-app-process-after-restart',app_before!=next(c['State']['StartedAt'] for c in d.resources('container') if c['Config']['Labels']['com.docker.compose.service']=='app'))
        ready(cid)
        rebuilt = gate(cid)
        check('paused-new-generation-keeps-issued-fact',int(rebuilt[2])>int(old_gate[2]) and sql('SELECT issued_count FROM coupon_campaign WHERE id='+cid+'; SELECT COUNT(*) FROM user_coupon;')=='1\n1')
        check('local-and-private-s3-identities-preserved',local_marker==d.compose('exec','-T','app','cat','/var/lib/campuslife/files/.storage-id') and storage_request(d,'GET','_storage-id')==(200,d.manifest['storage']['namespace']))
        check('old-session-and-original-key-replay-survive',api('POST','/coupon-claims',{'campaignId':cid},token,'remaining-first')['id']==first['id'])
        phase('formal-packaged-post-restart-resume-and-new-claim')
        resumed = action(cid,'Resume')
        check('post-restart-original-cli-resume-opens',resumed['mode']=='ASYNC' and resumed['phase']=='OPEN')
        second = student()
        claim(cid,second,'remaining-second')
        epoch = 'coupon:'+d.env['DATA_NAMESPACE']+':{'+cid+'}:g:'+gate(cid)[2]
        wait(lambda: redis('HMGET',epoch,'#total','#free','#held','#spent').splitlines()==['2','0','0','2'],'post-restart Redis settlement')
        check('new-issuance-unique-and-not-oversold',sql('SELECT issued_count FROM coupon_campaign WHERE id='+cid+'; SELECT COUNT(*) FROM user_coupon; SELECT COUNT(*) FROM message_consumption;')=='2\n2\n2')
        check('no-order-or-money-chain-run',sql('SELECT COUNT(*) FROM payment_record; SELECT COUNT(*) FROM order_coupon; SELECT COUNT(*) FROM file_asset;')=='0\n0\n0')
        result['finalSnapshot'] = observe(d,'Snapshot')
        check('post-restart-readiness-and-jvm-metrics',result['finalSnapshot']['readiness']['httpStatus']==200 and observe(d,'Metrics','jvm.memory.used')['httpStatus']==200)
        phase('final-resource-and-credential-hooks')
        monitor.hook('verified',d,result)
        logs = d.compose('logs','--no-color',timeout=30)
        check('logs-omit-fixture-password-and-all-four-tokens',all(v not in logs for v in [password,*tokens]))
        result.update(passed=True,operations=operations,services=7,volumes=5,networks=3,
                      credentialFingerprintsChecked=len(fingerprints),fixtureTokensChecked=len(tokens))
        phase('verified')
    except BaseException as failure:
        result.update(passed=False,failureType=type(failure).__name__)
        raise
    finally:
        try:
            monitor.hook('end',d,result)
            d.compose('logs','--no-color',timeout=30,log=folder/'remaining-services.log')
        finally:
            try:
                d.destroy_test()
                result['cleanup']={'containers':0,'volumes':0,'networks':0,'generatedSecretsRemoved':True}
            finally:
                result['finishedAt']=dt.datetime.now(dt.timezone.utc).isoformat()
                result['jarUnchanged']=digest(jar)==record['jarSha256']
                (output/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
                print(json.dumps({'passed':result['passed'],'checks':len(result['checks']),'cleanup':result.get('cleanup')}),flush=True)


if __name__=='__main__':
    main()
