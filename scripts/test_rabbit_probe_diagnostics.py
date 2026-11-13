"""Related, bounded primitive checks; no Docker, broker or business requests."""
import os
import subprocess
import sys
import time
import unittest
from unittest.mock import patch
from rabbit_probe_diagnostics import safe_output,ProbeBook,manual_script,decode_http,bounded_json_process
import rabbit_probe_diagnostics as diagnostic

class ProbeChecks(unittest.TestCase):
    def test_engine_timeout_requires_explicit_output(self):
        self.assertEqual(safe_output(b'Healthcheck exceeded timeout (5s)')['categories'],['DOCKER_TIMEOUT'])
        self.assertNotIn('DOCKER_TIMEOUT',safe_output(b'')['categories'])
    def test_never_persists_raw(self):
        value=safe_output(b'arbitrary private message');self.assertNotIn('raw',value);self.assertNotIn('arbitrary',str(value))
    def test_known_secret_suppresses_hash(self):
        value=safe_output(b'x-my-generated-value-y',['my-generated-value']);self.assertTrue(value['sensitiveSuppressed']);self.assertIsNone(value['outputSha256'])
    def test_cookie_and_authorization_suppressed(self):
        for text in [b'cookie hash abcd',b'Authorization Basic test',b'password value',b'eyJabcdefghijk.abcdefghijk.abcdefghijk']:
            self.assertTrue(safe_output(text)['sensitiveSuppressed'])
    def test_stage_allowlist(self):
        v=safe_output(b'__D71__ RUNNING_END 0\n__D71__ PRIVATE secret\n__D71__ WRAPPER 123\n')
        self.assertEqual([x['stage'] for x in v['markers']],['RUNNING_END','WRAPPER'])
    def test_probe_book_dedup_and_identity(self):
        b=ProbeBook('a'*64);x={'Id':'a'*64,'State':{'Health':{'Log':[dict(Start='one',End='two',ExitCode=-1,Output='')]}}}
        b.add(x);b.add(x);self.assertEqual(len(b.snapshot()),1)
        x['State']['Health']['Log'][0]['ExitCode']=0
        with self.assertRaises(ValueError):b.add(x)
        with self.assertRaises(ValueError):b.add(dict(x,Id='b'*64))
    def test_starting_results_not_completed(self):
        b=ProbeBook('a'*64);b.add({'Id':'a'*64,'State':{'Health':{'Log':[dict(Start='x',End='0001-01-01',ExitCode=0)]}}});self.assertEqual(b.snapshot(),[])
    def test_http_auth_errors_are_not_ready(self):
        for code in [401,403,404,503]:self.assertFalse(decode_http(code,b'{}','ready','test')['available'])
    def test_http_payload_and_identity(self):
        self.assertTrue(decode_http(200,b'{"status":"ok"}','ready','test')['available'])
        self.assertFalse(decode_http(200,b'[{"name":"other"}]','vhost','test')['available'])
        self.assertTrue(decode_http(200,b'[{"name":"test"}]','vhost','test')['available'])
        self.assertFalse(decode_http(200,b'[]','ready','test')['available'])
        self.assertFalse(decode_http(200,b'bad','ready','test')['available'])
    def test_vhost_control_requires_exact_visible_identity(self):
        for body in [b'[]',b'{}',b'{"name":"test"}',b'[{}]',b'[null]',b'[{"name":3}]',b'[{"name":"test"},{"name":"test"}]']:
            self.assertFalse(decode_http(200,body,'vhost','test')['available'])
        for code in [401,403,404,503]:
            self.assertFalse(decode_http(code,b'[{"name":"test"}]','vhost','test')['available'])
    def test_vhost_transport_uses_management_collection(self):
        class Reply:
            status=200
            def read(self,limit):return b'[{"name":"test"}]'
        with patch.object(diagnostic,'HTTPConnection') as factory:
            connection=factory.return_value;connection.getresponse.return_value=Reply()
            result=diagnostic._http_result('127.0.0.1','vhost','test','controlled-test-only')
            self.assertTrue(result['available'])
            args,kwargs=connection.request.call_args
            self.assertEqual(args,('GET','/api/vhosts?disable_stats=true'))
            self.assertIn('Authorization',kwargs['headers']);connection.close.assert_called_once()
    def test_unauthed_ready_not_authentication_proof(self):
        self.assertTrue(decode_http(200,b'{"status":"ok"}','ready','test')['available']) # credential policy belongs to the separate control endpoint.
    def test_wrong_version_not_compatible(self):
        self.assertFalse(decode_http(200,b'{"rabbitmq_version":"4.4.0"}','overview','test')['available'])
    def test_transport_constructs_connection_and_closes(self):
        class Reply:
            status=200
            def read(self,limit):return b'{"rabbitmq_version":"4.3.6"}'
        with patch.object(diagnostic,'HTTPConnection') as factory:
            connection=factory.return_value;connection.getresponse.return_value=Reply()
            value=diagnostic._http_result('127.0.0.1','overview','campuslife_'+'a'*32,None)
            self.assertTrue(value['available']);factory.assert_called_once_with('127.0.0.1',15672,timeout=1.8);connection.close.assert_called_once()
    @unittest.skipUnless(os.name=='posix','controlled real HTTP child checked on target Linux')
    def test_actual_bounded_http_child_loopback(self):
        from http.server import HTTPServer,BaseHTTPRequestHandler
        import threading
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                if self.path=='/api/overview':body=b'{"rabbitmq_version":"4.3.6"}'
                elif self.path=='/api/vhosts?disable_stats=true':body=b'[{"name":"campuslife_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]'
                else:self.send_error(404);return
                self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        server=HTTPServer(('127.0.0.1',15672),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            for kind in ['overview','vhost']:
                result=diagnostic.http('127.0.0.1',kind,'campuslife_'+'a'*32,'controlled-test-only')
                self.assertTrue(result['available'],result);self.assertLess(result['hostElapsedSeconds'],2)
        finally:server.shutdown();server.server_close();thread.join(2)
    def test_script_limits_and_input(self):
        self.assertIn('if [ "$result" -ne 0 ]; then exit',manual_script('combined'))
        self.assertNotIn('check_port_connectivity',manual_script('running'));self.assertNotIn('check_running',manual_script('ports'))
        with self.assertRaises(ValueError):manual_script('arbitrary')
    @unittest.skipUnless(os.name=='posix','POSIX short circuit checked on target Linux')
    def test_actual_shell_short_circuit(self):
        stub='gosu() { return 7; }\n';p=subprocess.run(['sh','-s'],input=stub+manual_script('combined'),capture_output=True,text=True,timeout=1)
        self.assertEqual(p.returncode,7);self.assertIn('RUNNING_END 7',p.stdout);self.assertNotIn('PORTS_BEGIN',p.stdout)
    def test_total_deadline_terminates_owned_process(self):
        at=time.monotonic();result=bounded_json_process([sys.executable,'-c','import time; time.sleep(3)'],b'',.5)
        self.assertEqual(result['error'],'HTTP_TOTAL_DEADLINE');self.assertTrue(result['childExited']);self.assertLess(time.monotonic()-at,2)

if __name__=='__main__':unittest.main()
