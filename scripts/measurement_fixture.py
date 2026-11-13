"""Host-side attestation for the V24 seed. Never accept a database name alone as ownership."""
import json
import re


def require(value, code):
    if not value:
        raise ValueError(code)


def resource_identity(info, manifest, kind):
    labels = info['Config']['Labels'] if kind == 'container' else info['Labels']
    for key, expected in [('campuslife.base.identity', manifest['identity']),
                          ('campuslife.base.kind', 'test'),
                          ('campuslife.base.mode', 'showcase'),
                          ('com.docker.compose.project', manifest['project'])]:
        require(labels.get(key) == expected, 'RESOURCE_IDENTITY')
    if kind == 'container':
        require(labels.get('com.docker.compose.service') == 'mysql', 'MYSQL_SERVICE')
        require(labels.get('campuslife.base.resources') == 'compact4g', 'MYSQL_PROFILE')
    else:
        require(labels.get('com.docker.compose.volume') == 'mysql-data', 'MYSQL_VOLUME')
    return labels


def attest(deployment, case_id, profile):
    require(re.fullmatch(r'case-[a-f0-9]{12,32}', case_id), 'CASE_ID')
    require(profile in ['FORMAL', 'PILOT'], 'PROFILE')
    m = deployment.manifest
    require(m['kind'] == 'test' and deployment.mode == 'showcase'
            and deployment.resource_profile == 'compact4g' and not m.get('restoreOf')
            and not m.get('demoMoney'), 'FRESH_MEASUREMENT_INSTANCE')
    config = deployment.validate()
    # Docker inspect and labels must agree before any SQL credential is supplied to the tool.
    from deploy_base import docker
    containers = [c for c in deployment.resources('container')
                  if c['Config']['Labels'].get('com.docker.compose.service') == 'mysql']
    require(len(containers) == 1, 'ONE_MYSQL_REQUIRED')
    mysql = containers[0]; resource_identity(mysql, m, 'container')
    require(mysql['State']['Running'] and mysql['State']['Health']['Status'] == 'healthy', 'MYSQL_NOT_READY')
    require(not mysql['HostConfig'].get('PortBindings'), 'PRIVATE_MYSQL_REQUIRED')
    expected_image = docker('image', 'inspect', config['services']['mysql']['image'], '--format', '{{.Id}}')
    require(mysql['Image'] == expected_image, 'MYSQL_IMAGE')
    mounts = [v for v in mysql['Mounts'] if v['Destination'] == '/var/lib/mysql']
    require(len(mounts) == 1 and mounts[0]['Type'] == 'volume', 'MYSQL_DATA_MOUNT')
    volume = json.loads(docker('volume', 'inspect', mounts[0]['Name']))[0]
    resource_identity(volume, m, 'volume')
    require(volume['Name'] == m['project']+'_mysql-data', 'MYSQL_VOLUME_NAME')
    core = m['project']+'_core'
    require(set(mysql['NetworkSettings']['Networks']) == {core}, 'MYSQL_NETWORK')
    network = json.loads(docker('network', 'inspect', core))[0]
    require(network['Internal'] and network['Labels'].get('campuslife.base.identity') == m['identity']
            and network['Labels'].get('campuslife.base.kind') == 'test'
            and network['Labels'].get('com.docker.compose.project') == m['project'], 'CORE_NETWORK')
    value = deployment.compose('exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$(cat /run/secrets/DB_PASSWORD)" exec mysql --protocol=TCP --host=127.0.0.1 '
        '--user=campuslife --database=campuslife_deploy --batch --skip-column-names',
        stdin='SELECT @@server_uuid,@@hostname,DATABASE();', timeout=15).split('\t')
    require(len(value) == 3 and value[2] == m['database'], 'DATABASE_IDENTITY')
    return {'schema': 1, 'caseId': case_id, 'instanceId': m['identity'], 'kind': 'test',
            'project': m['project'], 'database': m['database'], 'mysqlContainerId': mysql['Id'],
            'volumeName': volume['Name'], 'serverUuid': value[0], 'serverHostname': value[1], 'profile': profile}
