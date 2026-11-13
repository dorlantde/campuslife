"""D: one bounded seven-service lifecycle using existing production artifact and protocols."""
import datetime as dt
import hashlib
import http.client
import json
import platform
import re
import secrets
import time

from deploy_base import ROOT, Deployment, initialize
from deploy_storage import initialize_storage, storage_request
from deploy_observability import observe
from test_deploy_base import pdf_bytes
from test_deploy_components import free_port


def main(recovery_hook=None, *, resource_profile='standard', lifecycle_hook=None):
    folder = initialize(free_port(), docs=True, test=True, mode='showcase', demo_money=True, observe=True, resource_profile=resource_profile)
    d = Deployment(folder)
    result = {'task': 'M06.1-D', 'checks': [], 'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
              'hostSystem': platform.system(), 'validationScope': 'Container lifecycle; independent host acceptance is recorded separately'}

    def check(label, condition):
        if not condition:
            raise AssertionError(label)
        assert label not in result['checks']
        result['checks'].append(label)

    def wait(probe, description, seconds=60):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                value = probe()
                if value:
                    return value
            except (RuntimeError, OSError):
                pass
            time.sleep(.3)
        raise AssertionError('Recovery timeout: ' + description)

    def request(method, path, body=None, token=None, key=None, headers=None, raw=False):
        connection = http.client.HTTPConnection('127.0.0.1', d.manifest['port'], timeout=30)
        supplied = dict(headers or {})
        if token:
            supplied['Authorization'] = 'Bearer ' + token
        if key:
            supplied['Idempotency-Key'] = key
        if body is not None and not isinstance(body, bytes):
            supplied['Content-Type'] = 'application/json'
            body = json.dumps(body).encode()
        connection.request(method, path, body, supplied)
        response = connection.getresponse()
        data = response.read()
        status = response.status
        connection.close()
        return status, data if raw else json.loads(data)

    def api(method, path, body=None, token=None, key=None):
        status, value = request(method, '/api/v1' + path, body, token, key)
        if status not in [200, 201, 202]:
            raise AssertionError(method + ' ' + path + ': ' + str(status) + ' ' + str(value.get('code')))
        return value['data']

    def sql(statement):
        return d.compose('exec', '-T', 'mysql', 'sh', '-c',
            'MYSQL_PWD="$(cat /run/secrets/DB_PASSWORD)" exec mysql --protocol=TCP --host=127.0.0.1 '
            '--user=campuslife --database=campuslife_deploy --batch --skip-column-names', stdin=statement)

    def redis(service, *commands):
        prefix = 'RATING' if service == 'rating-redis' else 'COUPON'
        return d.compose('exec', '-T', service, 'sh', '-c',
            'REDISCLI_AUTH="$(cat /run/secrets/' + prefix + '_REDIS_OPS_PASSWORD)"; export REDISCLI_AUTH; exec redis-cli --user redis_ops --raw "$@"', 'redis-observe', *commands)

    def maintenance_state(out):
        m = re.search(r'campaign=(\d+) mode=(\w+) phase=(\w+) generation=(\d+) controlVersion=(\d+) pending=(\d+) issued=(\d+) actualCoupons=(\d+)', out)
        if not m:
            raise AssertionError('Missing original campaign maintenance snapshot')
        return dict(zip(['id','mode','phase','generation','version','pending','issued','actual'], m.groups()))

    def inspect(campaign):
        return maintenance_state(d.maintenance('coupon', 'Inspect', campaign))

    def action(campaign, operation, state=None):
        if operation == 'Resume':
            # The native host may still be rebuilding the PAUSED generation after Async/restart.
            # Observe readiness only; do not write the gate, change leases, or retry a stale operator.
            def ready():
                fields = sql('SELECT mode,phase,generation,COALESCE(redis_run_id,\'\') FROM coupon_dispatch_gate WHERE campaign_id=' + campaign + ';').split('\t')
                if len(fields) != 4 or fields[:2] != ['ASYNC', 'PAUSED'] or not re.fullmatch('[a-f0-9]{40}', fields[3]):
                    return False
                run = re.search(r'^run_id:([a-f0-9]{40})\r?$', redis('coupon-redis', 'INFO', 'server'), re.M)
                control = 'coupon:' + d.env['DATA_NAMESPACE'] + ':{' + campaign + '}:control'
                values = redis('coupon-redis', 'HMGET', control, 'generation', 'run', 'phase').splitlines()
                return run is not None and run.group(1) == fields[3] and values == [fields[2], fields[3], 'PAUSED']
            started = time.monotonic()
            wait(ready, 'SQL/Redis PAUSED generation readiness before Resume')
            result.setdefault('resumeReadinessWaitSeconds', []).append(round(time.monotonic()-started, 3))
        state = state or inspect(campaign)
        return maintenance_state(d.maintenance('coupon', operation, campaign, state['version'], upgraded=True))

    password = 'Show_' + secrets.token_hex(14)
    tokens = []

    def student():
        name = 'show_s_' + secrets.token_hex(5)
        api('POST', '/auth/register', {'username': name, 'password': password, 'nickname': '虚构七服务同学'})
        token = api('POST', '/auth/login', {'username': name, 'password': password})['accessToken']
        tokens.append(token)
        return token

    def upload(token, name):
        boundary = 'showcase' + secrets.token_hex(6)
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + name + '"\r\nContent-Type: application/pdf\r\n\r\n').encode() + sample + ('\r\n--' + boundary + '--\r\n').encode()
        status, value = request('POST', '/api/v1/files', body, token, headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})
        if status != 201:
            raise AssertionError('S3 upload did not return 201: ' + str(value.get('code')))
        return value['data']

    sample = pdf_bytes(20 * 1024**2) if resource_profile == 'compact4g' else pdf_bytes()
    try:
        if lifecycle_hook:
            lifecycle_hook('begin', d, result)
        print('Initializing native TLS, then starting seven services with private observation.', flush=True)
        initialize_storage(d)
        d.start()
        if lifecycle_hook:
            lifecycle_hook('started', d, result)
        states = d.status()
        check('seven-healthy-services-with-single-app', len(states['services']) == 7 and all(s['state'] == 'running' and s['health'] == 'healthy' for s in states['services']))
        check('five-persistent-volumes-and-three-networks', states['volumes'] == 5 and len(d.resources('network')) == 3)
        check('original-schema-and-no-default-business-admin', sql("SELECT COUNT(*) FROM account; SELECT COUNT(*) FROM flyway_schema_history WHERE success=1; SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history';") == '0\n24\n32')
        snapshot = observe(d, 'Snapshot')
        check('private-management-works-in-seven-service-mode', snapshot['readiness']['httpStatus'] == 200 and snapshot['database']['available'] and isinstance(snapshot['queues'], list))
        result['initialSnapshot'] = snapshot
        document = request('GET', '/v3/api-docs')[1]
        operation_count = sum(k in ['get','post','put','patch','delete','head','options'] for path in document['paths'].values() for k in path)
        check('business-openapi-remains-75', operation_count == 75)

        print('Running async claim -> S3 print -> notification -> pickup -> rating.', flush=True)
        token = student()
        admin_name = 'show_a_' + secrets.token_hex(5)
        d.admin(admin_name, '虚构七服务管理员', password)
        admin = api('POST', '/auth/login', {'username': admin_name, 'password': password})['accessToken']; tokens.append(admin)
        merchant_name = 'show_m_' + secrets.token_hex(5)
        category = next(c['id'] for c in api('GET', '/shop-categories') if c['code'] == 'PRINT')
        shop = api('POST', '/merchant-applications', {'username': merchant_name, 'password': password, 'nickname': '虚构店主', 'categoryId': category, 'shopName': '虚构七服务打印店', 'locationText': '虚构校区'})
        merchant = api('POST', '/auth/login', {'username': merchant_name, 'password': password})['accessToken']; tokens.append(merchant)
        detail = api('GET', '/admin/shops/' + shop['shopId'], token=admin)
        approved = api('POST', '/admin/shops/' + shop['shopId'] + '/approve', {'expectedVersion': detail['version']}, admin)
        hours = api('PUT', '/merchant/shop/business-hours', {'expectedVersion': approved['version'], 'intervals': [{'weekday': i, 'startTime': '00:00', 'endTime': '23:59'} for i in range(1,8)]}, merchant)
        api('PATCH', '/merchant/shop', {'expectedVersion': hours['version'], 'businessEnabled': True}, merchant)
        service = api('POST', '/merchant/services', {'serviceType': 'PRINT', 'name': '组合打印', 'priceAmount': 10, 'durationMinutes': None, 'enabled': True}, merchant)
        api('PUT', '/merchant/services/' + service['id'] + '/print-prices', {'rules': [{'paperSize': 'A4', 'colorMode': 'BW', 'sideMode': 'SINGLE', 'pricePerImpression': 10, 'enabled': True, 'expectedVersion': None}]}, merchant)
        now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
        campaign = api('POST', '/merchant/coupon-campaigns', {'name': '虚构满20分减5分', 'thresholdAmount': 20, 'reductionAmount': 5, 'totalQuantity': 2,
            'claimStartAt': (now-dt.timedelta(seconds=30)).isoformat(), 'claimEndAt': (now+dt.timedelta(hours=1)).isoformat(),
            'useStartAt': (now-dt.timedelta(seconds=60)).isoformat(), 'useEndAt': (now+dt.timedelta(hours=2)).isoformat()}, merchant)
        cid = campaign['id']
        api('POST', '/merchant/coupon-campaigns/' + cid + '/publish', {'expectedVersion': campaign['version']}, merchant)
        initial = inspect(cid)
        check('campaign-remains-default-sync-before-explicit-transition', initial['mode'] == 'SYNC')
        action(cid, 'Pause', initial)
        action(cid, 'Async')
        action(cid, 'Resume')
        status, accepted = request('POST', '/api/v1/coupon-claims', {'campaignId': cid}, token, 'show-claim')
        check('async-admission-is-durable-202', status == 202 and accepted.get('data') is not None and sql('SELECT COUNT(*) FROM coupon_claim_request WHERE id=' + accepted['data']['id'] + ';') == '1')
        claim_id = accepted['data']['id']
        wait(lambda: api('GET', '/coupon-claims/' + claim_id, token=token)['status'] == 'SUCCEEDED', 'original coupon consumer')
        claim = api('GET', '/coupon-claims/' + claim_id, token=token)
        file = upload(token, '组合打印样本.pdf')
        check('new-print-file-uses-s3-with-complete-content', sql('SELECT storage_kind FROM file_asset WHERE id=' + file['id'] + ';') == 'S3' and request('GET', '/api/v1/files/' + file['id'] + '/content', token=token, raw=True)[1] == sample)
        quote_body = {'fileId': file['id'], 'serviceId': service['id'], 'paperSize': 'A4', 'colorMode': 'BW', 'sideMode': 'SINGLE', 'copies': 2, 'userCouponId': claim['coupon']['id']}
        quote = api('POST', '/print-quotes', quote_body, token)
        order = api('POST', '/orders/prints', dict(quote_body, expectedPriceVersion=quote['priceVersion'], expectedPayableAmount=quote['payableAmount']), token, 'show-print')
        check('real-order-uses-claimed-coupon-and-frozen-payable', order['payableAmount'] == 15)
        api('POST', '/orders/' + order['id'] + '/payments/mock', token=token)
        for op in ['accept','start','ready-for-pickup']:
            api('POST', '/merchant/orders/' + order['id'] + '/' + op, token=merchant)
        wait(lambda: api('GET', '/notifications', token=token)['total'] == 1, 'original notification consumer')
        api('POST', '/orders/' + order['id'] + '/pickup', token=token)
        api('POST', '/orders/' + order['id'] + '/review', {'rating': 5, 'content': '虚构七服务组合验收'}, token)
        rating_key = 'campuslife:' + d.env['DATA_NAMESPACE'] + ':rating:s1:' + shop['shopId'] + ':1'
        rating = api('GET', '/shops/' + shop['shopId'])
        check('review-statistics-fill-real-rating-redis', rating['reviewCount'] == 1 and rating['rating'] == 5 and json.loads(redis('rating-redis', 'GET', rating_key))['ratingSum'] == '5')
        wait(lambda: sql("SELECT COUNT(*) FROM message_outbox WHERE status<>'SENT';") == '0', 'outbox drain')
        check('funds-coupon-notification-and-consumption-are-unique', sql('SELECT COUNT(*) FROM payment_record; SELECT COUNT(*) FROM order_coupon; SELECT COUNT(*) FROM notification; SELECT COUNT(*) FROM user_coupon; SELECT COUNT(*) FROM message_consumption;') == '1\n1\n1\n1\n2')

        paused = action(cid, 'Pause')
        marker = d.compose('exec', '-T', 'app', 'cat', '/var/lib/campuslife/files/.storage-id')
        volume_ids = sorted(v['Name'] for v in d.resources('volume'))
        trust_hashes = {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (folder/'secrets').iterdir()}
        before_jvm = next(c['State']['StartedAt'] for c in d.resources('container') if c['Config']['Labels']['com.docker.compose.service'] == 'app')
        result['beforeStopSnapshot'] = observe(d, 'Snapshot')
        if recovery_hook:
            result['task'] = 'M06.1-E source fixture'
            recovery_hook(dict(deployment=d, campaign=cid, token=token, merchant=merchant, admin=admin,
                               file=file, order=order, shop=shop, sample=sample, password=password, tokens=tokens,
                               check=check, result=result))
            return
        print('Stopping all seven services, retaining volumes and identities, then restarting.', flush=True)
        started = time.monotonic()
        stopped = d.stop()
        result['stopElapsedSeconds'] = round(time.monotonic()-started,3)
        check('all-seven-stopped-with-five-volumes-retained', len(stopped['services']) == 7 and all(x['state'] == 'exited' for x in stopped['services']) and stopped['volumes'] == 5)
        started = time.monotonic()
        d.start()
        result['restartElapsedSeconds'] = round(time.monotonic()-started,3)
        check('same-five-volumes-and-24-secret-fingerprints', sorted(v['Name'] for v in d.resources('volume')) == volume_ids and len(trust_hashes) == 24 and trust_hashes == {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (folder/'secrets').iterdir()})
        after_jvm = next(c['State']['StartedAt'] for c in d.resources('container') if c['Config']['Labels']['com.docker.compose.service'] == 'app')
        check('new-application-process-after-whole-stack-stop', before_jvm != after_jvm)
        wait(lambda: sql('SELECT phase FROM coupon_dispatch_gate WHERE campaign_id=' + cid + ';') == 'PAUSED' and sql('SELECT redis_run_id FROM coupon_dispatch_gate WHERE campaign_id=' + cid + ';') in redis('coupon-redis','INFO','server'), 'paused new-generation reconstruction')
        rebuilt = inspect(cid)
        check('async-paused-intent-and-issued-facts-survive-new-run', rebuilt['mode'] == 'ASYNC' and rebuilt['phase'] == 'PAUSED' and int(rebuilt['generation']) > int(paused['generation']) and rebuilt['issued'] == rebuilt['actual'] == '1')
        check('s3-and-local-identities-and-old-file-survive', storage_request(d, 'GET', '_storage-id') == (200,d.manifest['storage']['namespace']) and marker == d.compose('exec', '-T', 'app','cat','/var/lib/campuslife/files/.storage-id') and request('GET','/api/v1/files/'+file['id']+'/content',token=token,raw=True)[1] == sample)
        check('old-session-order-payment-and-notification-survive', api('GET','/orders/'+order['id'],token=token)['status'] == 'COMPLETED' and api('GET','/notifications',token=token)['total'] == 1 and sql('SELECT COUNT(*) FROM payment_record; SELECT COUNT(*) FROM order_coupon; SELECT COUNT(*) FROM message_consumption;') == '1\n1\n2')
        check('claim-idempotency-replays-after-restart', request('POST','/api/v1/coupon-claims',{'campaignId':cid},token,'show-claim')[0] == 200 and sql('SELECT COUNT(*) FROM user_coupon;') == '1')
        rated = api('GET','/shops/'+shop['shopId'])
        check('rating-cache-rebuilds-from-preserved-database', rated['reviewCount'] == 1 and json.loads(redis('rating-redis','GET',rating_key))['ratingSum'] == '5')
        check('private-readiness-and-jvm-metrics-survive-restart', observe(d,'Snapshot')['readiness']['httpStatus'] == 200 and observe(d,'Metrics','jvm.memory.used')['httpStatus'] == 200)
        new_file = upload(token, '重启后新上传.pdf')
        check('post-restart-new-s3-write-and-full-download', request('GET','/api/v1/files/'+new_file['id']+'/content',token=token,raw=True)[1] == sample)
        action(cid, 'Resume')
        second = student()
        status, accepted = request('POST','/api/v1/coupon-claims',{'campaignId':cid},second,'show-new-claim')
        check('new-async-request-after-controlled-resume-is-202', status == 202)
        second_claim = accepted['data']['id']
        wait(lambda: api('GET','/coupon-claims/'+second_claim,token=second)['status'] == 'SUCCEEDED', 'post-restart Redis/MQ/SQL new issuance')
        check('post-restart-new-issuance-is-not-duplicate-or-oversold', sql('SELECT issued_count FROM coupon_campaign WHERE id='+cid+'; SELECT COUNT(*) FROM user_coupon; SELECT COUNT(*) FROM payment_record; SELECT COUNT(*) FROM notification;') == '2\n2\n1\n1')
        result['finalSnapshot'] = observe(d,'Snapshot')
        if lifecycle_hook:
            lifecycle_hook('verified', d, result)
        logs = d.compose('logs','--no-color',timeout=30)
        check('combined-logs-have-no-test-password-tokens-or-ops-password', all(v not in logs for v in [password,*tokens,(folder/'secrets/MANAGEMENT_PASSWORD').read_text()]))
        result.update(passed=True, operations=operation_count, jarSha256=d.manifest['jarSha256'], imageId=d.manifest['imageId'], volumes=5, services=7, networks=3)
        print('PASS: '+str(len(result['checks']))+' seven-service lifecycle groups.',flush=True)
    except BaseException as error:
        result.update(passed=False,failure=str(error));raise
    finally:
        try:
            if lifecycle_hook:
                lifecycle_hook('end', d, result)
            d.compose('logs','--no-color',timeout=30,log=folder/'services.log')
        finally:
            try:
                d.destroy_test()
                result['cleanup']={'containers':0,'volumes':0,'networks':0,'generatedSecretsRemoved':True}
            finally:
                result['finishedAt']=dt.datetime.now(dt.timezone.utc).isoformat()
                evidence = folder / ('recovery-source.json' if recovery_hook else 'compact4g-acceptance.json' if resource_profile == 'compact4g' else 'showcase-acceptance.json')
                evidence.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
                print('Evidence: '+str(evidence),flush=True)


if __name__ == '__main__':
    main()
