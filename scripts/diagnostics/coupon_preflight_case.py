"""Run on the verified ECS. Targeted patched-source verification; not a replacement cloud JAR acceptance."""
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
    from deploy_base import Deployment, docker, initialize
    from deploy_storage import initialize_storage
    from test_deploy_components import free_port
    from test_deploy_cloud import native_host
    host = native_host()
    if docker('ps', '-aq') or docker('volume', 'ls', '-q'):
        raise RuntimeError('The scoped diagnosis requires no pre-existing running data resources')
    record = json.loads((release/'.cache/deploy-base-build.json').read_text())
    jar = release/'target/campuslife-0.0.1-SNAPSHOT.jar'
    if hashlib.sha256(jar.read_bytes()).hexdigest() != record['jarSha256']:
        raise RuntimeError('Original JAR fingerprint differs')
    source = Path(__file__).with_name('CouponPreflightVerification.java')
    libraries, classes = output/'lib', output/'classes'
    libraries.mkdir()
    classes.mkdir(mode=0o755)
    classes.chmod(0o755)
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (libraries/Path(name).name).write_bytes(archive.read(name))
    compiled = subprocess.run(['javac', '-J-Xmx256m', '--release', '21', '-cp',
        str(release/'target/classes')+':'+str(libraries/'*'), '-d', str(classes), str(source), str(source.with_name('CouponMaintenance.java')), str(source.with_name('CouponRedisRecovery.java'))],
        capture_output=True, text=True, timeout=60)
    (output/'compile.log').write_text(compiled.stdout+compiled.stderr)
    if compiled.returncode:
        raise RuntimeError('Diagnostic compile failed; no instance created')
    for path in classes.rglob('*'):
        path.chmod(0o755 if path.is_dir() else 0o644)

    libraries.chmod(0o755)
    for path in libraries.iterdir(): path.chmod(0o644)

    folder = initialize(free_port(), docs=True, test=True, mode='showcase', observe=True, resource_profile='compact4g')
    deployment = Deployment(folder)
    result = {'task': 'M06.1-D.6c', 'sourceClasspathVerification': True, 'newJarDeployed': False, 'directSqlTechnicalSeed': True,
        'instance': str(folder), 'host': host, 'jarSha256': record['jarSha256'], 'imageId': record['imageId'],
        'phase': 'initializing', 'resumeAndNewClaim': False, 'syncAndSyncResume': False,
        'snapshots': [], 'startedAt': dt.datetime.now(dt.timezone.utc).isoformat()}
    (output/'active.json').write_text(json.dumps({'instance': str(folder), 'identity': deployment.manifest['identity']}))
    campaign = None

    def phase(value):
        result['phase'] = value
        (output/'progress.json').write_text(json.dumps({'phase': value, 'instance': str(folder)}))
        print('PHASE '+value, flush=True)

    def api(method, path, body=None, token=None, key=None, expected=None):
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
        if response.status not in [200, 201, 202] or (expected is not None and response.status!=expected):
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
        phase('starting-private-fixture')
        initialize_storage(deployment)
        deployment.start()
        phase('seeding-one-issued-technical-record')
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
            'reductionAmount': 5, 'totalQuantity': 3, 'claimStartAt': (now-dt.timedelta(seconds=30)).isoformat(),
            'claimEndAt': (now+dt.timedelta(hours=1)).isoformat(), 'useStartAt': (now-dt.timedelta(seconds=60)).isoformat(),
            'useEndAt': (now+dt.timedelta(hours=2)).isoformat()}, merchant)
        campaign = item['id']
        api('POST', '/merchant/coupon-campaigns/'+campaign+'/publish', {'expectedVersion': item['version']}, merchant)
        name = 'diag_s_'+secrets.token_hex(5)
        api('POST', '/auth/register', {'username': name, 'password': password, 'nickname': '虚构诊断同学'})
        student = api('POST', '/auth/login', {'username': name, 'password': password})['accessToken']
        api('POST', '/coupon-claims', {'campaignId': campaign}, student, 'diag-issued')
        assert sql('SELECT issued_count FROM coupon_campaign WHERE id='+campaign+'; SELECT COUNT(*) FROM user_coupon;')=='1\n1'
        reservation = secrets.token_hex(16)
        sql("UPDATE coupon_claim_request SET source_mode='ASYNC',reservation_id='"+reservation+
            "',accepted_generation=1,accepted_at=created_at,deadline=TIMESTAMPADD(MINUTE,5,created_at),"
            "redis_settled_generation=1 WHERE campaign_id="+campaign+"; "
            "UPDATE coupon_dispatch_gate SET mode='ASYNC',phase='PAUSED',control_version=2 WHERE campaign_id="+campaign+';')
        phase('waiting-initial-issued-generation')
        before = ready('before-maintenance')
        original_coupon = sql('SELECT id FROM user_coupon WHERE campaign_id='+campaign+';')
        result['compiledProductionClasses'] = {str(path.relative_to(classes)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in classes.rglob('Coupon*.class')}

        def probe(action):
            deployment.env.update(COUPON_CAMPAIGN_ID=campaign,
                COUPON_CONTROL_VERSION=sql('SELECT control_version FROM coupon_dispatch_gate WHERE campaign_id='+campaign+';'),
                DIAGNOSTIC_ACTION=action)
            out = deployment.compose('run', '--rm', '-T', '--no-deps',
                '-v', str(classes)+':/run/diagnostic:ro', '-v', str(release/'target/classes')+':/run/original:ro',
                '-v', str(libraries)+':/run/lib:ro', '-e', 'COUPON_CAMPAIGN_ID', '-e', 'COUPON_CONTROL_VERSION',
                '-e', 'DIAGNOSTIC_ACTION', '--entrypoint', 'java', 'app',
                '-XX:MaxRAMPercentage=65', '-XX:+ExitOnOutOfMemoryError', '-cp',
                '/run/diagnostic:/run/original:/run/lib/*', 'diagnostic.CouponPreflightVerification',
                timeout=90, log=folder/('preflight-'+action.lower()+'.log'))
            assert all(secret not in out for secret in [password, merchant, student])
            trace = [line for line in out.splitlines() if line.startswith('DIAG ')]
            result.setdefault('traces', {})[action] = trace
            assert 'DIAG updatedProductionClassesLoaded=true' in trace
            assert any(line.startswith('DIAG action='+action+' outcome=OK ') for line in trace), 'Patched maintenance '+action+' failed'
            return trace

        phase('patched-cold-jvm-resume')
        probe('Resume')
        resumed = snapshot('after-patched-resume')
        assert resumed['sqlGate'].split('\t')[:2] == ['ASYNC', 'OPEN']
        assert resumed['counts'] == '1\n1\n0'
        phase('new-real-async-claim')
        new_name = 'fix_s_'+secrets.token_hex(5)
        api('POST', '/auth/register', {'username': new_name, 'password': password, 'nickname': '虚构修复验证同学'})
        new_student = api('POST', '/auth/login', {'username': new_name, 'password': password})['accessToken']
        received = api('POST', '/coupon-claims', {'campaignId': campaign}, new_student, 'patched-new-claim', expected=202)
        assert received['status']=='PENDING'
        claim_id = received['id']
        end = time.monotonic()+60
        while time.monotonic()<end:
            status = api('GET', '/coupon-claims/'+claim_id, token=new_student)
            if status['status']=='SUCCEEDED': break
            if status['status']=='FAILED': raise RuntimeError('New claim reached a failed terminal state')
            time.sleep(.5)
        else: raise RuntimeError('New claim did not finish within observation budget')
        epoch = 'coupon:'+deployment.env['DATA_NAMESPACE']+':{'+campaign+'}:g:'+resumed['sqlGate'].split('\t')[2]
        end = time.monotonic()+60
        while time.monotonic()<end:
            if redis('HMGET', epoch, '#total', '#free', '#held', '#spent').splitlines()==['3','1','0','2']: break
            time.sleep(.5)
        else: raise RuntimeError('New claim Redis settlement did not finish within observation budget')
        issued = snapshot('after-new-async-claim')
        assert issued['counts']=='2\n2\n0'
        assert original_coupon in sql('SELECT id FROM user_coupon WHERE campaign_id='+campaign+' ORDER BY id;').splitlines()
        assert issued['redisCounters'][:4] == ['3','1','0','2']
        assert issued['redisCounters'][4:] == resumed['redisCounters'][4:]
        result['resumeAndNewClaim'] = True
        phase('patched-cold-jvm-sync-and-sync-resume')
        sync_trace = probe('Sync')
        assert 'DIAG syncResumeAddedRedisReads=0' in sync_trace
        final = snapshot('after-sync-and-sync-resume')
        assert final['sqlGate'].split('\t')[:2] == ['SYNC', 'OPEN']
        assert final['counts']=='2\n2\n0'
        result['syncAndSyncResume'] = True
        phase('captured')
    except BaseException as failure:
        result['fixtureFailureType'] = type(failure).__name__
        try: snapshot('fixture-failure')
        except BaseException as read_failure: result['failureSnapshotType'] = type(read_failure).__name__
        raise
    finally:
        try:
            deployment.compose('logs', '--no-color', timeout=30, log=folder/'diagnostic-services.log')
        finally:
            try:
                deployment.destroy_test()
                result['cleanup'] = {'containers': 0, 'volumes': 0, 'generatedSecretsRemoved': True}
            finally:
                result['finishedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
                result['jarUnchanged'] = hashlib.sha256(jar.read_bytes()).hexdigest()==record['jarSha256']
                (output/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
                print(json.dumps({'result': str(output/'result.json'), 'resumeAndNewClaim': result['resumeAndNewClaim'],
                    'syncAndSyncResume': result['syncAndSyncResume'], 'cleanup': result.get('cleanup')}), flush=True)


if __name__ == '__main__':
    main()
