"""B1: real private Redis/AMQP and existing HTTP workflows. No developer services/data."""
from concurrent.futures import ThreadPoolExecutor
import datetime as dt
import hashlib
import http.client as http_client
import json
import re
import secrets
import socket
import time
import urllib.parse

from deploy_base import ROOT, Deployment, initialize
from test_deploy_base import pdf_bytes


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def main():
    folder = initialize(free_port(), docs=True, test=True, mode='components', demo_money=True)
    d = Deployment(folder)
    result = {'task': 'M06.1-B1', 'instance': str(folder.relative_to(ROOT)), 'checks': [],
              'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'explicitDemoMoney': True}
    port = d.manifest['port']
    namespace = d.env['DATA_NAMESPACE']

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        if name in result['checks']:
            raise AssertionError('Duplicate check label')
        result['checks'].append(name)

    def wait(predicate, description, seconds=40):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                value = predicate()
                if value:
                    return value
            except (RuntimeError, ConnectionError, OSError):
                pass
            time.sleep(0.3)
        raise AssertionError('Timed out: ' + description)

    def http(method, path, body=None, token=None, key=None, raw=False):
        connection = http_client.HTTPConnection('127.0.0.1', port, timeout=30)
        headers = {}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if key:
            headers['Idempotency-Key'] = key
        if body is not None and not isinstance(body, bytes):
            headers['Content-Type'] = 'application/json'
            body = json.dumps(body, ensure_ascii=False).encode()
        connection.request(method, '/api/v1' + path, body, headers)
        response = connection.getresponse()
        data = response.read()
        status = response.status
        out = dict(response.getheaders())
        connection.close()
        return status, out, data if raw else json.loads(data)

    def api(method, path, body=None, token=None, key=None):
        status, _, value = http(method, path, body, token, key)
        if status not in [200, 201, 202]:
            raise AssertionError(method + ' ' + path + ': ' + str(status) + ' ' + str(value.get('code')))
        return value['data']

    def sql(statement, root=False):
        secret, user = ('MYSQL_ROOT_PASSWORD', 'root') if root else ('DB_PASSWORD', 'campuslife')
        return d.compose('exec', '-T', 'mysql', 'sh', '-c',
            'MYSQL_PWD="$(cat /run/secrets/' + secret + ')" exec mysql --protocol=TCP --host=127.0.0.1 '
            '--user=' + user + ' --database=campuslife_deploy --batch --skip-column-names --default-character-set=utf8mb4', stdin=statement)

    def redis(service, *commands, ops=False, anonymous=False):
        if anonymous:
            return d.compose('exec', '-T', service, 'redis-cli', '--raw', *commands)
        prefix = 'RATING' if service == 'rating-redis' else 'COUPON'
        secret = prefix + '_REDIS_OPS_PASSWORD' if ops else ('RATING_CACHE_PASSWORD' if prefix == 'RATING' else 'COUPON_REDIS_PASSWORD')
        user = 'redis_ops' if ops else ('rating_app' if prefix == 'RATING' else 'coupon_app')
        return d.compose('exec', '-T', service, 'sh', '-c',
            'REDISCLI_AUTH="$(cat /run/secrets/' + secret + ')"; export REDISCLI_AUTH; task_user="$1"; shift; exec redis-cli --user "$task_user" --raw "$@"',
            'redis-check', user, *commands)

    def broker(path):
        password = (folder / 'secrets/RABBIT_OPS_PASSWORD').read_text()
        config = 'silent\nshow-error\nfail\nuser = "campuslife_ops:' + password + '"\nurl = "http://rabbitmq:15672' + path + '"\n'
        return json.loads(d.compose('exec', '-T', 'app', 'curl', '--config', '-', stdin=config, timeout=15))

    def inspect_campaign(campaign):
        text = d.maintenance('coupon', 'Inspect', campaign)
        m = re.search(r'campaign=(\d+) mode=(\w+) phase=(\w+) generation=(\d+) controlVersion=(\d+) pending=(\d+) issued=(\d+) actualCoupons=(\d+)', text)
        if not m:
            raise AssertionError('Maintenance output missing durable gate')
        return dict(zip(['id', 'mode', 'phase', 'generation', 'version', 'pending', 'issued', 'actual'], m.groups()))

    def action(campaign, operation):
        state = inspect_campaign(campaign)
        d.maintenance('coupon', operation, campaign, state['version'], upgraded=True)

    password = 'Deploy_' + secrets.token_hex(12)

    def student():
        name = 'comp_s_' + secrets.token_hex(6)
        user = api('POST', '/auth/register', {'username': name, 'password': password, 'nickname': '组件验收同学😀'})
        return user, api('POST', '/auth/login', {'username': name, 'password': password})['accessToken']

    def aggregate_count():
        return int(sql("SELECT COALESCE(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='campuslife_deploy' AND DIGEST_TEXT LIKE '%rating_sum%' AND DIGEST_TEXT LIKE '%order_review%';", root=True))

    try:
        print('Starting explicit LOCAL components mode: six private services.', flush=True)
        d.start()
        check('six-services-only-gateway-published', len(d.resources('container')) == 6)
        check('original-schema-default-no-admin', sql("SELECT COUNT(*) FROM account; SELECT COUNT(*) FROM flyway_schema_history WHERE success=1; SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history';") == '0\n24\n32')
        check('application-has-only-runtime-not-ops-secrets', d.compose('exec', '-T', 'app', 'sh', '-c', 'test ! -e /run/secrets/RABBIT_OPS_PASSWORD && test ! -e /run/secrets/COUPON_REDIS_OPS_PASSWORD && test ! -e /run/secrets/RATING_REDIS_OPS_PASSWORD && test ! -e /run/secrets/RABBIT_ERLANG_COOKIE && test ! -e /run/secrets/MYSQL_ROOT_PASSWORD && echo isolated') == 'isolated')
        for service in ['rating-redis', 'coupon-redis']:
            check(service + '-anonymous-denied', 'NOAUTH' in redis(service, 'PING', anonymous=True))
            check(service + '-foreign-namespace-and-flush-denied', 'NOPERM' in redis(service, 'SET', 'outside:scope', 'x') and 'NOPERM' in redis(service, 'FLUSHALL'))
        check('redis-persistence-policies-differ', 'allkeys-lru' in redis('rating-redis', 'CONFIG', 'GET', 'maxmemory-policy', ops=True) and 'noeviction' in redis('coupon-redis', 'CONFIG', 'GET', 'maxmemory-policy', ops=True) and 'aof_enabled:1' in redis('coupon-redis', 'INFO', 'persistence', ops=True))
        result['versions'] = {'rating': redis('rating-redis', 'INFO', 'server', ops=True).split('redis_version:')[1].splitlines()[0].strip(), 'coupon': redis('coupon-redis', 'INFO', 'server', ops=True).split('redis_version:')[1].splitlines()[0].strip()}
        vhost = d.env['RABBIT_VHOST']
        check('rabbit-no-guest-and-dedicated-vhost', 'guest' not in d.compose('exec', '-T', 'rabbitmq', 'gosu', 'rabbitmq', 'rabbitmqctl', '-q', 'list_users') and [x['name'] for x in broker('/api/vhosts')] == [vhost])
        result['versions']['rabbit'] = broker('/api/overview')['rabbitmq_version']
        encoded = urllib.parse.quote(vhost, safe='')
        queues = broker('/api/queues/' + encoded)
        check('original-four-quorum-queues-declared', {q['name'] for q in queues} == {'campuslife.notification.v1', 'campuslife.notification.dead.v1', 'campuslife.coupon.issue.v1', 'campuslife.coupon.issue.dead.v1'} and all(q['type'] == 'quorum' for q in queues))
        user, token = student()
        admin_name = 'comp_a_' + secrets.token_hex(6)
        d.admin(admin_name, '组件验收管理员', password)
        admin = api('POST', '/auth/login', {'username': admin_name, 'password': password})['accessToken']
        category = next(c['id'] for c in api('GET', '/shop-categories') if c['code'] == 'PRINT')
        name = 'comp_m_' + secrets.token_hex(6)
        shop = api('POST', '/merchant-applications', {'username': name, 'password': password, 'nickname': '组件验收店主', 'categoryId': category, 'shopName': '虚构组件打印店', 'locationText': '虚构校区'})
        merchant = api('POST', '/auth/login', {'username': name, 'password': password})['accessToken']
        shop_id = shop['shopId']
        view = api('GET', '/admin/shops/' + shop_id, token=admin)
        approved = api('POST', '/admin/shops/' + shop_id + '/approve', {'expectedVersion': view['version']}, admin)
        hours = api('PUT', '/merchant/shop/business-hours', {'expectedVersion': approved['version'], 'intervals': [{'weekday': i, 'startTime': '00:00', 'endTime': '23:59'} for i in range(1, 8)]}, merchant)
        api('PATCH', '/merchant/shop', {'expectedVersion': hours['version'], 'businessEnabled': True}, merchant)
        service = api('POST', '/merchant/services', {'serviceType': 'PRINT', 'name': '组件打印', 'priceAmount': 10, 'durationMinutes': None, 'enabled': True}, merchant)
        api('PUT', '/merchant/services/' + service['id'] + '/print-prices', {'rules': [{'paperSize': 'A4', 'colorMode': 'BW', 'sideMode': 'SINGLE', 'pricePerImpression': 10, 'enabled': True, 'expectedVersion': None}]}, merchant)
        rating_key = 'campuslife:' + namespace + ':rating:s1:' + shop_id + ':0'
        wait(lambda: (api('GET', '/shops/' + shop_id), redis('rating-redis', 'EXISTS', rating_key, ops=True) == '1')[1], 'authenticated cache fill', 15)
        before = aggregate_count()
        original = api('GET', '/shops/' + shop_id)
        api('GET', '/shops/' + shop_id)
        check('rating-cache-hit-skips-aggregate-query', aggregate_count() == before and json.loads(redis('rating-redis', 'GET', rating_key, ops=True))['reviewCount'] == '0')
        d.compose('stop', 'rating-redis', timeout=30)
        before = aggregate_count()
        fallback = api('GET', '/shops/' + shop_id)
        check('rating-outage-falls-back-to-mysql', fallback == original and aggregate_count() > before)
        d.compose('start', 'rating-redis')
        wait(lambda: (api('GET', '/shops/' + shop_id), redis('rating-redis', 'EXISTS', rating_key, ops=True) == '1')[1], 'cache refill after restart', 20)
        now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)

        def campaign(quantity):
            body = {'name': '虚构限量满20减5', 'thresholdAmount': 20, 'reductionAmount': 5, 'totalQuantity': quantity,
                    'claimStartAt': (now - dt.timedelta(seconds=30)).isoformat(), 'claimEndAt': (now + dt.timedelta(hours=1)).isoformat(),
                    'useStartAt': (now - dt.timedelta(seconds=60)).isoformat(), 'useEndAt': (now + dt.timedelta(hours=2)).isoformat()}
            row = api('POST', '/merchant/coupon-campaigns', body, merchant)
            api('POST', '/merchant/coupon-campaigns/' + row['id'] + '/publish', {'expectedVersion': row['version']}, merchant)
            return row['id']

        cid = campaign(2)
        check('activities-still-default-sync', inspect_campaign(cid)['mode'] == 'SYNC')
        print('Using original Pause/Async/Resume CLI and Redis restart protocol.', flush=True)
        for op in ['Pause', 'Async', 'Resume']:
            action(cid, op)
        check('explicit-maintenance-opens-async', inspect_campaign(cid)['phase'] == 'OPEN' and inspect_campaign(cid)['mode'] == 'ASYNC')
        d.compose('stop', 'coupon-redis', timeout=30)
        status, _, failure = http('POST', '/coupon-claims', {'campaignId': cid}, token, 'dep-down')
        check('coupon-outage-does-not-fall-back-sync', status == 503 and sql('SELECT COUNT(*) FROM user_coupon;') == '0')
        d.compose('start', 'coupon-redis')
        wait(lambda: sql('SELECT phase FROM coupon_dispatch_gate WHERE campaign_id=' + cid + ';') == 'OPEN' and sql('SELECT redis_run_id FROM coupon_dispatch_gate WHERE campaign_id=' + cid + ';') in redis('coupon-redis', 'INFO', 'server', ops=True), 'coupon generation recovery', 45)
        check('coupon-restart-rebuilds-without-changing-mode', inspect_campaign(cid)['mode'] == 'ASYNC')
        print('Stopping broker to verify durable 202, then recovering delivery.', flush=True)
        d.compose('stop', 'rabbitmq', timeout=60)
        status, response_headers, accepted = http('POST', '/coupon-claims', {'campaignId': cid}, token, 'first-async')
        claim_id = accepted.get('data', {}).get('id')
        check('broker-offline-202-is-durable-pending', status == 202 and accepted['data']['status'] == 'PENDING' and sql('SELECT status FROM coupon_claim_request WHERE id=' + claim_id + ';') == 'PENDING' and sql('SELECT COUNT(*) FROM user_coupon;') == '0')
        replay = http('POST', '/coupon-claims', {'campaignId': cid}, token, 'first-async')
        check('pending-replay-keeps-id-and-deadline', replay[0] == 202 and replay[2]['data']['id'] == claim_id and replay[2]['data']['deadline'] == accepted['data']['deadline'])
        message_id = sql("SELECT message_id FROM message_outbox WHERE event_type='COUPON_ISSUE_REQUESTED' AND aggregate_id=" + claim_id + ';')
        check('plain-jdbc-message-inspect-gets-file-secret', 'messageId=' + message_id in d.maintenance('message', 'Inspect', message_id))
        d.compose('start', 'rabbitmq')
        wait(lambda: api('GET', '/coupon-claims/' + claim_id, token=token)['status'] == 'SUCCEEDED', 'durable issuance after broker recovery', 50)
        claimed = api('GET', '/coupon-claims/' + claim_id, token=token)
        check('final-replay-is-one-issued-coupon', http('POST', '/coupon-claims', {'campaignId': cid}, token, 'first-async')[0] == 200 and sql('SELECT issued_count FROM coupon_campaign WHERE id=' + cid + ';') == '1')
        students = [student()[1] for _ in range(5)]
        with ThreadPoolExecutor(max_workers=5) as pool:
            replies = list(pool.map(lambda pair: http('POST', '/coupon-claims', {'campaignId': cid}, pair[1], 'race-' + str(pair[0])), enumerate(students)))
        for response in replies:
            if response[0] == 202:
                rid = response[2]['data']['id']
                wait(lambda: api('GET', '/coupon-claims/' + rid, token=students[replies.index(response)])['status'] != 'PENDING', 'racing claim terminal')
        check('lua-and-mysql-stock-have-one-remaining-winner', sum(r[0] == 202 for r in replies) == 1 and all(r[0] in [202, 409] for r in replies) and sql('SELECT issued_count FROM coupon_campaign WHERE id=' + cid + ';') == '2' and sql('SELECT COUNT(*) FROM user_coupon WHERE campaign_id=' + cid + ';') == '2')
        # Upload is plain multipart test data; never a public object URL.
        pdf = pdf_bytes()
        boundary = 'component' + secrets.token_hex(8)
        multipart = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="sample.pdf"\r\nContent-Type: application/pdf\r\n\r\n').encode() + pdf + ('\r\n--' + boundary + '--\r\n').encode()
        conn = http_client.HTTPConnection('127.0.0.1', port, timeout=30)
        conn.request('POST', '/api/v1/files', multipart, {'Authorization': 'Bearer ' + token, 'Content-Type': 'multipart/form-data; boundary=' + boundary})
        upload = conn.getresponse(); file = json.loads(upload.read())['data']; conn.close()
        quote_body = {'serviceId': service['id'], 'fileId': file['id'], 'paperSize': 'A4', 'colorMode': 'BW', 'sideMode': 'SINGLE', 'copies': 2, 'userCouponId': claimed['coupon']['id']}
        quote = api('POST', '/print-quotes', quote_body, token)
        order = api('POST', '/orders/prints', dict(quote_body, expectedPriceVersion=quote['priceVersion'], expectedPayableAmount=quote['payableAmount']), token, 'coupon-print')
        check('real-print-order-uses-issued-coupon', quote['payableAmount'] == 15 and order['payableAmount'] == 15)
        api('POST', '/orders/' + order['id'] + '/payments/mock', token=token)
        for op in ['accept', 'start', 'ready-for-pickup']:
            api('POST', '/merchant/orders/' + order['id'] + '/' + op, token=merchant)
        notification = wait(lambda: next((n for n in api('GET', '/notifications', token=token)['items'] if n['orderId'] == order['id']), None), 'notification consumer')
        check('original-print-event-notifies-student-once', notification['type'] == 'PRINT_READY_FOR_PICKUP' and sql("SELECT COUNT(*) FROM notification WHERE order_id=" + order['id'] + ';') == '1')
        notification_message = sql("SELECT message_id FROM message_outbox WHERE event_type='PRINT_READY_FOR_PICKUP' AND aggregate_id=" + order['id'] + ';')
        check('consumed-message-replay-does-not-duplicate-notification', 'Already consumed' in d.maintenance('message', 'Replay', notification_message, reason='虚构验收重放核对') and sql('SELECT COUNT(*) FROM notification WHERE order_id=' + order['id'] + ';') == '1')
        api('POST', '/orders/' + order['id'] + '/pickup', token=token)
        api('POST', '/orders/' + order['id'] + '/review', {'rating': 5, 'content': '组件闭环验收😀'}, token)
        new_key = 'campuslife:' + namespace + ':rating:s1:' + shop_id + ':1'
        wait(lambda: (api('GET', '/shops/' + shop_id), redis('rating-redis', 'EXISTS', new_key, ops=True) == '1')[1], 'versioned cache refill')
        rated = api('GET', '/shops/' + shop_id)
        check('review-write-advances-versioned-rating-cache', rated['rating'] == 5 and rated['reviewCount'] == 1 and json.loads(redis('rating-redis', 'GET', new_key, ops=True))['ratingSum'] == '5')
        check('funds-and-coupon-remain-unique', sql('SELECT COUNT(*) FROM payment_record WHERE order_id=' + order['id'] + '; SELECT COUNT(*) FROM order_coupon WHERE order_id=' + order['id'] + ';') == '1\n1')
        action(cid, 'Pause')
        paused = inspect_campaign(cid)
        marker = d.compose('exec', '-T', 'app', 'cat', '/var/lib/campuslife/files/.storage-id')
        print('Restarting all six services with a PAUSED async campaign.', flush=True)
        stopped = d.stop()
        check('ordinary-stop-keeps-four-persistent-volumes', stopped['volumes'] == 4 and all(x['state'] == 'exited' for x in stopped['services']))
        d.start()
        wait(lambda: sql('SELECT phase FROM coupon_dispatch_gate WHERE campaign_id=' + cid + ';') == 'PAUSED' and sql('SELECT redis_run_id FROM coupon_dispatch_gate WHERE campaign_id=' + cid + ';') in redis('coupon-redis', 'INFO', 'server', ops=True), 'paused restart recovery', 45)
        recovered = inspect_campaign(cid)
        check('paused-intent-and-issued-facts-survive-new-redis-run', recovered['mode'] == 'ASYNC' and recovered['phase'] == 'PAUSED' and int(recovered['generation']) > int(paused['generation']) and recovered['issued'] == recovered['actual'] == '2')
        check('broker-local-files-and-notification-survive-restart', api('GET', '/notifications', token=token)['total'] == 1 and http('GET', '/files/' + file['id'] + '/content', token=token, raw=True)[2] == pdf and marker == d.compose('exec', '-T', 'app', 'cat', '/var/lib/campuslife/files/.storage-id'))
        try:
            d.maintenance('coupon', 'Resume', cid, '0')
            raise AssertionError('Old control version accepted')
        except RuntimeError:
            check('stale-operator-cannot-reopen-paused-gate', inspect_campaign(cid)['phase'] == 'PAUSED')
        action(cid, 'Sync'); action(cid, 'Resume')
        check('controlled-sync-return-keeps-original-coupon-facts', inspect_campaign(cid)['mode'] == 'SYNC' and inspect_campaign(cid)['phase'] == 'OPEN' and sql('SELECT COUNT(*) FROM user_coupon WHERE campaign_id=' + cid + ';') == '2')
        sync_cid = campaign(1)
        _, sync_token = student()
        check('new-default-sync-claim-still-returns-201', http('POST', '/coupon-claims', {'campaignId': sync_cid}, sync_token, 'sync-default')[0] == 201)
        # A mode edit against existing labelled data must not silently disable required backends.
        manifest_path = folder / 'instance.json'
        saved_manifest = d.manifest.copy()
        try:
            manifest_path.write_text(json.dumps(dict(saved_manifest, mode='base', demoMoney=False)), encoding='utf-8')
            try:
                Deployment(folder).validate()
                raise AssertionError('Unsafe in-place mode change allowed')
            except RuntimeError:
                check('existing-component-volumes-cannot-silently-become-base', True)
        finally:
            manifest_path.write_text(json.dumps(saved_manifest), encoding='utf-8')
        logs = d.compose('logs', '--no-color', timeout=30)
        check('logs-do-not-contain-test-password-or-token', password not in logs and token not in logs)
        result.update(passed=True, jarSha256=d.manifest['jarSha256'], persistentVolumes=4)
        print('PASS: ' + str(len(result['checks'])) + ' component deployment checks.', flush=True)
    except BaseException as error:
        result.update(passed=False, failure=str(error))
        raise
    finally:
        try:
            d.compose('logs', '--no-color', timeout=30, log=folder / 'services.log')
        finally:
            try:
                d.destroy_test()
                result['cleanup'] = {'containers': 0, 'volumes': 0, 'networks': 0, 'generatedSecretsRemoved': True}
            finally:
                result['finishedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
                (folder / 'components-acceptance.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
                print('Evidence: ' + str(folder / 'components-acceptance.json'), flush=True)


def base_smoke():
    """Narrow regression for the unchanged default mode after the shared runner/entrypoint edit."""
    folder = initialize(free_port(), test=True)
    d = Deployment(folder)
    result = {'task': 'M06.1-B1 base regression', 'checks': []}

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        result['checks'].append(name)

    try:
        config = d.validate()
        env = config['services']['app']['environment']
        check('base-stays-three-services', len(config['services']) == 3)
        check('base-keeps-all-optional-components-and-money-off', all(env[k] == 'false' for k in ['MESSAGING_ENABLED', 'RATING_CACHE_ENABLED', 'COUPON_REDIS_ENABLED', 'MOCK_PAYMENT_ENABLED', 'FILE_S3_ENABLED']))
        check('base-generates-only-original-three-secrets', len(list((folder / 'secrets').iterdir())) == 3)
        try:
            initialize(free_port(), test=True, mode='base', demo_money=True)
            raise AssertionError('Base accepted demo funds')
        except ValueError:
            check('base-rejects-demo-money-combination', True)
        d.start()
        conn = http_client.HTTPConnection('127.0.0.1', d.manifest['port'], timeout=10)
        conn.request('GET', '/api/v1/system/health')
        response = conn.getresponse()
        check('base-new-jar-starts-without-redis-or-broker', response.status == 200 and json.loads(response.read())['data']['status'] == 'UP')
        conn.close()
        # Older A manifests lacked these fields; their base interpretation stays explicit.
        old = d.manifest.copy()
        old.pop('mode'); old.pop('demoMoney')
        (folder / 'instance.json').write_text(json.dumps(old), encoding='utf-8')
        check('old-base-manifest-fields-remain-compatible', len(Deployment(folder).validate()['services']) == 3)
        stopped = d.stop()
        check('base-stop-still-retains-two-volumes', stopped['volumes'] == 2 and all(s['state'] == 'exited' for s in stopped['services']))
        result['passed'] = True
        print('PASS: ' + str(len(result['checks'])) + ' base regression checks.', flush=True)
    except BaseException as error:
        result.update(passed=False, failure=str(error))
        raise
    finally:
        try:
            d.compose('logs', '--no-color', log=folder / 'services.log')
        finally:
            try:
                d.destroy_test()
                result['cleanup'] = {'containers': 0, 'volumes': 0, 'networks': 0, 'generatedSecretsRemoved': True}
            finally:
                (folder / 'base-regression.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
                print('Evidence: ' + str(folder / 'base-regression.json'), flush=True)


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--phase', choices=['All', 'Components', 'Base'], default='All')
    phase = parser.parse_args().phase
    if phase in ['All', 'Components']:
        main()
    if phase in ['All', 'Base']:
        base_smoke()
