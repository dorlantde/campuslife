"""C: private management security, real database/broker outages, fixed on-demand snapshots."""
import datetime as dt
import http.client as http_client
import json
import secrets
import time

from deploy_base import ROOT, Deployment, initialize
from deploy_observability import management_request, observe
from test_deploy_base import pdf_bytes
from test_deploy_components import free_port, base_smoke


def main():
    folder = initialize(free_port(), docs=True, test=True, mode='components', demo_money=True, observe=True)
    d = Deployment(folder)
    result = {'task': 'M06.1-C', 'checks': [], 'startedAt': dt.datetime.now(dt.timezone.utc).isoformat()}

    def check(label, condition):
        if not condition:
            raise AssertionError(label)
        assert label not in result['checks']
        result['checks'].append(label)

    def wait(probe, seconds=50):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                value = probe()
                if value:
                    return value
            except (RuntimeError, OSError):
                pass
            time.sleep(.3)
        raise AssertionError('Bounded recovery observation timed out')

    def http(method, path, body=None, token=None, headers=None):
        conn = http_client.HTTPConnection('127.0.0.1', d.manifest['port'], timeout=20)
        supplied = dict(headers or {})
        if token:
            supplied['Authorization'] = 'Bearer ' + token
        if body is not None and not isinstance(body, bytes):
            supplied['Content-Type'] = 'application/json'
            body = json.dumps(body).encode()
        conn.request(method, path, body, supplied)
        response = conn.getresponse()
        data = response.read()
        out = dict(response.getheaders())
        status = response.status
        conn.close()
        return status, out, json.loads(data) if data else None

    def api(method, path, body=None, token=None, headers=None):
        status, _, value = http(method, '/api/v1' + path, body, token, headers)
        if status not in [200, 201]:
            raise AssertionError(method + ' ' + path + ': ' + str(status))
        return value['data']

    def sql(text):
        return d.compose('exec', '-T', 'mysql', 'sh', '-c',
            'MYSQL_PWD="$(cat /run/secrets/DB_PASSWORD)" exec mysql --protocol=TCP --host=127.0.0.1 '
            '--user=campuslife --database=campuslife_deploy --batch --skip-column-names', stdin=text)

    password = 'Obs_' + secrets.token_hex(14)
    student_token = admin_token = merchant_token = ''
    try:
        print('Starting isolated six-service management fixture.', flush=True)
        d.start()
        config = d.validate()
        check('management-port-unpublished-and-separate', not config['services']['app'].get('ports') and config['services']['app']['environment']['MANAGEMENT_PORT'] == '8081')
        probe = d.compose('exec', '-T', 'nginx', 'sh', '-c', 'if wget -q -T 2 -O /dev/null http://app:8081/actuator/health; then echo reachable; else echo private; fi')
        check('management-loopback-refuses-peer-container', probe == 'private')
        check('nginx-does-not-proxy-actuator', http('GET', '/actuator/health')[0] == 404)
        check('anonymous-management-request-401', management_request(d, '/actuator/metrics', authorization='')[0] == 401)
        check('bearer-management-request-401', management_request(d, '/actuator/metrics', authorization='Bearer header.payload.signature')[0] == 401)
        health = observe(d, 'Health')
        result['healthSummary'] = health
        check('health-summary-does-not-export-details', health['httpStatus'] == 200 and health['data']['status'] == 'UP'
              and set(health['data']) <= {'status', 'groups'})
        check('only-get-is-authorized', management_request(d, '/actuator/metrics', method='POST')[0] == 403)
        snapshot = observe(d, 'Snapshot')
        check('fixed-read-only-snapshot-is-available', snapshot['database']['available'] and snapshot['readiness']['httpStatus'] == 200 and isinstance(snapshot['queues'], list))
        result['initialSnapshot'] = snapshot

        names = observe(d, 'Metrics')['data']['names']
        check('jvm-http-and-connection-pool-meters-present', 'jvm.memory.used' in names and 'http.server.requests' in names and any(n.startswith('hikaricp.connections') for n in names))
        result['meterNames'] = names
        check('jvm-meter-is-readable', observe(d, 'Metrics', 'jvm.memory.used')['httpStatus'] == 200)
        openapi = http('GET', '/v3/api-docs')[2]
        operations = sum(1 for methods in openapi['paths'].values() for k in methods if k in ['get','post','put','patch','delete','head','options'])
        check('business-openapi-remains-75', operations == 75 and not any('/actuator' in p for p in openapi['paths']))

        print('Checking MySQL outage without business-session authentication on management.', flush=True)
        d.compose('stop', 'mysql', timeout=45)
        check('readiness-detects-real-database-outage', management_request(d, '/actuator/health/readiness')[0] == 503)
        check('liveness-stays-up-without-database', management_request(d, '/actuator/health/liveness') == (200, {'status': 'UP'}))
        check('jvm-metrics-still-readable-without-database', observe(d, 'Metrics', 'jvm.memory.used')['httpStatus'] == 200)
        check('business-health-still-only-http-liveness', http('GET', '/api/v1/system/health')[0] == 200)
        down = observe(d, 'Snapshot')
        check('snapshot-reports-database-unavailable-without-error-details', down['database'] == {'available': False})
        result['databaseOutageSnapshot'] = down
        d.compose('start', 'mysql')
        wait(lambda: management_request(d, '/actuator/health/readiness')[0] == 200)
        check('readiness-recovers-without-app-restart', True)

        print('Preparing an original print notification for a real broker outage.', flush=True)
        suffix = secrets.token_hex(5)
        api('POST', '/auth/register', {'username': 'obs_s_' + suffix, 'password': password, 'nickname': '虚构观测同学'})
        student_token = api('POST', '/auth/login', {'username': 'obs_s_' + suffix, 'password': password})['accessToken']
        d.admin('obs_a_' + suffix, '虚构观测管理员', password)
        admin_token = api('POST', '/auth/login', {'username': 'obs_a_' + suffix, 'password': password})['accessToken']
        check('actual-admin-jwt-cannot-read-management', management_request(d, '/actuator/metrics', authorization='Bearer ' + admin_token)[0] == 401)
        direct = d.compose('exec', '-T', 'app', 'curl', '--config', '-', stdin='silent\nshow-error\nmax-time = 10\nurl = "http://127.0.0.1:8080/actuator/metrics"\nheader = "Authorization: Bearer ' + admin_token + '"\nwrite-out = "\\n%{http_code}"\n')
        check('business-port-rejects-actuator-even-for-admin', direct.endswith('403'))
        category = next(c['id'] for c in api('GET', '/shop-categories') if c['code'] == 'PRINT')
        shop = api('POST', '/merchant-applications', {'username': 'obs_m_' + suffix, 'password': password, 'nickname': '虚构店主', 'categoryId': category, 'shopName': '虚构观测打印店', 'locationText': '虚构校区'})
        merchant_token = api('POST', '/auth/login', {'username': 'obs_m_' + suffix, 'password': password})['accessToken']
        detail = api('GET', '/admin/shops/' + shop['shopId'], token=admin_token)
        approved = api('POST', '/admin/shops/' + shop['shopId'] + '/approve', {'expectedVersion': detail['version']}, admin_token)
        hours = api('PUT', '/merchant/shop/business-hours', {'expectedVersion': approved['version'], 'intervals': [{'weekday': day, 'startTime': '00:00', 'endTime': '23:59'} for day in range(1,8)]}, merchant_token)
        api('PATCH', '/merchant/shop', {'expectedVersion': hours['version'], 'businessEnabled': True}, merchant_token)
        service = api('POST', '/merchant/services', {'serviceType': 'PRINT', 'name': '观测打印', 'priceAmount': 10, 'durationMinutes': None, 'enabled': True}, merchant_token)
        api('PUT', '/merchant/services/' + service['id'] + '/print-prices', {'rules': [{'paperSize': 'A4', 'colorMode': 'BW', 'sideMode': 'SINGLE', 'pricePerImpression': 10, 'enabled': True, 'expectedVersion': None}]}, merchant_token)
        boundary = 'obs' + suffix
        multipart = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="sample.pdf"\r\nContent-Type: application/pdf\r\n\r\n').encode() + pdf_bytes() + ('\r\n--' + boundary + '--\r\n').encode()
        file = api('POST', '/files', multipart, student_token, {'Content-Type': 'multipart/form-data; boundary=' + boundary})
        body = {'fileId': file['id'], 'serviceId': service['id'], 'paperSize': 'A4', 'colorMode': 'BW', 'sideMode': 'SINGLE', 'copies': 2}
        quote = api('POST', '/print-quotes', body, student_token)
        order = api('POST', '/orders/prints', dict(body, expectedPriceVersion=quote['priceVersion'], expectedPayableAmount=quote['payableAmount']), student_token, {'Idempotency-Key': 'obs-print'})
        api('POST', '/orders/' + order['id'] + '/payments/mock', token=student_token)
        api('POST', '/merchant/orders/' + order['id'] + '/accept', token=merchant_token)
        api('POST', '/merchant/orders/' + order['id'] + '/start', token=merchant_token)
        d.compose('stop', 'rabbitmq', timeout=50)
        api('POST', '/merchant/orders/' + order['id'] + '/ready-for-pickup', token=merchant_token)
        pending = observe(d, 'Snapshot')
        check('broker-down-is-diagnosed-separately', pending['queues'] == {'available': False} and pending['readiness']['httpStatus'] == 200)
        outbox = [r for r in pending['database']['aggregates'] if r['section'] == 'outbox']
        check('snapshot-shows-durable-backlog-and-age', sum(r['count'] for r in outbox if r['state'] in ['READY','PUBLISHING']) == 1 and any(r['oldestAgeSeconds'] is not None for r in outbox))
        check('sent-is-not-consumption-and-no-early-notification', api('GET', '/notifications', token=student_token)['total'] == 0)
        result['brokerOutageSnapshot'] = pending
        d.compose('start', 'rabbitmq')
        wait(lambda: api('GET', '/notifications', token=student_token)['total'] == 1)
        check('recovered-notification-has-one-consumption-fact', sql("SELECT COUNT(*) FROM notification; SELECT COUNT(*) FROM message_consumption WHERE consumer_name='notification-v1';") == '1\n1')
        check('outbox-is-sent-after-confirm', sql("SELECT status FROM message_outbox WHERE aggregate_id=" + order['id'] + ';') == 'SENT')
        result['recoveredSnapshot'] = observe(d, 'Snapshot')

        query_marker, supplied_trace = 'never-log-query-' + suffix, 'untrusted-' + suffix
        status, headers, response = http('GET', '/api/v1/shops/999999?probe=' + query_marker, headers={'X-Trace-Id': supplied_trace})
        trace = response['traceId']
        check('application-generates-trace-despite-client-input', trace != supplied_trace and headers.get('X-Trace-Id') == trace)
        logs = d.compose('logs', '--no-color', 'nginx', 'app', timeout=30)
        check('gateway-log-correlates-generated-application-trace', '"trace_id":"' + trace + '"' in logs)
        ops_secret = (folder / 'secrets/MANAGEMENT_PASSWORD').read_text()
        check('logs-exclude-query-password-and-bearer-values', all(v not in logs for v in [query_marker, supplied_trace, password, ops_secret, student_token, merchant_token, admin_token]))
        meter = observe(d, 'Metrics', 'http.server.requests')['data']
        text = json.dumps(meter)
        check('http-meter-tags-use-routes-without-query-or-identities', all(v not in text for v in [query_marker, ops_secret, student_token, '/shops/999999']) and '/api/v1/shops/{shopId}' in text)
        result['httpMeter'] = meter
        result.update(passed=True, operations=operations, jarSha256=d.manifest['jarSha256'])
        print('PASS: ' + str(len(result['checks'])) + ' private management checks.', flush=True)
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
                (folder / 'observability-acceptance.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
                print('Evidence: ' + str(folder / 'observability-acceptance.json'), flush=True)


def showcase_config_regression():
    folder = initialize(free_port(), test=True, mode='showcase', observe=True)
    d = Deployment(folder)
    result = {'task': 'M06.1-C showcase configuration'}
    try:
        config = d.validate()
        app = config['services']['app']
        assert len(config['services']) == 7 and app['environment']['FILE_S3_ENABLED'] == 'true'
        assert app['environment']['MANAGEMENT_PORT'] == '8081' and not app.get('ports')
        assert len(list((folder / 'secrets').iterdir())) == 24
        result.update(passed=True, checks=['seven-service-tls-overlay-composes-with-private-management'])
    finally:
        d.destroy_test()
        result['cleanup'] = {'containers': 0, 'volumes': 0, 'networks': 0, 'generatedSecretsRemoved': True}
        (folder / 'observability-config.json').write_text(json.dumps(result), encoding='utf-8')
        print('Evidence: ' + str(folder / 'observability-config.json'), flush=True)


if __name__ == '__main__':
    main()
    base_smoke()
    showcase_config_regression()
