"""Check the configuration boundaries without Apple accounts, devices, or servers."""
import os
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def run_script(self, name, **settings):
        return subprocess.run([sys.executable, str(ROOT / 'scripts' / name)],
                              env={**os.environ, **settings}, capture_output=True, text=True)

    def test_side_by_side_apps_cannot_claim_each_others_callback(self):
        info = plistlib.loads((ROOT / 'Configuration/Info.plist').read_bytes())
        def schemes(bundle):
            return {scheme.replace('$(PRODUCT_BUNDLE_IDENTIFIER)', bundle)
                          .replace('$(GOOGLE_REVERSED_CLIENT_ID)', 'google.' + bundle)
                    for entry in info['CFBundleURLTypes'] for scheme in entry['CFBundleURLSchemes']}
        self.assertFalse(schemes('com.subhajit.mulberry.dev') & schemes('com.subhajit.mulberry.beta'),
                         'A Shortcut callback can open the wrong installed app when schemes overlap.')

    def transport(self, url, allow=False, old_exceptions=False):
        path = self.directory / 'Mulberry.app/Info.plist'
        path.parent.mkdir(parents=True, exist_ok=True)
        info = {'MulberryAPIBaseURL': url}
        if old_exceptions:
            info.update(NSAppTransportSecurity={'NSAllowsArbitraryLoads': True}, NSLocalNetworkUsageDescription='old')
        path.write_bytes(plistlib.dumps(info))
        result = self.run_script('allow-local-server.py', TARGET_BUILD_DIR=str(self.directory),
                                 INFOPLIST_PATH='Mulberry.app/Info.plist', CONFIGURATION='ProdDebug',
                                 MULBERRY_ALLOW_LOCAL_SERVER='YES' if allow else 'NO')
        return result, plistlib.loads(path.read_bytes())

    def test_production_rejects_http_without_local_override(self):
        result, _ = self.transport('http://192.168.0.105:8080/')
        self.assertNotEqual(result.returncode, 0)

    def test_local_override_works_with_production_identity(self):
        result, info = self.transport('http://192.168.0.105:8080/', allow=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(info['NSAppTransportSecurity']['NSAllowsLocalNetworking'])
        self.assertIn('NSLocalNetworkUsageDescription', info)

    def test_return_to_production_removes_local_exceptions(self):
        result, info = self.transport('https://api.mulberry.my/', old_exceptions=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('NSAppTransportSecurity', info)
        self.assertNotIn('NSLocalNetworkUsageDescription', info)

    def firebase(self, source_bundle, **extra):
        source = self.directory / 'Configuration/GoogleService-Info.prod.plist'
        source.parent.mkdir()
        source.write_bytes(plistlib.dumps({'BUNDLE_ID': source_bundle, **extra}))
        result = self.run_script('copy-client-config.py', SRCROOT=str(self.directory),
                                 TARGET_BUILD_DIR=str(self.directory / 'build'),
                                 UNLOCALIZED_RESOURCES_FOLDER_PATH='Mulberry.app',
                                 MULBERRY_APP_ENVIRONMENT='prod', CONFIGURATION='ProdDebug',
                                 PRODUCT_BUNDLE_IDENTIFIER='com.subhajit.mulberry.beta')
        return result, self.directory / 'build/Mulberry.app/GoogleService-Info.plist'

    def test_firebase_uses_environment_instead_of_compilation_mode(self):
        result, destination = self.firebase('com.subhajit.mulberry.beta')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(destination.exists())

    def test_wrong_bundle_is_never_copied(self):
        result, destination = self.firebase('com.subhajit.mulberry.dev')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(destination.exists())

    def test_server_credentials_are_never_copied(self):
        result, destination = self.firebase('com.subhajit.mulberry.beta', private_key='test-only')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(destination.exists())

    def test_shortcut_binds_to_the_selected_team_and_bundle(self):
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/make-shortcut.py'),
                                 '--unsigned-only', '--team-id', 'HMJX9736KN',
                                 '--bundle-id', 'com.subhajit.mulberry.beta',
                                 '--output-dir', str(self.directory)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        workflow = plistlib.loads((self.directory / 'Mulberry Sync.plist').read_bytes())
        descriptors = [a['WFWorkflowActionParameters']['AppIntentDescriptor']
                       for a in workflow['WFWorkflowActions']
                       if 'AppIntentDescriptor' in a['WFWorkflowActionParameters']]
        self.assertTrue(descriptors)
        for descriptor in descriptors:
            self.assertEqual(descriptor['TeamIdentifier'], 'HMJX9736KN')
            self.assertEqual(descriptor['BundleIdentifier'], 'com.subhajit.mulberry.beta')
        self.assertNotIn(b'com.subhajit.mulberry.dev', (self.directory / 'Mulberry Sync.plist').read_bytes())


if __name__ == '__main__':
    unittest.main()
