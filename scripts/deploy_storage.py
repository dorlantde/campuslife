"""Explicit private S3 provisioning. Host-only signing identity; no global trust changes."""
import datetime as dt
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import shutil
import urllib.parse
import uuid

STORAGE_SECRETS = ['FILE_S3_ACCESS_KEY', 'FILE_S3_SECRET_KEY', 'S3_ADMIN_ACCESS_KEY', 'S3_ADMIN_SECRET_KEY',
                   'S3_SERVER_CONFIG', 'S3_CA_CERT', 'S3_CA_KEY', 'S3_SERVER_CERT', 'S3_SERVER_KEY', 'S3_JAVA_TRUSTSTORE']


def keytool(root):
    candidates = sorted((root / '.tools/java21').glob('*/bin/keytool.exe'), reverse=True)
    executable = str(candidates[0]) if candidates else shutil.which('keytool')
    if not executable:
        raise RuntimeError('Java keytool is required to prepare this private trust store')
    return executable


def prepare_storage(folder, identity):
    from deploy_base import ROOT, run, write_secret
    directory = folder / 'secrets'
    if not shutil.which('openssl'):
        raise RuntimeError('OpenSSL is required to generate the instance-local CA')
    tool = keytool(ROOT)
    openssl_config = folder / 'openssl.cnf'
    openssl_config.write_text('[req]\nprompt=no\ndistinguished_name=dn\n[dn]\nCN=CampusLife\n', encoding='ascii')
    cert_env = dict(os.environ)
    cert_env['OPENSSL_CONF'] = str(openssl_config)
    namespace = str(uuid.uuid4())
    bucket = 'campuslife-' + identity
    values = {'FILE_S3_ACCESS_KEY': 'app' + identity, 'FILE_S3_SECRET_KEY': secrets.token_hex(32),
              'S3_ADMIN_ACCESS_KEY': 'init' + identity, 'S3_ADMIN_SECRET_KEY': secrets.token_hex(32)}
    for name, value in values.items():
        write_secret(directory, name, value)
    config = {'identities': [
        {'name': 'initializer', 'credentials': [{'accessKey': values['S3_ADMIN_ACCESS_KEY'], 'secretKey': values['S3_ADMIN_SECRET_KEY']}], 'actions': ['Admin', 'Read', 'Write', 'List']},
        {'name': 'app', 'credentials': [{'accessKey': values['FILE_S3_ACCESS_KEY'], 'secretKey': values['FILE_S3_SECRET_KEY']}],
         'actions': ['Read:' + bucket + '/' + namespace + '/objects/*', 'Write:' + bucket + '/' + namespace + '/objects/*', 'Read:' + bucket + '/_storage-id*']}]}
    write_secret(directory, 'S3_SERVER_CONFIG', json.dumps(config))
    run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '365',
         '-subj', '/CN=CampusLife-' + identity + '-CA', '-addext', 'basicConstraints=critical,CA:TRUE',
         '-addext', 'keyUsage=critical,keyCertSign,cRLSign', '-keyout', str(directory / 'S3_CA_KEY'), '-out', str(directory / 'S3_CA_CERT')], env=cert_env)
    csr = folder / 'server.csr'
    extensions = folder / 'server.ext'
    extensions.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:seaweed\n', encoding='ascii')
    run(['openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-subj', '/CN=seaweed',
         '-keyout', str(directory / 'S3_SERVER_KEY'), '-out', str(csr)], env=cert_env)
    run(['openssl', 'x509', '-req', '-in', str(csr), '-CA', str(directory / 'S3_CA_CERT'), '-CAkey', str(directory / 'S3_CA_KEY'),
         '-set_serial', str(int.from_bytes(secrets.token_bytes(16), 'big')), '-days', '90', '-sha256', '-extfile', str(extensions), '-out', str(directory / 'S3_SERVER_CERT')], env=cert_env)
    password = folder / 'trust-build-password'
    password.write_text(secrets.token_hex(32), encoding='ascii')
    try:
        # Certificate-only JKS. JSSE reads public trust entries with a null password;
        # integrity is protected by the read-only mount and recorded file digest, not a leaked JVM argument.
        run([tool, '-importcert', '-noprompt', '-alias', 'campuslife-private-ca', '-file', str(directory / 'S3_CA_CERT'),
             '-keystore', str(directory / 'S3_JAVA_TRUSTSTORE'), '-storetype', 'JKS', '-storepass:file', str(password)])
    finally:
        password.unlink(missing_ok=True)
        csr.unlink(missing_ok=True)
        extensions.unlink(missing_ok=True)
    for name in STORAGE_SECRETS:
        (directory / name).chmod(0o444)
    hashes = {name: hashlib.sha256((directory / name).read_bytes()).hexdigest() for name in ['S3_CA_CERT', 'S3_SERVER_CERT', 'S3_JAVA_TRUSTSTORE']}
    return {'bucket': bucket, 'namespace': namespace, 'pkiSha256': hashes}


def storage_request(deployment, method, key='', body=b'', *, bucket=None, query=None, admin=False,
                    anonymous=False, endpoint='https://seaweed:8333', secret_override=None):
    """Sign one exact request in host memory; curl verifies TLS inside the owned app network.

    Signing inputs/Authorization are stdin, never Docker argv or log output. This provisioning
    client is not part of business upload/download; those still use the existing Java SDK.
    """
    m = deployment.manifest['storage']
    uri = urllib.parse.urlsplit(endpoint)
    if endpoint not in ['https://seaweed:8333', 'https://seaweed-wrong:8333']:
        raise ValueError('Only this owned storage service can receive signing material')
    bucket = m['bucket'] if bucket is None else bucket
    path = '/' + bucket + ('/' + key if key else '') if bucket else '/'
    canonical_path = urllib.parse.quote(path, safe='/-_.~')
    canonical_query = urllib.parse.urlencode(sorted((query or {}).items()), quote_via=urllib.parse.quote, safe='~')
    url = endpoint + canonical_path + ('?' + canonical_query if canonical_query else '')
    now = dt.datetime.now(dt.timezone.utc)
    stamp, day = now.strftime('%Y%m%dT%H%M%SZ'), now.strftime('%Y%m%d')
    payload_hash = hashlib.sha256(body).hexdigest()
    headers = {'host': uri.netloc, 'x-amz-content-sha256': payload_hash, 'x-amz-date': stamp}
    if not anonymous:
        prefix = 'S3_ADMIN' if admin else 'FILE_S3'
        access = (deployment.folder / 'secrets' / (prefix + '_ACCESS_KEY')).read_text().strip()
        secret = secret_override or (deployment.folder / 'secrets' / (prefix + '_SECRET_KEY')).read_text().strip()
        signed = ';'.join(sorted(headers))
        canonical_headers = ''.join(k + ':' + headers[k] + '\n' for k in sorted(headers))
        canonical = '\n'.join([method, canonical_path, canonical_query, canonical_headers, signed, payload_hash])
        scope = day + '/us-east-1/s3/aws4_request'
        to_sign = 'AWS4-HMAC-SHA256\n' + stamp + '\n' + scope + '\n' + hashlib.sha256(canonical.encode()).hexdigest()
        def sign(key_bytes, data):
            return hmac.new(key_bytes, data.encode(), hashlib.sha256).digest()
        signing = sign(sign(sign(sign(('AWS4' + secret).encode(), day), 'us-east-1'), 's3'), 'aws4_request')
        headers['Authorization'] = 'AWS4-HMAC-SHA256 Credential=' + access + '/' + scope + ', SignedHeaders=' + signed + ', Signature=' + hmac.new(signing, to_sign.encode(), hashlib.sha256).hexdigest()
    config = 'silent\nshow-error\nmax-time = 20\ncacert = "/run/secrets/S3_CA_CERT"\nurl = "' + url + '"\nrequest = "' + method + '"\nwrite-out = "\\n%{http_code}"\n'
    if method == 'HEAD':
        config += 'head\n'
    for name, value in headers.items():
        config += 'header = "' + name + ': ' + value + '"\n'
    arguments = []
    temporary = None
    try:
        if body or method in ['PUT', 'POST']:
            temporary = deployment.folder / ('storage-request-' + uuid.uuid4().hex)
            temporary.write_bytes(body)
            temporary.chmod(0o444)
            arguments += ['-v', str(temporary) + ':/run/storage-request:ro']
            config += 'data-binary = "@/run/storage-request"\n'
        output = deployment.compose('run', '--rm', '-T', '--no-deps', *arguments, '--entrypoint', 'curl', 'app', '--config', '-',
                                    stdin=config, timeout=45)
        content, status = output.rsplit('\n', 1) if '\n' in output else ('', output)
        return int(status), content
    finally:
        if temporary:
            temporary.chmod(0o600)
            temporary.unlink(missing_ok=True)


def initialize_storage(deployment):
    deployment.validate()
    if deployment.mode != 'showcase':
        raise ValueError('StorageInit requires the explicit showcase mode')
    deployment.compose('up', '-d', '--wait', '--wait-timeout', '90', 'seaweed', timeout=120, log=deployment.folder / 'storage-start.log')
    bucket = deployment.manifest['storage']['bucket']
    namespace = deployment.manifest['storage']['namespace']
    status, marker = storage_request(deployment, 'GET', '_storage-id', admin=True)
    if status == 404:
        status, _ = storage_request(deployment, 'HEAD', admin=True)
        if status == 404:
            status, _ = storage_request(deployment, 'PUT', admin=True)
            if status != 200:
                raise RuntimeError('Cannot initialize the new owned bucket')
            status, _ = storage_request(deployment, 'PUT', '_storage-id', namespace.encode(), admin=True)
            if status != 200:
                raise RuntimeError('Marker result uncertain; inspect owned storage before retrying')
        else:
            raise RuntimeError('Existing unmarked bucket: refusing to claim or overwrite it')
    elif status != 200 or marker != namespace:
        raise RuntimeError('Storage identity mismatch/unavailable; no marker overwritten')
    policy = {'Version': '2012-10-17', 'Statement': [
        {'Effect': 'Deny', 'Principal': '*', 'Action': 's3:DeleteObject', 'Resource': ['arn:aws:s3:::' + bucket + '/' + namespace + '/objects/', 'arn:aws:s3:::' + bucket + '/' + namespace + '/objects', 'arn:aws:s3:::' + bucket + '/' + namespace + '/']},
        {'Effect': 'Deny', 'Principal': '*', 'Action': 's3:GetObject', 'Resource': 'arn:aws:s3:::' + bucket + '/_storage-id?*'}]}
    status, _ = storage_request(deployment, 'PUT', body=json.dumps(policy).encode(), query={'policy': ''}, admin=True)
    if status not in [200, 204]:
        raise RuntimeError('Cannot install the adopted narrow storage policy')
    status, marker = storage_request(deployment, 'GET', '_storage-id')
    if status != 200 or marker != namespace:
        raise RuntimeError('Application identity cannot verify its exact marker')
    (deployment.folder / 'storage-ready.json').write_text(json.dumps({'bucket': bucket, 'namespace': namespace}), encoding='utf-8')
