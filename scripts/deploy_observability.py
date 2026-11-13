"""On-demand, bounded operations diagnostics; never a public application endpoint."""
import datetime as dt
import json
import re
import subprocess


def management_request(deployment, path, *, method='GET', authorization=None):
    if not re.fullmatch(r'/actuator/(health(?:/liveness|/readiness)?|metrics(?:/[a-zA-Z0-9_.-]{1,120})?)', path):
        raise ValueError('Only fixed health/metric paths are supported')
    password = (deployment.folder / 'secrets/MANAGEMENT_PASSWORD').read_text()
    config = ('silent\nshow-error\nmax-time = 15\nurl = "http://127.0.0.1:8081' + path
              + '"\nrequest = "' + method + '"\nwrite-out = "\\n%{http_code}"\n')
    if authorization is None:
        config += 'user = "campuslife_ops:' + password + '"\n'
    elif authorization:
        config += 'header = "Authorization: ' + authorization + '"\n'
    raw = deployment.compose('exec', '-T', 'app', 'curl', '--config', '-', stdin=config, timeout=25)
    body, status = raw.rsplit('\n', 1) if '\n' in raw else ('', raw)
    return int(status), json.loads(body) if body else None


def database_snapshot(deployment):
    # One short read-only RR snapshot. Fixed aggregate queries; no payload, identity, file path or content.
    sql = """SET SESSION MAX_EXECUTION_TIME=1500;
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SELECT JSON_OBJECT('section','outbox','state',status,'count',COUNT(*),
 'oldestAgeSeconds',IF(status IN ('READY','PUBLISHING','DEAD'),GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(created_at),UTC_TIMESTAMP(3))),NULL))
 FROM message_outbox GROUP BY status;
SELECT JSON_OBJECT('section','claims','state',status,'count',COUNT(*)) FROM coupon_claim_request GROUP BY status;
SELECT JSON_OBJECT('section','gates','mode',mode,'phase',phase,'count',COUNT(*)) FROM coupon_dispatch_gate GROUP BY mode,phase;
SELECT JSON_OBJECT('section','files','backend',storage_kind,'state',status,'count',COUNT(*)) FROM file_asset GROUP BY storage_kind,status;
COMMIT;
"""
    raw = deployment.compose('exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$(cat /run/secrets/DB_PASSWORD)" exec mysql --protocol=TCP --host=127.0.0.1 '
        '--connect-timeout=2 --user=campuslife --database=campuslife_deploy --batch --skip-column-names', stdin=sql, timeout=15)
    return [json.loads(line) for line in raw.splitlines() if line.strip()]


def observe(deployment, operation, metric=None):
    deployment.validate()
    if not deployment.manifest.get('observability', False):
        raise ValueError('This instance did not explicitly enable private observation')
    if operation not in ['Health', 'Metrics', 'Snapshot']:
        raise ValueError('Choose Health, Metrics or Snapshot')
    if metric and (operation != 'Metrics' or not re.fullmatch('[a-zA-Z0-9_.-]{1,120}', metric)):
        raise ValueError('Use an exact metric name without URL parameters')
    if operation in ['Health', 'Metrics']:
        path = '/actuator/health' if operation == 'Health' else '/actuator/metrics' + ('/' + metric if metric else '')
        status, body = management_request(deployment, path)
        return {'httpStatus': status, 'data': body}
    result = {'observedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'containers': deployment.status()['services']}
    for name in ['liveness', 'readiness']:
        try:
            status, body = management_request(deployment, '/actuator/health/' + name)
            result[name] = {'httpStatus': status, 'data': body}
        except (RuntimeError, OSError, subprocess.TimeoutExpired):
            result[name] = {'available': False}
    try:
        result['database'] = {'available': True, 'aggregates': database_snapshot(deployment)}
    except (RuntimeError, OSError, subprocess.TimeoutExpired):
        result['database'] = {'available': False}
    if deployment.mode != 'base':
        try:
            result['queues'] = deployment.compose('exec', '-T', 'rabbitmq', 'gosu', 'rabbitmq', 'rabbitmqctl', '-q', 'list_queues',
                '-p', deployment.env['RABBIT_VHOST'], 'name', 'messages_ready', 'messages_unacknowledged', timeout=15).splitlines()
        except (RuntimeError, OSError, subprocess.TimeoutExpired):
            result['queues'] = {'available': False}
    return result
