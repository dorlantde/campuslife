"""Controlled checks for C.3 health semantics, without cloud or service mutations."""
import datetime as dt
from pathlib import Path
import unittest
from measurement_runtime import HealthGate,SERVICES,group_path,start_ticks,probe_unix

def rows(probes=(),health='healthy',streak=0):
    return [dict(service=s,id=s,pid=10,startTicks=20,restartCount=0,oomKilled=False,oom=0,oomKill=0,running=True,
                 health=health if s=='rabbitmq' else 'healthy',failingStreak=streak if s=='rabbitmq' else 0,probes=list(probes) if s=='rabbitmq' else []) for s in SERVICES]
def probe(second,code=0):return {'end':dt.datetime.fromtimestamp(1000+second,dt.timezone.utc).isoformat(),'exitCode':code}

class RuntimeChecks(unittest.TestCase):
    def test_docker_nanoseconds_on_python310(self):
        self.assertEqual(probe_unix('2026-11-07T00:00:00.123456789Z'),probe_unix('2026-11-07T00:00:00.123456+00:00'))
    def test_baseline_exact_window_and_three_new_completed(self):
        g=HealthGate();r=rows([probe(45),probe(50),probe(55)]);g.ingest(r)
        self.assertFalse(g.baseline(r,1000,59.999));self.assertTrue(g.baseline(r,1000,60));self.assertFalse(g.baseline(r,1060,60))
    def test_same_probe_is_not_two_failures(self):
        g=HealthGate();r=rows([probe(10,1)])
        self.assertEqual(g.ingest(r),'');self.assertEqual(g.ingest(r),'');self.assertEqual(len(g.probes),1)
        self.assertEqual(g.ingest(rows([probe(10,1),probe(15,1)])),'BROKER_PROBE_FAILED')
    def test_unhealthy_and_missing_service_stop(self):
        self.assertEqual(HealthGate().ingest(rows(health='unhealthy')),'BROKER_PROBE_FAILED')
        self.assertEqual(HealthGate().ingest(rows()[:-1]),'SERVICE_SET_CHANGED')
        self.assertEqual(HealthGate().ingest(rows()+rows()[:1]),'SERVICE_SET_CHANGED')
    def test_no_recovery_after_stop(self):
        g=HealthGate();g.ingest(rows(health='unhealthy'));g.ingest(rows([probe(10),probe(15),probe(20)]))
        self.assertFalse(g.baseline(rows(),1000,60))
    def test_identity_or_restart_changed(self):
        for field,value in [('pid',11),('id','replacement'),('startTicks',21),('restartCount',1)]:
            g=HealthGate();r=rows();g.ingest(r);r[0][field]=value;self.assertEqual(g.ingest(r),'SERVICE_IDENTITY_CHANGED')
    def test_oom_and_exit_stop(self):
        for field,value,code in [('oomKilled',True,'CONTAINER_OOM'),('oom',1,'CONTAINER_OOM'),('oomKill',1,'CONTAINER_OOM'),('running',False,'CONTAINER_STOPPED')]:
            r=rows();r[0][field]=value;self.assertEqual(HealthGate().ingest(r),code)
    def test_same_end_mutation_refused(self):
        g=HealthGate();g.ingest(rows([probe(10)]));self.assertEqual(g.ingest(rows([probe(10,1)])),'PROBE_IDENTITY_CHANGED')
    def test_final_streak_and_unhealthy_services_refused(self):
        g=HealthGate();r=rows([probe(45),probe(50),probe(55)],streak=1);g.ingest(r);self.assertFalse(g.baseline(r,1000,60))
        r=rows([probe(45),probe(50),probe(55)]);r[0]['health']='starting';self.assertFalse(g.baseline(r,1000,60))
    def test_proc_stat_parentheses(self):
        text='123 (a b) ) S '+' '.join(str(i) for i in range(1,25));self.assertEqual(start_ticks(text),19)
    def test_cgroup_scope(self):
        root=Path.cwd()/'test-cgroup-root';cid='a'*64
        self.assertEqual(group_path('0::/system.slice/docker-'+cid+'.scope',cid,root),root/'system.slice'/('docker-'+cid+'.scope'))
        for text in ['0::/system.slice/docker-other.scope','0::/../docker-'+cid+'.scope','1:cpu:/docker-'+cid+'.scope']:
            with self.assertRaises(ValueError):group_path(text,cid,root)

if __name__=='__main__':unittest.main()
