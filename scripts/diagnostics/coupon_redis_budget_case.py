"""ECS-only, no public ports/volumes/SQL: compare isolated Lettuce initialization and command expiry."""
import argparse,hashlib,json,os,secrets,subprocess,time,zipfile
from pathlib import Path

REDIS='redis:8.2.9@sha256:66b7f4c2f3c6ef1b298bbd7b13c806bc6f71e61f1136a4f58710daf2cb053b21'
APP='sha256:8d654a790e39c6dbd7d1910b83458a3b5a1318aa107517ab8d69d86283fbf367'
JAR_SHA='4dfd3a9f1efdd8f344417ac5c1ebe989a1dfdd1dd689499f4c7978017e9cef00'

def main():
    p=argparse.ArgumentParser();p.add_argument('--release',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True)
    p.add_argument('--modes',default='default-delay,candidate-delay,candidate-command,wrong-auth,unavailable');a=p.parse_args()
    modes=a.modes.split(',');assert modes and len(modes)==len(set(modes)) and set(modes)<=set(['default-delay','candidate-delay','candidate-command','wrong-auth','unavailable'])
    release=a.release.resolve();out=a.output_dir.resolve()
    assert out.is_relative_to(release/'.cache/diagnostics')
    os.umask(0o077);out.mkdir(parents=True,exist_ok=False)
    def run(*args,timeout=30):
        v=subprocess.run(args,capture_output=True,text=True,timeout=timeout)
        if v.returncode: raise RuntimeError('CONTROLLED_SUBPROCESS_FAILURE '+str(v.returncode))
        return v.stdout.strip()
    assert not run('docker','ps','-aq') and not run('docker','volume','ls','-q')
    jar=release/'target/campuslife-0.0.1-SNAPSHOT.jar';assert hashlib.sha256(jar.read_bytes()).hexdigest()==JAR_SHA
    lib=out/'lib';classes=out/'classes';private=out/'private'
    for d in [lib,classes,private]:d.mkdir();d.chmod(0o755)
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if n.startswith('BOOT-INF/lib/') and n.endswith('.jar'):(lib/Path(n).name).write_bytes(z.read(n))
    source=Path(__file__).with_name('CouponRedisBudgetProbe.java')
    compile_result=subprocess.run(['javac','-J-Xmx256m','--release','21','-cp',str(lib/'*'),'-d',str(classes),str(source)],capture_output=True,text=True,timeout=45)
    (out/'compile.log').write_text(compile_result.stdout+compile_result.stderr)
    assert compile_result.returncode==0,'PROBE_COMPILE_FAILED'
    for f in classes.rglob('*'):f.chmod(0o755 if f.is_dir() else 0o644)
    app=secrets.token_hex(24);ops=secrets.token_hex(24)
    for name,value in [('app-password',app),('ops-password',ops),('acl','user default off\nuser coupon_app on #'+hashlib.sha256(app.encode()).hexdigest()+' ~coupon:probe:* +@read +@write +@scripting +@connection +info -flushdb -flushall -config -shutdown -acl\nuser redis_ops on #'+hashlib.sha256(ops.encode()).hexdigest()+' ~* +@all\n')]:
        (private/name).write_text(value);(private/name).chmod(0o444)
    identity='budget-'+secrets.token_hex(6);network=identity;redis=identity+'-redis'
    (out/'active.json').write_text(json.dumps({'identity':identity,'network':network}))
    result={'task':'M06.1-D.6g','scope':'isolated-mechanism-only','productionModified':False,'checks':[], 'identity':identity}
    created=False;started=time.monotonic()
    try:
        run('docker','network','create','--internal','--label','campuslife.budget.identity='+identity,network);created=True
        run('docker','run','-d','--name',redis,'--network',network,'--network-alias','budget-redis','--label','campuslife.budget.identity='+identity,
            '--memory','192m','--memory-swap','192m','--cpuset-cpus','0,1','--tmpfs','/data:size=32m,mode=1777',
            '-v',str(private)+':/run/probe:ro',REDIS,'redis-server','--save','','--appendonly','no','--aclfile','/run/probe/acl')
        for _ in range(30):
            ready=run('docker','exec',redis,'sh','-c','REDISCLI_AUTH="$(cat /run/probe/app-password)" redis-cli --user coupon_app PING')
            if ready=='PONG':break
            time.sleep(.2)
        assert ready=='PONG'
        for mode in modes:
            print('PHASE '+mode,flush=True)
            value=subprocess.run(['docker','run','--rm','--name',identity+'-probe','--network',network,'--label','campuslife.budget.identity='+identity,
                '--memory','896m','--memory-swap','896m','--cpuset-cpus','0,1','--user','10001:10001',
                '-v',str(private)+':/run/probe:ro','-v',str(classes)+':/run/diagnostic:ro','--entrypoint','java',APP,
                '-Xms128m','-Xmx448m','-XX:ActiveProcessorCount=2','-Dloader.path=/run/diagnostic','-Dloader.main=diagnostic.CouponRedisBudgetProbe',
                '-cp','/opt/campuslife/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher',mode],capture_output=True,text=True,timeout=35)
            assert app not in value.stdout+value.stderr and ops not in value.stdout+value.stderr
            (out/(mode+'.log')).write_text(value.stdout+value.stderr)
            lines=[s for s in value.stdout.splitlines() if s.startswith('PROBE ')]
            result['checks'].append({'mode':mode,'exitCode':value.returncode,'trace':lines,'observations':[s for s in value.stdout.splitlines() if s.startswith('OBS ')]})
            assert value.returncode==0 and len(lines)==1,'MECHANISM_CHECK_FAILED '+mode
        result['passed']=True
    finally:
        for name in [identity+'-probe',redis]:
            found=run('docker','ps','-aq','--filter','name=^/'+name+'$')
            if found:
                label=run('docker','inspect','--format','{{index .Config.Labels "campuslife.budget.identity"}}',name);assert label==identity
                run('docker','rm','-f','-v',name)
        if created:run('docker','network','rm',network)
        for f in private.iterdir():f.chmod(0o600);f.unlink()
        private.rmdir()
        result.update(elapsedSeconds=round(time.monotonic()-started,3),jarUnchanged=hashlib.sha256(jar.read_bytes()).hexdigest()==JAR_SHA,
            containers=run('docker','ps','-aq').splitlines(),volumes=run('docker','volume','ls','-q').splitlines(),generatedSecretsRemoved=not private.exists())
        (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)

if __name__=='__main__':main()
