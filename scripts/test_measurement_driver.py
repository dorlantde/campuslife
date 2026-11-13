"""Compile/run controlled loopback-only driver checks. No Maven, Docker, ECS or business services."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid
import zipfile

ROOT=Path(__file__).resolve().parents[1]


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--fixture-expected',type=Path,required=True);args=parser.parse_args()
    os.umask(0o077)
    fixture=args.fixture_expected.resolve()
    if not fixture.is_relative_to(ROOT/'.cache') or fixture.is_symlink():raise ValueError('Use the recorded private B.2-A expected fixture')
    source=json.loads(fixture.read_text(encoding='utf-8-sig'))
    if source.get('profile')!='FORMAL' or source.get('counts',{}).get('service_order')!=11000:raise ValueError('FORMAL fixture expected')
    candidates=sorted((ROOT/'.tools/java21').glob('*/bin/javac.exe'),reverse=True)
    javac=str(candidates[0]) if candidates else shutil.which('javac')
    if not javac:raise RuntimeError('Java21 JDK required')
    java=str(Path(javac).with_name('java.exe' if os.name=='nt' else 'java'))
    area=ROOT/'.cache/measurement-driver-b2b'/('case-'+uuid.uuid4().hex[:16]);area.mkdir(parents=True,mode=0o700)
    for name in ['classes','lib','output','source']:(area/name).mkdir(mode=0o700)
    sources=sorted((ROOT/'src/measurement/java/campuslife/measurement').glob('Measurement*.java'))
    if len(sources)!=4:raise ValueError('Four explicit driver sources required')
    jar=ROOT/'target/campuslife-0.0.1-SNAPSHOT.jar'
    metadata={'task':'M06.2-B.2-B','case':area.name,'fixtureSha256':sha(fixture),'jarSha256':sha(jar),
              'sources':[{ 'path':str(p.relative_to(ROOT)).replace('\\','/'),'sha256':sha(p)} for p in sources],
              'jdkVersion':subprocess.check_output([javac,'-version'],stderr=subprocess.STDOUT).decode(errors='replace').strip(),
              'businessServicesStarted':False,'databaseConnected':False,'localNetworkOnly':True}
    for source_path in sources:shutil.copyfile(source_path,area/'source'/source_path.name)
    shutil.copyfile(fixture,area/'fixture-expected.json')
    with zipfile.ZipFile(jar) as z:
        names=[n for n in z.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')]
        if len(names)!=156:raise ValueError('Expected original156 dependencies')
        for name in names:(area/'lib'/Path(name).name).write_bytes(z.read(name))
    started=time.monotonic()
    with (area/'compile.log').open('wb') as log:
        code=subprocess.run([javac,'-encoding','UTF-8','-cp',str(area/'lib/*'),'-d',str(area/'classes'),
            *map(str,sorted((area/'source').glob('*.java')))],stdout=log,stderr=subprocess.STDOUT,timeout=45).returncode
    metadata['compileExitCode']=code;metadata['dependencyJars']=len(names)
    if code==0:
        with (area/'checks.log').open('wb') as log:
            proc=subprocess.Popen([java,'-XX:ActiveProcessorCount=2','-Xms32m','-Xmx128m','--add-modules','jdk.httpserver',
                '-cp',str(area/'classes')+os.pathsep+str(area/'lib/*'),'campuslife.measurement.MeasurementDriverChecks',
                str(area/'fixture-expected.json'),str(area/'output')],stdout=log,stderr=subprocess.STDOUT)
            try:code=proc.wait(timeout=150)
            except subprocess.TimeoutExpired:
                proc.kill();proc.wait(timeout=5);code=-9
            metadata['processExited']=proc.poll() is not None
    metadata['exitCode']=code;metadata['elapsedSeconds']=round(time.monotonic()-started,3)
    result_path=area/'output/result.json'
    if result_path.exists():metadata['result']=json.loads(result_path.read_text(encoding='utf-8-sig'))
    (area/'run.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8')
    (ROOT/'.cache/measurement-driver-b2b/current.json').write_text(json.dumps({'folder':str(area)}),encoding='utf-8')
    result=metadata.get('result',{})
    print(json.dumps({'case':area.name,'exitCode':code,'passed':result.get('passed',False),'checks':len(result.get('checks',[])),
        'failureCode':result.get('failureCode'),'elapsedSeconds':metadata['elapsedSeconds'],'evidence':str(area)}))
    return 0 if code==0 and result.get('passed') else 1


if __name__=='__main__':sys.exit(main())
