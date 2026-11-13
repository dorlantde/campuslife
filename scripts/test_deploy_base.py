"""Real three-container base acceptance; generated accounts/data only, no credentials printed."""
import datetime as dt
import hashlib
import http.client
import json
import secrets
import socket
import time
import urllib.parse
from pathlib import Path
import zipfile

from deploy_base import ROOT, BUILD_RECORD, Deployment, docker, initialize


def pdf_bytes(size=None):
    data = b'%PDF-1.4\n'
    offsets = []
    for i, body in enumerate([b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
                              b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>'], 1):
        offsets.append(len(data))
        data += str(i).encode() + b' 0 obj\n' + body + b'\nendobj\n'
    xref = len(data)
    data += b'xref\n0 4\n0000000000 65535 f \n' + b''.join(f'{n:010} 00000 n \n'.encode() for n in offsets)
    suffix = b'trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n' + str(xref).encode() + b'\n%%EOF\n'
    if size:
        data += b' ' * (size - len(data) - len(suffix))
    return data + suffix


def main():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    folder = initialize(port, test=True)
    deployment = Deployment(folder)
    checks = []
    result = {'task': 'M06.1-A', 'instance': str(folder.relative_to(ROOT)), 'checks': checks,
              'startedAt': dt.datetime.now(dt.timezone.utc).isoformat()}

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        if name in checks:
            raise AssertionError('Duplicate acceptance check label: ' + name)
        checks.append(name)

    def request(method, path, body=None, token=None, headers=None, raw=False):
        connection = http.client.HTTPConnection('127.0.0.1', port, timeout=90)
        supplied = dict(headers or {})
        if token:
            supplied['Authorization'] = 'Bearer ' + token
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body, ensure_ascii=False).encode('utf-8')
            supplied['Content-Type'] = 'application/json'
        connection.request(method, path, body=body, headers=supplied)
        response = connection.getresponse()
        content = response.read()
        out_headers = {k.lower(): v for k, v in response.getheaders()}
        status = response.status
        connection.close()
        if raw:
            return status, out_headers, content
        return status, out_headers, json.loads(content)

    def api(method, path, body=None, token=None, headers=None, statuses=(200, 201)):
        status, out_headers, data = request(method, '/api/v1' + path, body, token, headers)
        if status not in statuses:
            raise AssertionError('Business HTTP ' + method + ' ' + path + ': ' + str(status) + ' ' + str(data.get('code')))
        if out_headers.get('x-trace-id') != data.get('traceId'):
            raise AssertionError('Application trace correlation mismatch')
        return data.get('data')

    def upload(data, token, filename='校园验收😀.pdf'):
        boundary = 'campuslife-' + secrets.token_hex(12)
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + filename + '"\r\nContent-Type: application/pdf\r\n\r\n').encode() + data + ('\r\n--' + boundary + '--\r\n').encode()
        return request('POST', '/api/v1/files', body, token, {'Content-Type': 'multipart/form-data; boundary=' + boundary})

    def sql(statement):
        # No root credential is passed through argv or the host environment.
        return deployment.compose('exec', '-T', 'mysql', 'sh', '-c',
            'MYSQL_PWD="$(cat /run/secrets/DB_PASSWORD)" exec mysql --protocol=TCP --host=127.0.0.1 '
            '--user=campuslife --database=campuslife_deploy --batch --skip-column-names --default-character-set=utf8mb4', stdin=statement)

    def content(file_id, token):
        return request('GET', '/api/v1/files/' + file_id + '/content', token=token, raw=True)

    try:
        print('Starting owned base Compose environment (MySQL, app, Nginx).', flush=True)
        deployment.start()
        check('exactly-three-services-private-db-and-app', len(deployment.resources('container')) == 3)
        health_status, health_headers, health = request('GET', '/api/v1/system/health', headers={'X-Trace-Id': 'forged-client-trace'})
        check('gateway-health-utf8-and-independent-trace', health_status == 200 and health['data']['status'] == 'UP' and health['message'] == '成功' and health_headers['x-trace-id'] == health['traceId'] and health['traceId'] != 'forged-client-trace' and 'x-edge-request-id' in health_headers)
        check('no-default-admin-and-original-schema', sql("SELECT COUNT(*) FROM account WHERE role='ADMIN'; SELECT COUNT(*) FROM flyway_schema_history WHERE success=1; SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history';") == '0\n24\n32')
        status, _, _ = request('GET', '/v3/api-docs', raw=True)
        check('api-docs-disabled-by-default', status != 200)
        status, _, failure = request('GET', '/api/v1/orders')
        check('business-authentication-enforced', status == 401 and failure['code'] == 'AUTH_REQUIRED')
        check('non-root-linux-storage-writable', deployment.compose('exec', '-T', 'app', 'sh', '-c', 'id -u; test -w /var/lib/campuslife/files && echo writable; test ! -w /opt/campuslife/app.jar && echo immutable') == '10001\nwritable\nimmutable')
        check('runtime-no-dev-secrets-or-test-directories', deployment.compose('exec', '-T', 'app', 'sh', '-c', 'test ! -e /opt/campuslife/.local && test ! -e /opt/campuslife/.git && test ! -e /opt/campuslife/src && test ! -e /opt/campuslife/target && test ! -e /run/secrets/MYSQL_ROOT_PASSWORD && echo isolated') == 'isolated')
        image = json.loads(BUILD_RECORD.read_text())
        image_config = json.loads(docker('image', 'inspect', image['image']))[0]['Config']
        check('image-non-root-no-secret-environment', image_config['User'] == '10001:10001' and not any(s.startswith(('DB_PASSWORD=', 'JWT_SECRET_BASE64=', 'MYSQL_ROOT_PASSWORD=')) for s in image_config['Env']))
        result['runtimeVersions'] = {'java': deployment.compose('exec', '-T', 'app', 'java', '--version').splitlines()[0], 'mysql': sql('SELECT VERSION();'), 'nginx': deployment.compose('exec', '-T', 'nginx', 'sh', '-c', 'nginx -v 2>&1')}
        password = 'Test_' + secrets.token_hex(12)
        student_name = 'base_s_' + secrets.token_hex(6)
        student = api('POST', '/auth/register', {'username': student_name, 'password': password, 'nickname': '容器验收同学😀'})
        token = api('POST', '/auth/login', {'username': student_name, 'password': password})['accessToken']
        check('register-login-configtree-jwt', student['role'] == 'STUDENT' and bool(api('GET', '/me', token=token)))
        small = pdf_bytes()
        status, _, uploaded = upload(small, token)
        file_id = uploaded.get('data', {}).get('id')
        check('private-pdf-upload-unicode-name', status == 201 and uploaded['data']['pageCount'] == 1 and uploaded['data']['originalName'] == '校园验收😀.pdf')
        status, headers, downloaded = content(file_id, token)
        check('pdf-proxy-full-bytes-and-safe-disposition', status == 200 and downloaded == small and 'application/pdf' in headers['content-type'] and 'no-store' in headers['cache-control'] and 'filename*=' in headers['content-disposition'] and urllib.parse.quote('校园验收😀.pdf') in headers['content-disposition'])
        status, _, _ = request('GET', '/api/v1/files/' + file_id + '/content', raw=True)
        check('anonymous-cannot-download-private-content', status == 401)
        print('Checking actual 20 MiB PDF and both upload limit layers.', flush=True)
        large = pdf_bytes(20 * 1024 * 1024)
        status, _, uploaded_large = upload(large, token)
        check('exact-20mib-pdf-through-gateway', status == 201 and uploaded_large['data']['byteSize'] == len(large))
        status, _, bytes_large = content(uploaded_large['data']['id'], token)
        check('20mib-proxy-response-byte-equality', status == 200 and hashlib.sha256(bytes_large).digest() == hashlib.sha256(large).digest())
        status, _, rejected = upload(pdf_bytes(20 * 1024 * 1024 + 1), token)
        check('application-single-file-limit-413', status == 413 and rejected['code'] != 'EDGE_PAYLOAD_TOO_LARGE')
        status, headers, rejected = request('POST', '/api/v1/files', b'x' * (22 * 1024 * 1024 + 1), token, {'Content-Type': 'application/octet-stream'})
        check('gateway-total-body-limit-413-with-edge-identity', status == 413 and rejected['code'] == 'EDGE_PAYLOAD_TOO_LARGE' and rejected['requestId'] == headers.get('x-edge-request-id') and 'x-trace-id' not in headers)
        admin_name = 'base_a_' + secrets.token_hex(6)
        deployment.admin(admin_name, '容器验收管理员', password)
        admin_token = api('POST', '/auth/login', {'username': admin_name, 'password': password})['accessToken']
        check('explicit-non-http-admin-bootstrap', sql("SELECT COUNT(*) FROM account WHERE role='ADMIN';") == '1' and bool(api('GET', '/admin/accounts', token=admin_token)))
        categories = api('GET', '/shop-categories')
        category = next(c['id'] for c in categories if c['code'] == 'PRINT')
        merchant_name = 'base_m_' + secrets.token_hex(6)
        shop = api('POST', '/merchant-applications', {'username': merchant_name, 'password': password, 'nickname': '容器验收店主', 'categoryId': category, 'shopName': '虚构容器打印店', 'locationText': '虚构校区'})
        merchant_token = api('POST', '/auth/login', {'username': merchant_name, 'password': password})['accessToken']
        shop_id = shop['shopId']
        view = api('GET', '/admin/shops/' + shop_id, token=admin_token)
        approved = api('POST', '/admin/shops/' + shop_id + '/approve', {'expectedVersion': view['version']}, admin_token)
        hours = api('PUT', '/merchant/shop/business-hours', {'expectedVersion': approved['version'], 'intervals': [{'weekday': i, 'startTime': '00:00', 'endTime': '23:59'} for i in range(1, 8)]}, merchant_token)
        api('PATCH', '/merchant/shop', {'expectedVersion': hours['version'], 'businessEnabled': True}, merchant_token)
        service = api('POST', '/merchant/services', {'serviceType': 'PRINT', 'name': '容器A4打印', 'priceAmount': 10, 'durationMinutes': None, 'enabled': True}, merchant_token)
        api('PUT', '/merchant/services/' + service['id'] + '/print-prices', {'rules': [{'paperSize': 'A4', 'colorMode': 'BW', 'sideMode': 'SINGLE', 'pricePerImpression': 10, 'enabled': True, 'expectedVersion': None}]}, merchant_token)
        quote_body = {'serviceId': service['id'], 'fileId': file_id, 'paperSize': 'A4', 'colorMode': 'BW', 'sideMode': 'SINGLE', 'copies': 2}
        quote = api('POST', '/print-quotes', quote_body, token)
        check('merchant-review-operation-and-print-quote', quote['payableAmount'] == 20 and quote['pageCount'] == 1)
        order_body = dict(quote_body, expectedPriceVersion=quote['priceVersion'], expectedPayableAmount=quote['payableAmount'])
        order = api('POST', '/orders/prints', order_body, token, {'Idempotency-Key': 'base-print-create'})
        replay = api('POST', '/orders/prints', order_body, token, {'Idempotency-Key': 'base-print-create'})
        check('print-creation-idempotency-and-file-protection', order['id'] == replay['id'] and api('GET', '/files/' + file_id, token=token)['expiresAt'] is None)
        status, _, rejected = request('POST', '/api/v1/orders/' + order['id'] + '/payments/mock', token=token)
        check('mock-payment-stays-disabled', status == 503)
        closed = api('POST', '/orders/' + order['id'] + '/cancel', token=token)
        check('unpaid-print-cancel-and-retention', closed['status'] == 'CLOSED' and api('GET', '/files/' + file_id, token=token)['expiresAt'] is not None)
        # Explicit local docs opt-in uses the same base scope, not a frontend or mock-money overlay.
        manifest_path = folder / 'instance.json'
        manifest = deployment.manifest.copy()
        manifest['apiDocs'] = True
        manifest_path.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
        deployment = Deployment(folder)
        deployment.start()
        status, _, specification = request('GET', '/v3/api-docs')
        operations = sum(1 for item in specification.get('paths', {}).values() for key in item if key in ['get', 'post', 'patch', 'put', 'delete'])
        check('explicit-local-openapi-has-75-operations', status == 200 and operations == 75)
        status, _, swagger = request('GET', '/swagger-ui/index.html', raw=True)
        check('explicit-swagger-ui-through-gateway', status == 200 and b'swagger' in swagger.lower())
        print('Checking gateway outage behavior and full stop/start volume preservation.', flush=True)
        marker = deployment.compose('exec', '-T', 'app', 'cat', '/var/lib/campuslife/files/.storage-id')
        deployment.compose('stop', 'app', timeout=60)
        # A stopped Docker endpoint may drop packets (504), rather than reject TCP (502).
        # Replace only the stopped test app with a short-lived, no-server network probe.
        # No Java process, secret mount or data volume is present in this diagnostic container.
        deployment.compose('rm', '-f', 'app')
        probe_name = deployment.manifest['project'] + '-closed-port-probe'
        edge_network = next(n['Name'] for n in deployment.resources('network') if n['Labels'].get('com.docker.compose.network') == 'edge')
        docker('run', '-d', '--rm', '--name', probe_name, '--network', edge_network, '--network-alias', 'app',
               '--label', 'campuslife.base.identity=' + deployment.manifest['identity'], '--label', 'campuslife.base.kind=test',
               '--label', 'com.docker.compose.project=' + deployment.manifest['project'], '--label', 'com.docker.compose.service=closed-port-probe',
               '--entrypoint', 'sh', deployment.manifest['image'], '-c', 'sleep 90')
        try:
            deployment.compose('restart', 'nginx', timeout=30)
            status, headers, failure = request('GET', '/api/v1/system/health')
            result['edge502Observation'] = {'status': status, 'code': failure.get('code'), 'hasApplicationTrace': 'x-trace-id' in headers}
            check('edge-502-does-not-forge-application-trace', status == 502 and failure['code'] == 'EDGE_BAD_GATEWAY' and failure['requestId'] == headers.get('x-edge-request-id') and 'x-trace-id' not in headers)
        finally:
            if any(c['Name'].lstrip('/') == probe_name for c in deployment.resources('container')):
                docker('rm', '-f', probe_name)
        deployment.start()
        stopped = deployment.stop()
        check('stop-keeps-two-owned-volumes', stopped['volumes'] == 2 and all(x['state'] == 'exited' for x in stopped['services']))
        deployment.start()
        check('storage-id-survives-whole-stack-restart', marker == deployment.compose('exec', '-T', 'app', 'cat', '/var/lib/campuslife/files/.storage-id'))
        status, _, downloaded = content(file_id, token)
        check('jwt-session-database-and-pdf-survive-restart', status == 200 and downloaded == small and api('GET', '/orders/' + order['id'], token=token)['status'] == 'CLOSED')
        check('fresh-write-after-restart', upload(small, token, '重启新写.pdf')[0] == 201)
        logs = deployment.compose('logs', '--no-color', 'nginx')
        check('access-logs-correlate-server-trace-without-auth-or-password', health['traceId'] in logs and token not in logs and password not in logs and 'Authorization' not in logs)
        # No request/response buffering for PDF, and writes have no automatic proxy retry.
        nginx_effective = deployment.compose('exec', '-T', 'nginx', 'cat', '/etc/nginx/nginx.conf')
        check('proxy-streaming-and-no-write-retry-config', all(x in nginx_effective for x in ['proxy_request_buffering off', 'proxy_buffering off', 'proxy_next_upstream off']))
        print('Checking a real upstream timeout (60 seconds) and interrupted PDF streaming.', flush=True)
        app_name = next(c['Name'].lstrip('/') for c in deployment.resources('container') if c['Config']['Labels']['com.docker.compose.service'] == 'app')
        docker('pause', app_name)
        try:
            status, headers, failure = request('GET', '/api/v1/system/health')
            check('edge-504-has-own-request-id-without-application-trace', status == 504 and failure['code'] == 'EDGE_GATEWAY_TIMEOUT' and failure['requestId'] == headers.get('x-edge-request-id') and 'x-trace-id' not in headers)
        finally:
            docker('unpause', app_name)
        deployment.start()
        stream = http.client.HTTPConnection('127.0.0.1', port, timeout=15)
        stream.connect()
        stream.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
        stream.request('GET', '/api/v1/files/' + uploaded_large['data']['id'] + '/content', headers={'Authorization': 'Bearer ' + token})
        response = stream.getresponse()
        prefix = response.read(1024)
        docker('kill', '--signal', 'KILL', app_name)
        received = prefix
        try:
            received += response.read()
        except http.client.IncompleteRead as error:
            received += error.partial
        finally:
            stream.close()
        check('interrupted-pdf-is-original-prefix-without-json-tail', response.status == 200 and 0 < len(received) < len(large) and large.startswith(received))
        deployment.start()
        check('recovery-after-upstream-process-kill', content(file_id, token)[2] == small)
        result.update(passed=True, businessOperations=operations, jarSha256=deployment.manifest['jarSha256'])
        print('PASS: ' + str(len(checks)) + ' base deployment acceptance checks.', flush=True)
    except BaseException as error:
        result.update(passed=False, failure=str(error))
        raise
    finally:
        try:
            deployment.compose('logs', '--no-color', timeout=30, log=folder / 'services.log')
        finally:
            try:
                deployment.destroy_test()
                result['cleanup'] = {'containers': 0, 'volumes': 0, 'networks': 0, 'generatedSecretsRemoved': True}
            finally:
                result['finishedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
                (folder / 'acceptance.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
                print('Evidence: ' + str(folder / 'acceptance.json'), flush=True)


if __name__ == '__main__':
    main()
