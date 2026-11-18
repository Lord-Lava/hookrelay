"""Regression checks for official artifact routing and checksum failures."""
import hashlib
import importlib.util
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    'provenance', Path(__file__).with_name('verify-dependency-provenance.py'))
provenance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provenance)


class ProvenanceTest(unittest.TestCase):
    def test_gradle_distribution_checksum(self):
        for classifier in ('src', 'bin', 'all'):
            with self.subTest(classifier=classifier):
                artifact = f'gradle-9.9.1-{classifier}.zip'
                url = f'https://downloads.gradle.org/distributions/{artifact}'
                with patch.object(provenance, 'fetch', return_value=b'a' * 64) as fetch:
                    result = provenance.verify(('gradle', 'gradle', '9.9.1', artifact, ['a' * 64]))
                self.assertEqual('verified', result['status'])
                self.assertEqual(url, result['source'])
                fetch.assert_called_once_with(url + '.sha256')

    def test_gradle_distribution_mismatch_fails(self):
        with patch.object(provenance, 'fetch', return_value=b'b' * 64):
            result = provenance.verify(
                ('gradle', 'gradle', '9.9.1', 'gradle-9.9.1-src.zip', ['a' * 64]))
        self.assertEqual('MISMATCH', result['status'])

    def test_unavailable_distribution_fails(self):
        with patch.object(provenance, 'fetch', side_effect=urllib.error.URLError('unavailable')):
            result = provenance.verify(
                ('gradle', 'gradle', '9.9.1', 'gradle-9.9.1-src.zip', ['a' * 64]))
        self.assertEqual('unverified', result['status'])

    def test_maven_routing_is_preserved(self):
        with patch.object(provenance, 'fetch', return_value=b'a' * 64) as fetch:
            result = provenance.verify(('org.example', 'library', '1.0', 'library-1.0.jar', ['a' * 64]))
        self.assertEqual('verified', result['status'])
        fetch.assert_called_once_with(
            'https://repo.maven.apache.org/maven2/org/example/library/1.0/library-1.0.jar.sha256')

    def test_plugin_marker_uses_portal_artifact(self):
        content = b'plugin marker'
        with patch.object(provenance, 'fetch', return_value=content) as fetch:
            result = provenance.verify(('example', 'example.gradle.plugin', '1.0',
                                        'example.gradle.plugin-1.0.pom',
                                        [hashlib.sha256(content).hexdigest()]))
        self.assertEqual('verified', result['status'])
        fetch.assert_called_once_with(
            'https://plugins.gradle.org/m2/example/example.gradle.plugin/1.0/'
            'example.gradle.plugin-1.0.pom')


if __name__ == '__main__':
    unittest.main()
