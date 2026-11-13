"""B2 TLS-only storage fixture. No production data, clocks or certificate-verification bypass."""
import argparse
import hashlib
import http.client as http_client
import json
import os
from pathlib import Path
import secrets
import time
import uuid

from deploy_base import ROOT, Deployment, initialize, run
from deploy_storage import initialize_storage, storage_request, keytool
from test_deploy_components import free_port, base_smoke
from test_deploy_base import pdf_bytes


def main(phase):
    folder = initialize(free_port(), docs=True, test=True, mode='showcase')
    d = Deployment(folder)
    result = {'task': 'M06.1-B2', 'phase': phase, 'checks': [], 'instance': str(folder.relative_to(ROOT))}

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        if name in result['checks']:
            raise AssertionError('Duplicate check label')
        result['checks'].append(name)

    def denied_tls(call):
        try:
            call()
            return False
        except RuntimeError as error:
            return 'certificate' in str(error).lower() or 'ssl' in str(error).lower()

    try:
        print('Preparing explicit native TLS-only storage and adopted marker policy.', flush=True)
        initialize_storage(d)
        ns = d.manifest['storage']['namespace']
        bucket = d.manifest['storage']['bucket']
        check('native-correct-ca-and-marker', storage_request(d, 'GET', '_storage-id') == (200, ns))
        check('native-wrong-hostname-is-rejected', denied_tls(lambda: storage_request(d, 'GET', '_storage-id', endpoint='https://seaweed-wrong:8333')))
        config = 'silent\nshow-error\nmax-time = 3\nurl = "http://seaweed:8333/' + bucket + '/_storage-id"\nwrite-out = "\\n%{http_code}"\n'
        plain = d.compose('run', '--rm', '-T', '--no-deps', '--entrypoint', 'curl', 'app', '--config', '-', stdin=config)
        check('main-port-rejects-plaintext-http', plain.endswith('400') and ns not in plain)
        check('anonymous-and-wrong-signature-denied', storage_request(d, 'GET', '_storage-id', anonymous=True)[0] == 403 and storage_request(d, 'GET', '_storage-id', secret_override='invalid-fixture-secret')[0] == 403)
        leaf = uuid.uuid4().hex + '.pdf'
        key = ns + '/objects/' + leaf
        probe = b'private TLS protocol fixture'
        check('app-exact-prefix-put-get-head', storage_request(d, 'PUT', key, probe)[0] == 200 and storage_request(d, 'GET', key) == (200, probe.decode()) and storage_request(d, 'HEAD', key)[0] == 200)
        check('app-exact-delete-is-idempotent', storage_request(d, 'DELETE', key)[0] in [200, 204] and storage_request(d, 'DELETE', key)[0] in [200, 204] and storage_request(d, 'GET', key)[0] == 404)
        foreign = 'foreign/objects/' + leaf
        storage_request(d, 'PUT', foreign, probe, admin=True)
        check('foreign-prefix-read-write-delete-denied', all(storage_request(d, method, foreign, probe if method == 'PUT' else b'')[0] == 403 for method in ['GET', 'PUT', 'DELETE']))
        storage_request(d, 'PUT', '_storage-id-shadow', b'not-a-marker', admin=True)
        check('marker-exact-read-only-and-shadow-denied', storage_request(d, 'PUT', '_storage-id', b'bad')[0] == 403 and storage_request(d, 'DELETE', '_storage-id')[0] == 403 and storage_request(d, 'GET', '_storage-id-shadow')[0] == 403)
        check('namespace-directory-delete-is-denied', all(storage_request(d, 'DELETE', k)[0] == 403 for k in [ns + '/', ns + '/objects', ns + '/objects/']))
        check('app-has-no-bucket-policy-authority', storage_request(d, 'PUT', body=b'{}', query={'policy': ''})[0] == 403)
        status, listing = storage_request(d, 'GET', bucket='')
        check('bucket-list-keeps-original-empty-semantics', status == 200 and '<Bucket>' not in listing)
        storage_request(d, 'PUT', '_storage-id', b'wrong-identity', admin=True)
        try:
            initialize_storage(d)
            raise AssertionError('Initializer overwrote a mismatched identity')
        except RuntimeError:
            check('initializer-refuses-marker-mismatch', storage_request(d, 'GET', '_storage-id', admin=True) == (200, 'wrong-identity'))
        finally:
            storage_request(d, 'PUT', '_storage-id', ns.encode(), admin=True)
        if phase != 'Native':
            business(d, result, check)
        result['passed'] = True
        print('PASS: ' + str(len(result['checks'])) + ' TLS storage checks.', flush=True)
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
                (folder / 'storage-acceptance.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
                print('Evidence: ' + str(folder / 'storage-acceptance.json'), flush=True)


def business(d, result, check):
    folder, port = d.folder, d.manifest['port']
    print('Starting seven services and exercising the actual Java SDK with private trust.', flush=True)
    d.start()
    check('seven-services-storage-isolated-and-unpublished', len(d.resources('container')) == 7 and len(d.resources('network')) == 3)
    check('application-has-no-server-ca-private-or-init-secrets', d.compose('exec', '-T', 'app', 'sh', '-c', 'test ! -e /run/secrets/S3_SERVER_KEY && test ! -e /run/secrets/S3_CA_KEY && test ! -e /run/secrets/S3_ADMIN_SECRET_KEY && test ! -e /run/secrets/S3_SERVER_CONFIG && echo isolated') == 'isolated')

    def http(method, path, body=None, token=None, headers=None, raw=False):
        c = http_client.HTTPConnection('127.0.0.1', port, timeout=60)
        supplied = dict(headers or {})
        if token:
            supplied['Authorization'] = 'Bearer ' + token
        if body is not None and not isinstance(body, bytes):
            supplied['Content-Type'] = 'application/json'
            body = json.dumps(body, ensure_ascii=False).encode()
        c.request(method, path, body, supplied)
        r = c.getresponse(); data = r.read(); status = r.status; h = dict(r.getheaders()); c.close()
        return status, h, data if raw else json.loads(data)

    def api(method, path, body=None, token=None):
        status, _, data = http(method, '/api/v1' + path, body, token)
        if status not in [200, 201]:
            raise AssertionError(method + ' ' + path + ': ' + str(status) + ' ' + str(data.get('code')))
        return data['data']

    def upload(pdf, token, name='校园TLS验收😀.pdf'):
        boundary = 's3tls' + secrets.token_hex(8)
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + name + '"\r\nContent-Type: application/pdf\r\n\r\n').encode() + pdf + ('\r\n--' + boundary + '--\r\n').encode()
        return http('POST', '/api/v1/files', body, token, {'Content-Type': 'multipart/form-data; boundary=' + boundary})

    def sql(statement):
        return d.compose('exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$(cat /run/secrets/DB_PASSWORD)" exec mysql --protocol=TCP --host=127.0.0.1 --user=campuslife --database=campuslife_deploy --batch --skip-column-names', stdin=statement)

    def wait(predicate, description, seconds=35):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.3)
        raise AssertionError('Timed out: ' + description)

    def restart_app():
        # Recreate only after the old Java process has exited; there is never a second business instance.
        d.compose('up', '-d', '--wait', '--wait-timeout', '90', '--no-deps', '--force-recreate', 'app', timeout=120, log=folder / 'app-recreate.log')
        wait(lambda: http('GET', '/api/v1/system/health')[0] == 200, 'gateway follows recreated app')

    def content(identity, token):
        return http('GET', '/api/v1/files/' + identity + '/content', token=token, raw=True)

    password = 'TLS_' + secrets.token_hex(12)
    name = 'tls_s_' + secrets.token_hex(6)
    api('POST', '/auth/register', {'username': name, 'password': password, 'nickname': 'TLS验收同学😀'})
    token = api('POST', '/auth/login', {'username': name, 'password': password})['accessToken']
    sample = pdf_bytes()
    status, _, response = upload(sample, token)
    first = response.get('data')
    check('java-sdk-private-ca-upload-and-full-readback', status == 201 and first['originalName'] == '校园TLS验收😀.pdf' and content(first['id'], token)[2] == sample)
    check('database-locator-is-s3-with-owned-namespace', sql('SELECT CONCAT(storage_kind,\':\',storage_id) FROM file_asset WHERE id=' + first['id'] + ';') == 'S3:' + d.manifest['storage']['namespace'])
    check('anonymous-cannot-bypass-proxy-download-auth', content(first['id'], None)[0] == 401)
    spec_status, _, spec = http('GET', '/v3/api-docs')
    check('openapi-still-75-business-operations', spec_status == 200 and sum(k in ['get', 'post', 'put', 'patch', 'delete'] for row in spec['paths'].values() for k in row) == 75)
    print('Verifying real JVM wrong-CA and wrong-hostname failures without disabling TLS.', flush=True)
    secret = folder / 'secrets/S3_JAVA_TRUSTSTORE'
    good_store = secret.read_bytes()
    wrong_key, wrong_cert, wrong_store, store_pass = [folder / n for n in ['wrong-ca-key', 'wrong-ca-cert', 'wrong-ca-store', 'wrong-store-password']]
    env = dict(os.environ); env['OPENSSL_CONF'] = str(folder / 'openssl.cnf')
    run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2', '-subj', '/CN=UnrelatedTestCA', '-keyout', str(wrong_key), '-out', str(wrong_cert)], env=env)
    wrong_key.unlink()
    store_pass.write_text(secrets.token_hex(32), encoding='ascii')
    try:
        run([keytool(ROOT), '-importcert', '-noprompt', '-alias', 'unrelated-ca', '-file', str(wrong_cert), '-keystore', str(wrong_store), '-storetype', 'JKS', '-storepass:file', str(store_pass)])
    finally:
        store_pass.unlink(missing_ok=True)
    try:
        secret.chmod(0o600); secret.write_bytes(wrong_store.read_bytes()); secret.chmod(0o444)
        restart_app()
        status, _, failure = upload(sample, token, 'wrong-ca.pdf')
        check('actual-java-sdk-rejects-wrong-ca-without-local-fallback', status == 503 and failure['code'] == 'FILE_STORAGE_UNAVAILABLE' and sql("SELECT COUNT(*) FROM file_asset WHERE status='AVAILABLE';") == '1')
    finally:
        secret.chmod(0o600); secret.write_bytes(good_store); secret.chmod(0o444)
    d.env['STORAGE_ENDPOINT'] = 'https://seaweed-wrong:8333'
    try:
        restart_app()
        status, _, failure = upload(sample, token, 'wrong-host.pdf')
        check('actual-java-sdk-rejects-wrong-hostname', status == 503 and failure['code'] == 'FILE_STORAGE_UNAVAILABLE')
    finally:
        d.env['STORAGE_ENDPOINT'] = 'https://seaweed:8333'
        restart_app()
    check('original-s3-content-works-after-trust-restored', content(first['id'], token)[2] == sample)
    print('Verifying LOCAL coexistence and a full 20 MiB S3 upload through Nginx.', flush=True)
    # Only fixture data: prepare an old LOCAL locator without migrating any user or existing deployment.
    d.env['STORAGE_UPLOAD_TARGET'] = 'LOCAL'
    restart_app()
    local = upload(sample, token, '旧LOCAL样本.pdf')[2]['data']
    check('fixture-local-locator-is-preserved', sql('SELECT storage_kind FROM file_asset WHERE id=' + local['id'] + ';') == 'LOCAL')
    d.env['STORAGE_UPLOAD_TARGET'] = 'S3'
    restart_app()
    large = pdf_bytes(20 * 1024 * 1024)
    status, _, big = upload(large, token, '20MiB.pdf')
    check('20mib-through-nginx-and-java-s3-checksum-chain', status == 201 and big['data']['byteSize'] == len(large) and hashlib.sha256(content(big['data']['id'], token)[2]).digest() == hashlib.sha256(large).digest())
    check('old-local-and-new-s3-coexist-without-relocation', content(local['id'], token)[2] == sample and content(first['id'], token)[2] == sample and sql('SELECT storage_kind FROM file_asset WHERE id=' + local['id'] + ';') == 'LOCAL')
    # Expire only a fresh, unreferenced fixture row; startup invokes the unmodified real cleaner.
    doomed = upload(sample, token, '清理样本.pdf')[2]['data']
    object_key = sql('SELECT storage_key FROM file_asset WHERE id=' + doomed['id'] + ';')
    time.sleep(1.1)
    sql('UPDATE file_asset SET expires_at=DATE_ADD(created_at,INTERVAL 1 SECOND) WHERE id=' + doomed['id'] + ';')
    marker = d.compose('exec', '-T', 'app', 'cat', '/var/lib/campuslife/files/.storage-id')
    print('Restarting all seven services and checking old reads, new writes and exact cleanup.', flush=True)
    stopped = d.stop()
    check('stop-retains-five-owned-persistent-volumes', stopped['volumes'] == 5 and all(s['state'] == 'exited' for s in stopped['services']))
    d.start()
    wait(lambda: sql('SELECT status FROM file_asset WHERE id=' + doomed['id'] + ';') == 'DELETED', 'actual TLS cleanup')
    check('actual-java-cleaner-deletes-exact-s3-object', storage_request(d, 'HEAD', d.manifest['storage']['namespace'] + '/objects/' + object_key, admin=True)[0] == 404)
    check('restart-keeps-local-marker-and-both-backends', marker == d.compose('exec', '-T', 'app', 'cat', '/var/lib/campuslife/files/.storage-id') and content(local['id'], token)[2] == sample and content(first['id'], token)[2] == sample)
    new_status, _, new_upload = upload(sample, token, '重启新写.pdf')
    check('cold-restart-new-write-full-readback-succeeds', new_status == 201 and content(new_upload['data']['id'], token)[2] == sample)
    result['storage'] = d.manifest['storage']
    result['jarSha256'] = d.manifest['jarSha256']


def components_config_regression():
    folder = initialize(free_port(), test=True, mode='components')
    d = Deployment(folder)
    result = {'task': 'M06.1-B2 components config regression'}
    try:
        config = d.validate()
        env = config['services']['app']['environment']
        assert len(config['services']) == 6 and env['FILE_S3_ENABLED'] == 'false' and env['FILE_UPLOAD_TARGET'] == 'LOCAL'
        assert len(list((folder / 'secrets').iterdir())) == 13
        result.update(passed=True, checks=['six-service-mode-remains-local-without-storage-secrets'])
    finally:
        d.destroy_test()
        result['cleanup'] = {'containers': 0, 'volumes': 0, 'networks': 0, 'generatedSecretsRemoved': True}
        (folder / 'storage-config-regression.json').write_text(json.dumps(result), encoding='utf-8')
        print('Evidence: ' + str(folder / 'storage-config-regression.json'), flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--phase', choices=['All', 'Native', 'Business', 'Regression'], default='All')
    phase = p.parse_args().phase
    if phase in ['All', 'Native', 'Business']:
        main(phase)
    if phase in ['All', 'Regression']:
        base_smoke()
        components_config_regression()
