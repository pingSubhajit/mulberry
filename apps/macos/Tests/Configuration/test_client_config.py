"""Packaging boundaries: local HTTP must be explicit and server settings never ship."""
import os
from pathlib import Path
import plistlib
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'Scripts/copy-client-config.py'

class ClientConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.mac = self.root / 'macos'
        scripts = self.mac / 'Scripts'
        scripts.mkdir(parents=True)
        (scripts / SCRIPT.name).write_bytes(SCRIPT.read_bytes())
        config = self.root / 'ios/Configuration'
        config.mkdir(parents=True)
        for variant, bid in [('prod', 'com.subhajit.mulberry.beta'), ('dev', 'com.subhajit.mulberry.dev')]:
            (config / f'GoogleService-Info.{variant}.plist').write_bytes(plistlib.dumps({'BUNDLE_ID': bid}))
        (self.mac / '.env.release').write_text('MULBERRY_MAC_GOOGLE_CLIENT_ID=existing-desktop-client\nSERVER_PRIVATE_KEY=never-copy\n')
        self.env = {k:v for k,v in os.environ.items() if not k.startswith('MULBERRY_')}
        self.env.update(MULBERRY_APP_ENVIRONMENT='prod', PRODUCT_BUNDLE_IDENTIFIER='com.subhajit.mulberry.beta', TARGET_BUILD_DIR=str(self.root / 'build'), UNLOCALIZED_RESOURCES_FOLDER_PATH='Resources')

    def run_copy(self, **values):
        return subprocess.run(['python3', str(self.mac / 'Scripts' / SCRIPT.name)], env=self.env | values, capture_output=True, text=True)

    def test_release_default_and_secret_allowlist(self):
        self.assertEqual(self.run_copy().returncode, 0)
        content = (self.root / 'build/Resources/.env').read_text()
        self.assertIn('https://api.mulberry.my/', content)
        self.assertNotIn('SERVER_PRIVATE_KEY', content)
        self.assertNotIn('never-copy', content)

    def test_release_http_requires_explicit_local_override(self):
        self.assertNotEqual(self.run_copy(MULBERRY_MAC_API_BASE_URL='http://127.0.0.1:8080/').returncode, 0)
        self.assertEqual(self.run_copy(MULBERRY_MAC_API_BASE_URL='http://127.0.0.1:8080/', MULBERRY_ALLOW_LOCAL_SERVER='YES').returncode, 0)

    def test_local_override_cannot_ship_remote_http(self):
        self.assertNotEqual(self.run_copy(MULBERRY_MAC_API_BASE_URL='http://api.mulberry.my/', MULBERRY_ALLOW_LOCAL_SERVER='YES').returncode, 0)

    def test_firebase_identity_must_match(self):
        self.env['PRODUCT_BUNDLE_IDENTIFIER']='com.subhajit.mulberry.dev'
        self.assertNotEqual(self.run_copy().returncode, 0)

    def test_unsigned_development_build_needs_no_private_configuration(self):
        self.env.update(MULBERRY_APP_ENVIRONMENT='dev', PRODUCT_BUNDLE_IDENTIFIER='com.subhajit.mulberry.dev',
                        MULBERRY_MAC_UNSIGNED_BUILD='YES')
        (self.root / 'ios/Configuration/GoogleService-Info.dev.plist').unlink()
        result = self.run_copy()
        self.assertEqual(result.returncode, 0, result.stderr)
        resources = self.root / 'build/Resources'
        self.assertIn('http://127.0.0.1:8080/', (resources / '.env').read_text())
        self.assertNotIn('existing-desktop-client', (resources / '.env').read_text())
        self.assertFalse((resources / 'GoogleService-Info.plist').exists())

    def test_unsigned_mode_cannot_relax_production_requirements(self):
        self.assertNotEqual(self.run_copy(MULBERRY_MAC_UNSIGNED_BUILD='YES').returncode, 0)

    def test_signed_development_still_requires_configuration(self):
        self.env.update(MULBERRY_APP_ENVIRONMENT='dev', PRODUCT_BUNDLE_IDENTIFIER='com.subhajit.mulberry.dev')
        self.assertNotEqual(self.run_copy().returncode, 0)

if __name__ == '__main__':
    unittest.main()
