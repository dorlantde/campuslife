"""Small compatibility parsing checks; no Docker, broker or credentials."""
import unittest
from run_rabbit_health_compatibility import decode_inventory

class InventoryChecks(unittest.TestCase):
    def inventory(self,extra=''):
        return 'curl=absent\nwget=absent\ntimeout=/usr/bin/timeout\nsh=/usr/bin/sh\nbash=/usr/bin/bash\nversion_timeout=timeout (GNU coreutils) 9.4\n'+extra
    def test_missing_client_is_explicit_stop(self):
        result=decode_inventory(self.inventory());self.assertFalse(result['compatibleTools']);self.assertEqual(result['reason'],'NO_HTTP_CLIENT')
    def test_select_supported_curl(self):
        text=self.inventory().replace('curl=absent','curl=/usr/bin/curl')+'version_curl=curl 8.5.0 (x86_64-pc-linux-gnu)\n'
        result=decode_inventory(text);self.assertTrue(result['compatibleTools']);self.assertEqual(result['selectedClient'],'curl')
    def test_gnu_wget_only(self):
        text=self.inventory().replace('wget=absent','wget=/usr/bin/wget')
        self.assertFalse(decode_inventory(text+'version_wget=BusyBox v1.0\n')['compatibleTools'])
        self.assertEqual(decode_inventory(text+'version_wget=GNU Wget 1.21.4 built on linux-gnu.\n')['selectedClient'],'wget')
    def test_total_deadline_tool_required(self):
        text=self.inventory().replace('curl=absent','curl=/usr/bin/curl').replace('timeout (GNU coreutils) 9.4','unknown')+'version_curl=curl 8.5.0\n'
        self.assertEqual(decode_inventory(text)['reason'],'NO_TOTAL_DEADLINE_TOOL')
    def test_duplicate_unknown_and_bad_paths_refused(self):
        for text in [self.inventory()+'curl=absent\n',self.inventory()+'other=data\n',self.inventory().replace('/usr/bin/sh','/tmp/sh'),self.inventory().replace('sh=/usr/bin/sh\n',''),self.inventory()+'x'*8193]:
            with self.assertRaises(ValueError):decode_inventory(text)

if __name__=='__main__':unittest.main()
