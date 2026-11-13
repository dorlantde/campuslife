"""Transfer only source-pinned public base images through SSH; never uploads workspace data."""
import argparse
import hashlib
import ipaddress
import json
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() if hasattr(hashlib, 'file_digest') else stream_hash(stream)


def stream_hash(stream):
    h = hashlib.sha256()
    for data in iter(lambda: stream.read(1024**2), b''):
        h.update(data)
    return h.hexdigest()


def pinned_images():
    refs = set()
    for name in ['compose.yaml', 'components.yaml', 'storage.yaml']:
        refs.update(re.findall(r'^\s+image:\s*([^\s$]+@sha256:[a-f0-9]{64})\s*$',
                              (ROOT/'deploy/base'/name).read_text(), re.M))
    refs.update(re.findall(r'^FROM (\S+@sha256:[a-f0-9]{64})$', (ROOT/'deploy/base/Dockerfile').read_text(), re.M))
    if len(refs) != 6:
        raise RuntimeError('Expected exactly six source-pinned public base images')
    return sorted(refs, key=lambda r: (not r.startswith('redis:'), r))


def canonical(ref):
    name, digest = ref.split('@')
    name = name.removeprefix('docker.io/').removeprefix('library/').split(':')[0]
    return name+'@'+digest


def metadata(raw):
    return {k: raw[k] for k in ['Id', 'RepoDigests', 'Os', 'Architecture', 'RootFS']}


def verify(expected, actual, ref):
    if (actual['Id'] != expected['Id'] or actual['RootFS'] != expected['RootFS']
            or actual['Os'] != 'linux' or actual['Architecture'] != 'amd64'
            or canonical(ref) not in [canonical(x) for x in actual['RepoDigests']]):
        raise RuntimeError('Imported image identity/platform/layers differ: '+ref)


def export_archive(ref, folder, report):
    tag = ref.split('@')[0]
    source = metadata(json.loads(subprocess.check_output(['docker', 'image', 'inspect', ref]))[0])
    verify(source, source, ref)
    probe = subprocess.run(['docker', 'image', 'inspect', tag], capture_output=True)
    if probe.returncode:
        subprocess.run(['docker', 'image', 'tag', ref, tag], check=True)
        # Keep same-version names; removing a final tag may remove its digest alias too.
        report['addedLocalVersionTags'].append(tag)
    elif json.loads(probe.stdout)[0]['Id'] != source['Id']:
        raise RuntimeError('Existing version tag points elsewhere; refusing overwrite')
    archive = folder/(tag.split(':')[0].replace('/', '-')+'.tar')
    if archive.exists():
        raise RuntimeError('Refusing to overwrite existing archive: '+str(archive))
    subprocess.run(['docker', 'image', 'save', '-o', str(archive), tag], check=True, timeout=180)
    with tarfile.open(archive) as members:
        index = json.load(members.extractfile('index.json'))
        if ref.split('@')[1] not in [m['digest'] for m in index['manifests']]:
            raise RuntimeError('Original descriptor is not preserved in the OCI archive')
        count = 0
        for member in members:
            if re.fullmatch(r'blobs/sha256/[a-f0-9]{64}', member.name):
                if stream_hash(members.extractfile(member)) != member.name.split('/')[-1]:
                    raise RuntimeError('Exported OCI blob checksum differs')
                count += 1
    return {'ref': ref, 'source': source, 'archive': archive.name,
            'archiveSha256': sha(archive), 'archiveBytes': archive.stat().st_size,
            'verifiedBlobs': count}


def export_remaining(folder):
    folder = folder.resolve()
    cache = (ROOT/'.cache/cloud-images').resolve()
    if not folder.is_relative_to(cache) or folder == cache:
        raise RuntimeError('Export directory must be a new child of .cache/cloud-images')
    folder.mkdir(parents=True, exist_ok=False)
    report = {'task': 'M06.1-D.4', 'mode': 'local export for user-side SCP',
              'images': [], 'addedLocalVersionTags': [], 'passed': False,
              'applicationImageTransferred': False, 'workspaceOrSecretsTransferred': False,
              'remoteOperationsPerformed': False}
    try:
        for ref in pinned_images():
            if ref.startswith('redis:'):
                continue
            print('Exporting '+ref, flush=True)
            result = export_archive(ref, folder, report)
            report['images'].append(result)
            print('Verified '+result['archive']+': '+str(result['archiveBytes'])+' bytes', flush=True)
        if len(report['images']) != 5:
            raise RuntimeError('Expected five remaining public base images')
        report['passed'] = True
    except BaseException as error:
        report['failure'] = str(error)
        raise
    finally:
        (folder/'manifest.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
        print('Manifest: '+str(folder/'manifest.json'), flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--host')
    p.add_argument('--identity-file')
    p.add_argument('--known-hosts')
    p.add_argument('--only', choices=['redis'])
    p.add_argument('--export-remaining', action='store_true', help='Only export five non-Redis public images locally')
    p.add_argument('--output-dir', type=Path)
    args = p.parse_args()
    if args.export_remaining:
        if not args.output_dir or args.host or args.identity_file or args.known_hosts or args.only:
            p.error('--export-remaining requires --output-dir and no SSH/--only arguments')
        export_remaining(args.output_dir)
        return
    if args.output_dir or not all([args.host, args.identity_file, args.known_hosts]):
        p.error('Automatic transfer requires --host, --identity-file and --known-hosts')
    ipaddress.ip_address(args.host)
    task = uuid.uuid4().hex
    folder = ROOT/'.cache/cloud-images'/task
    folder.mkdir(parents=True)
    base = ['ssh', '-T', '-i', args.identity_file, '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
            '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile='+str(Path(args.known_hosts).resolve()),
            '-o', 'ConnectTimeout=10', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3', 'campusdeploy@'+args.host]

    def remote(command, *, stdin=None, timeout=120):
        out = subprocess.run(base+[command], stdin=stdin, capture_output=True, timeout=timeout)
        if out.returncode:
            raise RuntimeError('SSH operation failed: '+out.stderr.decode(errors='replace')[-1200:])
        return out.stdout.decode(errors='replace').strip()

    stage = '/home/campusdeploy/.cache/campuslife-images/'+task
    remote('umask 077; mkdir -p /home/campusdeploy/.cache/campuslife-images; mkdir '+shlex.quote(stage))
    report = {'task': task, 'remoteStage': stage, 'images': [], 'applicationImageTransferred': False,
              'workspaceOrSecretsTransferred': False, 'addedLocalVersionTags': []}
    try:
        for ref in pinned_images():
            if args.only and not ref.startswith(args.only+':'):
                continue
            tag = ref.split('@')[0]
            print('Checking '+ref, flush=True)
            source = metadata(json.loads(subprocess.check_output(['docker', 'image', 'inspect', ref]))[0])
            verify(source, source, ref)
            existing = json.loads(remote('docker image inspect '+shlex.quote(ref)+' 2>/dev/null || true') or '[]')
            if existing:
                verify(source, metadata(existing[0]), ref)
                report['images'].append({'ref': ref, 'source': source, 'alreadyPresentAndVerified': True})
                continue
            probe = subprocess.run(['docker', 'image', 'inspect', tag], capture_output=True)
            if probe.returncode:
                subprocess.run(['docker', 'image', 'tag', ref, tag], check=True)
                # Keep these same-version names: removing the final tag may also remove the canonical digest alias.
                report['addedLocalVersionTags'].append(tag)
            elif json.loads(probe.stdout)[0]['Id'] != source['Id']:
                raise RuntimeError('Existing version tag points elsewhere; refusing overwrite')
            archive = folder/(tag.split(':')[0].replace('/', '-')+'.tar')
            subprocess.run(['docker', 'image', 'save', '-o', str(archive), tag], check=True, timeout=180)
            with tarfile.open(archive) as members:
                index = json.load(members.extractfile('index.json'))
                if ref.split('@')[1] not in [m['digest'] for m in index['manifests']]:
                    raise RuntimeError('Original descriptor is not preserved in the OCI archive')
                for member in members:
                    if re.fullmatch(r'blobs/sha256/[a-f0-9]{64}', member.name):
                        if stream_hash(members.extractfile(member)) != member.name.split('/')[-1]:
                            raise RuntimeError('Exported OCI blob checksum differs')
            digest = sha(archive)
            target = stage+'/'+archive.name
            started = time.monotonic()
            print('Uploading '+str(archive.stat().st_size)+' bytes; archive SHA256='+digest, flush=True)
            remote('test ! -e '+shlex.quote(target+'.partial'))
            # Use SFTP's bounded parallel file requests, not Win32 ssh stdin streaming.
            copied = subprocess.run(['scp', '-C', *base[2:-1], archive.resolve().as_posix(),
                                     'campusdeploy@'+args.host+':'+target+'.partial'],
                                    capture_output=True, timeout=180)
            if copied.returncode:
                raise RuntimeError('SFTP copy failed: '+copied.stderr.decode(errors='replace')[-1200:])
            elapsed = round(time.monotonic()-started, 3)
            receipt = remote('sha256sum '+shlex.quote(target+'.partial'))
            if receipt.split()[0] != digest:
                raise RuntimeError('Transport checksum differs; no image loaded')
            remote('test ! -e '+shlex.quote(target)+' && mv '+shlex.quote(target+'.partial')+' '+shlex.quote(target))
            print(remote('docker image load --input '+shlex.quote(target), timeout=240), flush=True)
            imported = metadata(json.loads(remote('docker image inspect '+shlex.quote(ref)))[0])
            verify(source, imported, ref)
            report['images'].append({'ref': ref, 'source': source, 'target': imported,
                'archiveSha256': digest, 'archiveBytes': archive.stat().st_size, 'uploadSeconds': elapsed})
            print('Verified original digest/platform/layers: '+tag, flush=True)
        report['passed'] = True
    except BaseException as error:
        report.update(passed=False, failure=str(error))
        raise
    finally:
        (folder/'result.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        print('Evidence: '+str(folder/'result.json'), flush=True)


if __name__ == '__main__':
    main()
