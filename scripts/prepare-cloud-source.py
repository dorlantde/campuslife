"""Create a fresh allowlisted source archive; never copies developer state or credentials."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parents[1]
FILES = ['pom.xml', '.dockerignore', '.mvn/maven.config',
         'scripts/deploy_base.py', 'scripts/deploy_storage.py', 'scripts/deploy_observability.py']
TREES = {'src/main': {'.java', '.properties', '.sql', '.lua'}, 'src/test': {'.java'},
         'deploy/base': {'.sh', '.yaml', '.conf'}}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def inputs():
    names = set(FILES)
    names.add('deploy/base/Dockerfile')
    for tree, extensions in TREES.items():
        for p in (ROOT/tree).rglob('*'):
            if p.is_symlink():
                raise ValueError('Symlink is not a source file: '+str(p))
            if p.is_file():
                name = p.relative_to(ROOT).as_posix()
                if p.suffix not in extensions and name != 'deploy/base/Dockerfile':
                    raise ValueError('Unexpected extension in source tree: '+name)
                names.add(name)
    for name in sorted(names):
        p = ROOT/name
        if not p.is_file() or p.is_symlink() or not p.resolve().is_relative_to(ROOT):
            raise ValueError('Unsafe or missing source: '+name)
        data = p.read_bytes()
        if b'-----BEGIN ' in data and b'PRIVATE KEY-----' in data:
            raise ValueError('Private key marker in allowlisted source: '+name)
        if p.suffix == '.sh' and b'\r' in data:
            raise ValueError('Shell source requires LF: '+name)
        yield name, data


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--task', choices=['M06.1-D.5', 'M06.1-D.6d', 'M06.1-D.6h'], default='M06.1-D.5')
    args = parser.parse_args()
    output = args.output_dir.resolve()
    parent = (ROOT/'.cache/cloud-build').resolve()
    if not output.is_relative_to(parent) or output == parent:
        raise ValueError('Use a new child of .cache/cloud-build')
    values = list(inputs())
    manifest = {'task': args.task, 'files': [
        {'path': name, 'bytes': len(data), 'sha256': sha(data)} for name, data in values],
        'sourceBytes': sum(len(data) for _, data in values), 'fileCount': len(values),
        'rules': {'files': FILES+['deploy/base/Dockerfile'],
                  'trees': {k: sorted(v) for k, v in TREES.items()}},
        'excludes': ['.local', '.cache', '.tools', '.vscode', '.git', 'frontend',
                     'target', '.mvn/vscode-settings.xml', 'credentials and business data']}
    content = (json.dumps(manifest, indent=2)+'\n').encode()
    output.mkdir(parents=True, exist_ok=False)
    archive = output/'campuslife-source.tar.gz'
    with tarfile.open(archive, 'w:gz') as tar:
        for name, data in [*values, ('source-manifest.json', content)]:
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = 0o644
            info.mtime = 0
            tar.addfile(info, io.BytesIO(data))
    with tarfile.open(archive) as tar:
        assert tar.getnames() == [name for name, _ in values]+['source-manifest.json']
        for name, data in values:
            assert sha(tar.extractfile(name).read()) == sha(data)
    (output/'source-manifest.json').write_bytes(content)
    receipt = {'archive': archive.name, 'archiveBytes': archive.stat().st_size,
               'archiveSha256': sha(archive.read_bytes()), 'manifestSha256': sha(content),
               'fileCount': len(values), 'sourceBytes': manifest['sourceBytes'],
               'passed': True, 'transferred': False}
    (output/'receipt.json').write_text(json.dumps(receipt, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(receipt))


if __name__ == '__main__':
    main()
