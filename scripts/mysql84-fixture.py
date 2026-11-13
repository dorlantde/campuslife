"""Owned MySQL 8.4 compatibility runner. Secrets are random, local and never logged."""
import base64
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
IMAGE = 'mysql:8.4.13@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902'
CASES = [
    'Mysql84CompatibilityIT', 'MysqlIntegrationIT',
    'BookingOrdersMysqlIT#twentyStudentsCompetingForAnIntervalOnlyCreateOneOrder+concurrentSameKeyConvergesOrRejectsChangedContent+actualMysqlLockTimeoutReturns503AndDoesNotConsumeTheCreationKey+databaseConstraintsRejectInvalidMoneyIntervalsAndDuplicateEvents',
    'BookingPaymentsMysqlIT#successfulPaymentReplayAndRefundPreserveOriginalFactsAndRelease+paymentWriteFailuresRollbackEverything+refundWriteFailuresRollbackEverythingAndScannerRetries+concurrentRepeatedPaymentsProduceOneSuccessfulFact+concurrentPayCancelAndExpiryConvergeWithoutPartialFacts+detailUsesOneRepeatableReadSnapshotWhileRefundCommits',
    'CouponOrdersMysqlIT#quoteCreateReleaseReusePaymentAndRefund+snapshotAndConsumeFailureRollbackAllFacts+sameCouponTwoCreatesHaveExactlyOneWinner',
    'CouponClaimsMysqlIT#distinctStudentsCompeteForExactStockWithoutOverselling+sameStudentDifferentKeysAndSameKeyHaveOneEffect',
    'CouponAsyncMysqlIT#concurrentIssuanceAndDuplicateDeliveriesCannotOversell+timeoutAndConsumerCompeteForOneTerminal+pendingAndOutboxRollbackTogetherIfAdmissionWriterFails',
    'OutboxMysqlIT#outboxSqlFailureRollsBackOrderStageAndAuditEvent+leaseExpiryReclaimsSameIdentityAndRejectsLateCallback+skipLockedAllowsAnotherWorkerToClaimDifferentRow',
    'PublicShopCatalogMysqlIT#categoryFirstPagePreservesVisibilityOrderAndBlankKeywordNormalization+otherListShapesKeepTheOriginalPaginationPath+searchTreatsWildcardsAndSpecialCharactersAsLiteralNameContent+concurrentVisibilityHoursAndServiceWritesDoNotTearReadSnapshots',
    'PublicShopReviewsMysqlIT#exactVisibleSumDividedByCountRoundsHalfUp+reviewPageKeepsQualificationCountRowsAndNicknameInOneRealSnapshot+catalogStatsAndQualificationUseTheSameRealSnapshot',
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--phase', choices=['All', 'Startup'], default='All')
    phase = parser.parse_args().phase
    cases = CASES if phase == 'All' else CASES[:2]
    identity = uuid.uuid4().hex
    name = 'campuslife-mysql84-test-' + identity
    folder = ROOT / '.cache' / 'mysql84-it' / identity
    folder.mkdir(parents=True)
    secret_dir = folder / 'secrets'
    secret_dir.mkdir()
    began = time.time()
    root_pw, app_pw = secrets.token_hex(32), secrets.token_hex(32)
    (secret_dir / 'root-password').write_text(root_pw, encoding='ascii')
    (secret_dir / 'client.cnf').write_text('[client]\nuser=root\npassword=' + root_pw + '\n', encoding='ascii')
    (secret_dir / 'init.sql').write_text(
        "CREATE DATABASE campuslife_it CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n"
        "CREATE USER 'campuslife'@'%' IDENTIFIED WITH caching_sha2_password BY '" + app_pw + "';\n"
        "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON campuslife_it.* TO 'campuslife'@'%';\n", encoding='ascii')
    evidence = {'task': 'M06.1-A0', 'identity': identity, 'image': IMAGE,
                'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'phase': phase, 'selectedCases': cases}
    owned = set()

    def command(args, *, timeout=60, stdin=None):
        result = subprocess.run(args, cwd=ROOT, input=stdin, capture_output=True, text=True,
                                encoding='utf-8', errors='replace', timeout=timeout)
        if result.returncode:
            raise RuntimeError('Command failed: ' + args[0] + ' ' + args[1] + '\n' + result.stderr[-2500:])
        return result.stdout.strip()

    def docker(*args, **kwargs):
        return command(['docker', *args], **kwargs)

    def sql(statement):
        # Windows bind mounts can appear world-writable to MySQL, which then ignores option files.
        # Copy only inside the disposable container and enforce Linux 0600; no password in argv.
        return docker('exec', '-i', name, 'sh', '-c',
                      'umask 077; cp /run/campuslife/client.cnf /tmp/campuslife-client.cnf && '
                      'chmod 600 /tmp/campuslife-client.cnf && exec mysql '
                      '--defaults-extra-file=/tmp/campuslife-client.cnf --batch '
                      '--protocol=TCP --host=127.0.0.1 '
                      '--skip-column-names --default-character-set=utf8mb4', stdin=statement)

    def ready():
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                if sql('SELECT COUNT(*) FROM mysql.user WHERE User=\'campuslife\';') == '1':
                    return
            except (RuntimeError, subprocess.TimeoutExpired):
                pass
            time.sleep(0.5)
        raise RuntimeError('Owned MySQL failed readiness within 120 seconds')

    def assert_owned(kind):
        raw = docker('inspect', name) if kind == 'container' else docker(kind, 'inspect', name)
        info = json.loads(raw)[0]
        labels = info['Config']['Labels'] if kind == 'container' else info['Labels']
        if labels.get('campuslife.mysql84.identity') != identity or labels.get('campuslife.mysql84.kind') != 'test':
            raise RuntimeError('Refusing to operate on a resource without matching ownership')
        return info

    def maven(arguments, logfile):
        executable = shutil.which('mvn.cmd') or shutil.which('mvn')
        if not executable:
            raise RuntimeError('Maven is required')
        # Maven wrappers may consume MAVEN_ARGS; inherited user settings must not select other tests.
        with (folder / logfile).open('w', encoding='utf-8') as log:
            result = subprocess.run([executable, '-B', *arguments], cwd=ROOT, env=env,
                                    stdout=log, stderr=subprocess.STDOUT, timeout=900)
        if result.returncode:
            raise RuntimeError('Maven failed; retained evidence: ' + str(folder / logfile))

    try:
        evidence['engine'] = docker('version', '--format', '{{.Server.Version}}')
        docker('pull', IMAGE, timeout=240)
        evidence['imageInfo'] = json.loads(docker('image', 'inspect', IMAGE, '--format', '{{json .}}'))
        # Retain only non-secret image identity fields.
        image_info = evidence['imageInfo']
        evidence['imageInfo'] = {key: image_info.get(key) for key in ['Id', 'RepoDigests', 'Os', 'Architecture']}
        for kind in ['network', 'volume']:
            docker(kind, 'create', '--label', 'campuslife.mysql84.identity=' + identity,
                   '--label', 'campuslife.mysql84.kind=test', name)
            owned.add(kind)
        docker('create', '--name', name, '--network', name,
               '--label', 'campuslife.mysql84.identity=' + identity, '--label', 'campuslife.mysql84.kind=test',
               '--memory', '1536m', '--cpus', '2', '-p', '127.0.0.1::3306',
               '--mount', 'type=volume,source=' + name + ',target=/var/lib/mysql',
               '--mount', 'type=bind,source=' + str(secret_dir) + ',target=/run/campuslife,readonly',
               '--mount', 'type=bind,source=' + str(secret_dir / 'init.sql') + ',target=/docker-entrypoint-initdb.d/01-campuslife.sql,readonly',
               '-e', 'MYSQL_ROOT_PASSWORD_FILE=/run/campuslife/root-password', IMAGE,
               '--mysqlx=OFF', '--skip-log-bin', '--default-time-zone=+00:00', '--bind-address=0.0.0.0')
        owned.add('container')
        docker('start', name)
        info = assert_owned('container')
        ports = json.loads(docker('inspect', '--format', '{{json .NetworkSettings.Ports}}', name))
        bindings = ports['3306/tcp']
        if len(bindings) != 1 or bindings[0]['HostIp'] != '127.0.0.1' or any(v for k, v in ports.items() if k != '3306/tcp'):
            raise RuntimeError('Unexpected external port exposure')
        port = bindings[0]['HostPort']
        ready()
        evidence['database'] = sql("SELECT VERSION(),@@global.restrict_fk_on_non_standard_key,@@global.foreign_key_checks,@@global.time_zone; SELECT User,Host,plugin FROM mysql.user WHERE User='campuslife';")
        print('Owned MySQL 8.4.13 ready on loopback port ' + port, flush=True)
        env = dict(os.environ)
        env.pop('MAVEN_ARGS', None)
        for key in list(env):
            if key.startswith('CAMPUSLIFE_') or key.startswith('SPRING_'):
                env.pop(key)
        env.update(DB_HOST='127.0.0.1', DB_PORT=port, DB_NAME='campuslife_it', DB_USERNAME='campuslife', DB_PASSWORD=app_pw,
                   JWT_SECRET_BASE64=base64.b64encode(secrets.token_bytes(64)).decode(),
                   SPRING_PROFILES_ACTIVE='mysql', SERVER_ADDRESS='127.0.0.1',
                   SPRING_CONFIG_IMPORT='optional:classpath:/__campuslife_isolated_fixture__.properties',
                   FILE_STORAGE_ROOT=str(folder / 'files'), FILE_TEST_STORAGE_ROOT=str(folder / 'files'),
                   FILE_CLEANUP_ENABLED='false', FILE_S3_ENABLED='false', FILE_UPLOAD_TARGET='LOCAL',
                   MESSAGING_ENABLED='false', MESSAGING_SCHEDULING_ENABLED='false', COUPON_REDIS_ENABLED='false',
                   COUPON_REDIS_SCHEDULING_ENABLED='false', RATING_CACHE_ENABLED='false',
                   CAMPUSLIFE_MYSQL84_IT='owned-isolated-fixture')
        print('Compiling existing application and compatibility tests (no business changes).', flush=True)
        maven(['-Pmysql-check', '-DskipTests', 'package'], 'build.log')
        print('Running selected migration, real-lock, money, idempotency and read-query checks.', flush=True)
        maven(['-Pmysql-check', '-Dit.test=' + ','.join(cases), 'failsafe:integration-test', 'failsafe:verify'], 'checks.log')
        suites = []
        for case in cases:
            matches = list((ROOT / 'target/failsafe-reports').glob('TEST-*.' + case.split('#')[0] + '.xml'))
            if len(matches) != 1 or matches[0].stat().st_mtime < began:
                raise RuntimeError('Missing fresh test evidence: ' + case)
            suite = ET.parse(matches[0]).getroot()
            row = {k: int(suite.attrib.get(k, 0)) for k in ['tests', 'failures', 'errors', 'skipped']}
            row['name'] = suite.attrib['name']
            if row['tests'] < 1 or any(row[k] for k in ['failures', 'errors', 'skipped']):
                raise RuntimeError('Selected tests did not all pass: ' + case)
            suites.append(row)
            # Copy only the per-suite textual summaries, not environment/credentials from XML.
        evidence['suites'] = suites
        evidence['schema'] = sql("SELECT COUNT(*) FROM campuslife_it.flyway_schema_history WHERE success=1; SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='campuslife_it' AND table_name<>'flyway_schema_history';")
        sql("CREATE TABLE campuslife_it.deploy84_probe(id INT PRIMARY KEY,value_text VARCHAR(80)) ENGINE=InnoDB; INSERT INTO campuslife_it.deploy84_probe VALUES(1,'restart-persisted');")
        before = sql("SELECT value_text FROM campuslife_it.deploy84_probe;")
        docker('restart', '--time', '30', name, timeout=60)
        ready()
        # A dynamically published Docker port may be reallocated on restart.
        # Rediscover and verify the same owned container's effective loopback binding.
        after_ports = json.loads(docker('inspect', '--format', '{{json .NetworkSettings.Ports}}', name))
        after_bindings = after_ports['3306/tcp']
        if len(after_bindings) != 1 or after_bindings[0]['HostIp'] != '127.0.0.1' or any(v for k, v in after_ports.items() if k != '3306/tcp'):
            raise RuntimeError('Restart changed the owned loopback exposure boundary')
        evidence['publishedPortBeforeRestart'] = port
        evidence['publishedPortAfterRestart'] = after_bindings[0]['HostPort']
        env['DB_PORT'] = after_bindings[0]['HostPort']
        after = sql("SELECT value_text FROM campuslife_it.deploy84_probe;")
        if before != 'restart-persisted' or before != after:
            raise RuntimeError('Owned volume restart persistence failed')
        sql('DROP TABLE campuslife_it.deploy84_probe;')
        evidence['restartPersistence'] = True
        print('Checking packaged application against the restarted owned database.', flush=True)
        with (folder / 'smoke.log').open('w', encoding='utf-8') as log:
            smoke = subprocess.run(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                                    str(ROOT / 'scripts/smoke.ps1'), '-Profile', 'mysql', '-StartupTimeoutSeconds', '90'],
                                   cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=150)
        for stream in ['stdout', 'stderr']:
            smoke_detail = ROOT / '.cache' / ('smoke.' + stream + '.log')
            if smoke_detail.exists():
                shutil.copyfile(smoke_detail, folder / smoke_detail.name)
        if smoke.returncode:
            raise RuntimeError('Packaged startup check failed; inspect retained smoke log')
        evidence['jarSmoke'] = True
        evidence['passed'] = True
        print('PASS: ' + str(sum(x['tests'] for x in suites)) + ' selected checks, restart and JAR startup.', flush=True)
    except BaseException as error:
        evidence['passed'] = False
        evidence['failure'] = str(error)
        raise
    finally:
        observed = []
        for case in cases:
            for report in (ROOT / 'target/failsafe-reports').glob('TEST-*.' + case.split('#')[0] + '.xml'):
                if report.stat().st_mtime >= began:
                    suite = ET.parse(report).getroot()
                    observed.append({'name': suite.attrib['name'], **{k: int(suite.attrib.get(k, 0)) for k in ['tests', 'failures', 'errors', 'skipped']}})
                    text_report = report.with_name(suite.attrib['name'] + '.txt')
                    if text_report.exists():
                        shutil.copyfile(text_report, folder / text_report.name)
        evidence['observedSuites'] = observed
        cleanup_errors = []
        if 'container' in owned:
            try:
                assert_owned('container')
                server_log = subprocess.run(['docker', 'logs', name], cwd=ROOT, capture_output=True,
                                            text=True, encoding='utf-8', errors='replace', timeout=30)
                (folder / 'server.log').write_text(server_log.stdout + server_log.stderr, encoding='utf-8')
                docker('rm', '-f', name)
                owned.remove('container')
            except Exception as error:
                cleanup_errors.append(str(error))
        for kind in ['volume', 'network']:
            if kind in owned:
                try:
                    assert_owned(kind)
                    docker(kind, 'rm', name)
                    owned.remove(kind)
                except Exception as error:
                    cleanup_errors.append(str(error))
        # The directory is this run's generated fixture, with no user files or recursive deletion.
        if not owned:
            for filename in ['root-password', 'client.cnf', 'init.sql']:
                (secret_dir / filename).unlink(missing_ok=True)
            secret_dir.rmdir()
        evidence['cleanupErrors'] = cleanup_errors
        evidence['remainingOwnedResources'] = sorted(owned)
        evidence['finishedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
        (folder / 'result.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
        print('Evidence: ' + str(folder / 'result.json'), flush=True)
        if owned or cleanup_errors:
            raise RuntimeError('Owned resource cleanup incomplete; see evidence')


if __name__ == '__main__':
    main()
