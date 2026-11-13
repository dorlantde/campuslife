"""Run on the verified ECS. Unissued technical fixture and one packaged Resume trace; never a business acceptance."""
import argparse
import datetime as dt
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--release', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    args = parser.parse_args()
    release, output = args.release.resolve(), args.output_dir.resolve()
    if not output.is_relative_to(release/'.cache/diagnostics'):
        raise ValueError('Use a new child of this release .cache/diagnostics')
    os.umask(0o077)
    output.mkdir(parents=True, exist_ok=False)
    sys.path.insert(0, str(release/'scripts'))
    helper_hashes = {'test_deploy_cloud.py': 'f6d57c9b573008f451c7d23ec4e3bdc12a0737fe7c9342f74c9e3fa9d7ae875e', 'test_deploy_compact4g.py': '2e3d7a21f19ffdf78bb2ec241d7164356855abe1551ad6394fa72aa81bd46c46', 'test_deploy_showcase.py': '02174e873d57cd4f57d50ed4c72ca35f5d74f7c81f02f8589af9a50ce5328a35', 'test_deploy_components.py': 'fb1277540e06b9c119a8286be1f68fc74daf4e4e9b02f9549a293ed39e6e03f0', 'test_deploy_base.py': '471bdc9fe0db6a07749f89cf90b9448befe08caa009d4705181b092668b80e1c', 'deploy_recovery.py': 'ff0df6551729bd0a8f4dce474dcfe409151664f7eff21861f5169750e62a488b', 'deploy_base.py': '69dafb6e1e393d751b2f6c893e01549baacb0bb8722defbf935cda5620ec2977'}
    assert all(hashlib.sha256((release/'scripts'/name).read_bytes()).hexdigest()==value for name,value in helper_hashes.items())
    from deploy_base import Deployment, docker, initialize
    from deploy_storage import initialize_storage
    from test_deploy_components import free_port
    from test_deploy_cloud import native_host, CloudMonitor
    host = native_host()
    monitor = CloudMonitor(host)
    if docker('ps', '-aq') or docker('volume', 'ls', '-q'):
        raise RuntimeError('The scoped diagnosis requires no pre-existing running data resources')
    record = json.loads((release/'.cache/deploy-base-build.json').read_text())
    assert record['jarSha256']=='4dfd3a9f1efdd8f344417ac5c1ebe989a1dfdd1dd689499f4c7978017e9cef00'
    assert record['imageId']=='sha256:8d654a790e39c6dbd7d1910b83458a3b5a1318aa107517ab8d69d86283fbf367'
    jar = release/'target/campuslife-0.0.1-SNAPSHOT.jar'
    if hashlib.sha256(jar.read_bytes()).hexdigest() != record['jarSha256']:
        raise RuntimeError('Original JAR fingerprint differs')
    source = Path(__file__).with_name('CouponPreflightTrace.java')
    libraries, classes = output/'lib', output/'classes'
    libraries.mkdir()
    classes.mkdir(mode=0o755)
    classes.chmod(0o755)
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (libraries/Path(name).name).write_bytes(archive.read(name))
    compiled = subprocess.run(['javac', '-J-Xmx256m', '--release', '21', '-cp',
        str(release/'target/classes')+':'+str(libraries/'*'), '-d', str(classes), str(source)],
        capture_output=True, text=True, timeout=60)
    (output/'compile.log').write_text(compiled.stdout+compiled.stderr)
    if compiled.returncode:
        raise RuntimeError('Diagnostic compile failed; no instance created')
    assert all(path.relative_to(classes).parts[0]=='diagnostic' for path in classes.rglob('*.class'))
    for path in classes.rglob('*'):
        path.chmod(0o755 if path.is_dir() else 0o644)

    folder = initialize(free_port(), docs=True, test=True, mode='showcase', observe=True, resource_profile='compact4g')
    deployment = Deployment(folder)
    result = {'task': 'M06.1-D.6f', 'diagnosticOnly': True, 'directSqlTechnicalSeed': True,
        'instance': str(folder), 'host': host, 'jarSha256': record['jarSha256'], 'imageId': record['imageId'],
        'checks': [], 'phase': 'initializing', 'diagnosisCaptured': False, 'operationSucceeded': None,
        'snapshots': [], 'startedAt': dt.datetime.now(dt.timezone.utc).isoformat()}
    (output/'active.json').write_text(json.dumps({'instance': str(folder), 'identity': deployment.manifest['identity']}))
    campaign = None

    def phase(value):
        result['phase'] = value
        (output/'progress.json').write_text(json.dumps({'phase': value, 'instance': str(folder)}))
        print('PHASE '+value, flush=True)

    def api(method, path, body=None, token=None, key=None):
        connection = http.client.HTTPConnection('127.0.0.1', deployment.manifest['port'], timeout=30)
        headers = {}
        if token: headers['Authorization'] = 'Bearer '+token
        if key: headers['Idempotency-Key'] = key
        if body is not None:
            body = json.dumps(body).encode()
            headers['Content-Type'] = 'application/json'
        connection.request(method, '/api/v1'+path, body, headers)
        response = connection.getresponse()
        data = json.loads(response.read())
        connection.close()
        if response.status not in [200, 201, 202]:
            raise RuntimeError('Fixture API status='+str(response.status)+' code='+str(data.get('code')))
        return data['data']

    def sql(statement):
        return deployment.compose('exec', '-T', 'mysql', 'sh', '-c',
            'MYSQL_PWD="$(cat /run/secrets/DB_PASSWORD)" exec mysql --protocol=TCP --host=127.0.0.1 '
            '--user=campuslife --database=campuslife_deploy --batch --skip-column-names', stdin=statement, timeout=30)

    def redis(*commands):
        return deployment.compose('exec', '-T', 'coupon-redis', 'sh', '-c',
            'REDISCLI_AUTH="$(cat /run/secrets/COUPON_REDIS_OPS_PASSWORD)"; export REDISCLI_AUTH; '
            'exec redis-cli --user redis_ops --raw "$@"', 'diagnostic-read', *commands, timeout=20)

    def snapshot(label):
        value = {'label': label, 'at': dt.datetime.now(dt.timezone.utc).isoformat()}
        if campaign:
            value['sqlGate'] = sql("SELECT mode,phase,generation,control_version,COALESCE(resume_phase,'NULL'),"
                "COALESCE(DATE_FORMAT(lease_until,'%Y-%m-%dT%H:%i:%s.%f'),'NULL'),COALESCE(redis_run_id,'NULL'),"
                "rebuild_token IS NOT NULL,UTC_TIMESTAMP(3) FROM coupon_dispatch_gate WHERE campaign_id="+campaign+';')
            value['counts'] = sql('SELECT issued_count FROM coupon_campaign WHERE id='+campaign+'; '
                'SELECT COUNT(*) FROM user_coupon WHERE campaign_id='+campaign+'; '
                "SELECT COUNT(*) FROM coupon_claim_request WHERE campaign_id="+campaign+" AND status='PENDING';")
            info = re.search(r'^run_id:([a-f0-9]{40})\r?$', redis('INFO', 'server'), re.M)
            value['redisRunId'] = info.group(1) if info else None
            control = 'coupon:'+deployment.env['DATA_NAMESPACE']+':{'+campaign+'}:control'
            value['redisControl'] = redis('HMGET', control, 'generation', 'run', 'phase').splitlines()
            generation = sql('SELECT generation FROM coupon_dispatch_gate WHERE campaign_id='+campaign+';')
            epoch = 'coupon:'+deployment.env['DATA_NAMESPACE']+':{'+campaign+'}:g:'+generation
            value['redisCounters'] = redis('HMGET', epoch, '#total', '#free', '#held', '#spent', '#expectedSpent', '#seenSpent').splitlines()
        value['hostLoadAverage'] = list(os.getloadavg())
        value['hostCpuPressure'] = Path('/proc/pressure/cpu').read_text() if Path('/proc/pressure/cpu').exists() else None
        stats = redis('INFO', 'stats')
        selected = ['total_connections_received','rejected_connections','total_error_replies']
        value['redisServerStats'] = {key: int(match.group(1)) for key in selected
            if (match := re.search(r'^'+key+r':([0-9]+)\r?$', stats, re.M))}
        try:
            records = json.loads(redis('--json', 'ACL', 'LOG', '32'))
            counts = {}
            for row in records:
                item = row if isinstance(row,dict) else dict(zip(row[::2],row[1::2]))
                reason = item.get('reason','OTHER')
                if reason not in ['auth','command','key','channel']: reason='OTHER'
                counts[reason] = counts.get(reason,0)+int(item.get('count',0))
            value['redisAclSummary'] = {'available':True,'countsByReason':counts}
        except (RuntimeError,ValueError,TypeError):
            value['redisAclSummary'] = {'available':False}
        result['snapshots'].append(value)
        (output/'snapshots.json').write_text(json.dumps(result['snapshots'], indent=2))
        return value

    def ready(label):
        end = time.monotonic()+60
        while time.monotonic() < end:
            fields = sql("SELECT mode,phase,generation,COALESCE(redis_run_id,'') FROM coupon_dispatch_gate WHERE campaign_id="+campaign+';').split('\t')
            if len(fields)==4 and fields[:2]==['ASYNC', 'PAUSED'] and re.fullmatch('[a-f0-9]{40}', fields[3]):
                info = re.search(r'^run_id:([a-f0-9]{40})\r?$', redis('INFO', 'server'), re.M)
                control = 'coupon:'+deployment.env['DATA_NAMESPACE']+':{'+campaign+'}:control'
                if info and info.group(1)==fields[3] and redis('HMGET', control, 'generation', 'run', 'phase').splitlines()==[fields[2], fields[3], 'PAUSED']:
                    return snapshot(label)
            time.sleep(.5)
        snapshot(label+'-timeout')
        raise RuntimeError('Paused generation readiness budget expired')

    try:
        monitor.hook('begin', deployment, result)
        result.update(task='M06.1-D.6f', validationScope='One packaged Resume with safe observation; technical zero-issued fixture, not full acceptance.')
        result.pop('pdfBytes', None)
        phase('starting-private-fixture')
        initialize_storage(deployment)
        deployment.start()
        monitor.hook('started', deployment, result)
        phase('seeding-unissued-technical-paused-activity')
        category = next(c['id'] for c in api('GET', '/shop-categories') if c['code']=='PRINT')
        password = 'Diag_'+secrets.token_hex(14)
        merchant_name = 'diag_m_'+secrets.token_hex(5)
        shop = api('POST', '/merchant-applications', {'username': merchant_name, 'password': password,
            'nickname': '虚构诊断店主', 'categoryId': category, 'shopName': '隔离诊断店', 'locationText': '虚构校区'})
        merchant = api('POST', '/auth/login', {'username': merchant_name, 'password': password})['accessToken']
        sid = shop['shopId']
        # Deliberate technical seed, not an administrator-review acceptance.
        sql("UPDATE shop SET approval_status='APPROVED',version=version+1 WHERE id="+sid+
            "; UPDATE shop_member SET status='ACTIVE' WHERE shop_id="+sid+';')
        now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
        item = api('POST', '/merchant/coupon-campaigns', {'name': '诊断已有发券活动', 'thresholdAmount': 20,
            'reductionAmount': 5, 'totalQuantity': 2, 'claimStartAt': (now-dt.timedelta(seconds=30)).isoformat(),
            'claimEndAt': (now+dt.timedelta(hours=1)).isoformat(), 'useStartAt': (now-dt.timedelta(seconds=60)).isoformat(),
            'useEndAt': (now+dt.timedelta(hours=2)).isoformat()}, merchant)
        campaign = item['id']
        api('POST', '/merchant/coupon-campaigns/'+campaign+'/publish', {'expectedVersion': item['version']}, merchant)
        sql("UPDATE coupon_dispatch_gate SET mode='ASYNC',phase='PAUSED',control_version=2 WHERE campaign_id="+campaign+';')
        phase('waiting-initial-paused-generation')
        before = ready('before-single-resume')
        version = sql('SELECT control_version FROM coupon_dispatch_gate WHERE campaign_id='+campaign+';')
        deployment.env.update(COUPON_CAMPAIGN_ID=campaign, COUPON_CONTROL_VERSION=version,
            COUPON_MAINTENANCE_ACTION='Resume', COUPON_ALL_INSTANCES_UPGRADED='true')
        phase('one-packaged-resume-with-stage-and-command-observation')
        out = deployment.compose('run', '--rm', '-T', '--no-deps', '-v', str(classes)+':/run/diagnostic:ro',
            '-e', 'COUPON_CAMPAIGN_ID', '-e', 'COUPON_CONTROL_VERSION', '-e', 'COUPON_MAINTENANCE_ACTION',
            '-e', 'COUPON_ALL_INSTANCES_UPGRADED', '--entrypoint', 'java', 'app',
            '-XX:MaxRAMPercentage=65', '-XX:+ExitOnOutOfMemoryError', '-Dloader.path=/run/diagnostic',
            '-Dloader.main=diagnostic.CouponPreflightTrace', '-cp', '/opt/campuslife/app.jar',
            'org.springframework.boot.loader.launch.PropertiesLauncher', timeout=90, log=folder/'preflight-trace.log')
        assert all(secret not in out for secret in [password, merchant])
        result['trace'] = [line for line in out.splitlines() if line.startswith('DIAG ')]
        result['operationSucceeded'] = any(re.search(r'^DIAG atMs=[0-9]+ outcome=OK ', line) for line in result['trace'])
        result['diagnosisCaptured'] = any(re.search(r'^DIAG atMs=[0-9]+ outcome=FAILED ', line) for line in result['trace'])
        snapshot('after-single-resume')
        assert result['operationSucceeded'] or result['diagnosisCaptured'], 'No conclusive safe trace'
        phase('captured')
    except BaseException as failure:
        result['fixtureFailureType'] = type(failure).__name__
        try: snapshot('fixture-failure')
        except BaseException as read_failure: result['failureSnapshotType'] = type(read_failure).__name__
        raise
    finally:
        try:
            monitor.hook('end', deployment, result)
            deployment.compose('logs', '--no-color', timeout=30, log=folder/'diagnostic-services.log')
        finally:
            try:
                deployment.destroy_test()
                result['cleanup'] = {'containers': 0, 'volumes': 0, 'generatedSecretsRemoved': True}
            finally:
                result['finishedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
                result['jarUnchanged'] = hashlib.sha256(jar.read_bytes()).hexdigest()==record['jarSha256']
                (output/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
                print(json.dumps({'result': str(output/'result.json'), 'diagnosisCaptured': result['diagnosisCaptured'],
                    'operationSucceeded': result['operationSucceeded'], 'cleanup': result.get('cleanup')}), flush=True)


if __name__ == '__main__':
    main()
