import hashlib
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile
import zlib

import collect_installed_apks as collector

TOKEN = '00000000-0000-4000-8000-000000000001'
RETRY_TOKEN = '00000000-0000-4000-8000-000000000002'


def apk_bytes(marker=b'installed', duplicate=False):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        archive.writestr('AndroidManifest.xml', b'manifest')
        archive.writestr('classes.dex', marker)
        archive.writestr('classes2.dex', b'second dex')
        archive.writestr('assets/classes.dex', b'not a root dex')
        if duplicate:
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                archive.writestr('classes.dex', b'duplicate dex')
    return output.getvalue()


class InstalledApkCaptureTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.project = self.root / 'project'
        self.project.mkdir()
        self.main, self.test = 'com.artflow.studio', 'com.artflow.studio.test'
        self.main_path, self.test_path = '/data/app/main package/base.apk', '/data/app/tests/base.apk'
        self.payloads = {self.main: [(self.main_path, apk_bytes())],
                         self.test: [(self.test_path, apk_bytes(b'test dex'))]}
        self.write_built(self.main, apk_bytes(b'different built dex'))
        self.write_built(self.test, self.payloads[self.test][0][1])
        self.namespace, self.source = self.make_snapshot()

    def write_built(self, package, data):
        path = self.project / collector.PACKAGES[package].replace('*.apk', 'app.apk')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def make_snapshot(self, token=TOKEN, device='emulator-5554 - 15', payloads=None):
        namespace = (self.project / collector.EVIDENCE_ROOT / 'debugAndroidTest/connected' / device /
                     'test-evidence/apk-provenance' / token)
        namespace.mkdir(parents=True, exist_ok=True)
        source = {'schemaVersion': 1, 'attemptToken': token, 'capturedAtUtc': '2026-10-25T00:00:00Z',
                  'pid': 1234, 'packages': {}, 'errors': []}
        for package, apks in (payloads or self.payloads).items():
            record = {'packageName': package, 'apks': [], 'errors': []}
            source['packages'][package] = record
            for index, (device_path, data) in enumerate(apks):
                relative = f'installed/{package}/{index:02d}.apk'
                path = namespace / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
                record['apks'].append({'devicePath': device_path, 'file': relative, 'sizeBytes': len(data),
                                       'sha256': hashlib.sha256(data).hexdigest(), 'captureComplete': True, 'errors': []})
        (namespace / 'manifest.json').write_text(json.dumps(source))
        return namespace, source

    def update_source(self):
        (self.namespace / 'manifest.json').write_text(json.dumps(self.source))

    def replace_snapshot(self, data, package=None):
        item = self.source['packages'][package or self.main]['apks'][0]
        (self.namespace / item['file']).write_bytes(data)
        item['sizeBytes'], item['sha256'] = len(data), hashlib.sha256(data).hexdigest()
        self.update_source()

    def capture(self, status=37, name='attempt-1', token=TOKEN):
        output = self.root / name
        # Package-manager/ADB fallback would be too late: UTP has uninstalled both packages.
        with mock.patch('subprocess.run', side_effect=AssertionError('No installed-package fallback is permitted')):
            manifest = collector.collect(output, self.project, status, token)
        self.assertEqual(manifest, json.loads((output / 'manifest.json').read_text()))
        return output, manifest

    def test_failure_keeps_exact_installed_bytes_and_both_package_dex_hashes(self):
        output, manifest = self.capture()
        self.assertEqual(37, manifest['instrumentationStatus'])
        self.assertFalse(self.namespace.exists())
        self.assertTrue(manifest['runnerCaptures'][0]['accepted'])
        raw = output / manifest['runnerCaptures'][0]['manifestPath']
        self.assertEqual(self.source, json.loads(raw.read_text()))
        for package in (self.main, self.test):
            record = manifest['packages'][package]['installedApks'][0]
            retained = output / record['retainedPath']
            self.assertTrue(record['captureComplete'])
            self.assertEqual(self.payloads[package][0][1], retained.read_bytes())
            self.assertEqual(hashlib.sha256(retained.read_bytes()).hexdigest(), record['sha256'])
            self.assertEqual(len(retained.read_bytes()), record['sizeBytes'])
            self.assertEqual(['classes.dex', 'classes2.dex'], [dex['name'] for dex in record['dex']])
            self.assertEqual(hashlib.sha256(b'second dex').hexdigest(), record['dex'][1]['sha256'])
        main = manifest['packages'][self.main]
        self.assertNotEqual(main['builtApks'][0]['sha256'], main['installedApks'][0]['sha256'])
        self.assertEqual([], main['installedApks'][0]['matchingBuiltApks'])
        self.assertEqual([manifest['packages'][self.test]['builtApks'][0]['path']],
                         manifest['packages'][self.test]['installedApks'][0]['matchingBuiltApks'])

    def test_success_cleans_only_its_payloads_and_preserves_ignored_attempt_evidence(self):
        stale, _ = self.make_snapshot(token=RETRY_TOKEN)
        (stale / 'leftover.apk.partial').write_bytes(b'incomplete')
        (self.namespace / 'leftover.apk.partial').write_bytes(b'current incomplete')
        output, manifest = self.capture(status=0)
        self.assertFalse(manifest['retainInstalledApks'])
        self.assertEqual([], list(output.rglob('*.apk')))
        self.assertEqual([], list(self.namespace.rglob('*.apk')))
        self.assertEqual([], list(self.namespace.rglob('*.partial')))
        self.assertEqual(2, len(list(stale.rglob('*.apk'))))
        self.assertEqual(b'incomplete', (stale / 'leftover.apk.partial').read_bytes())
        self.assertTrue((self.namespace / 'manifest.json').is_file())
        self.assertTrue((output / manifest['runnerCaptures'][0]['manifestPath']).is_file())
        self.assertEqual([RETRY_TOKEN], manifest['ignoredCaptureTokens'])
        for package in manifest['packages'].values():
            self.assertIsNone(package['installedApks'][0]['retainedPath'])
            self.assertTrue(package['installedApks'][0]['captureComplete'])
            self.assertTrue((self.project / package['builtApks'][0]['path']).is_file())

    def test_all_split_paths_are_verified_and_retained(self):
        self.payloads[self.main].append(('/data/app/main package/split_config.x86_64.apk', apk_bytes(b'split dex')))
        self.namespace, self.source = self.make_snapshot()
        output, manifest = self.capture()
        records = manifest['packages'][self.main]['installedApks']
        self.assertEqual([pair[0] for pair in self.payloads[self.main]], [record['devicePath'] for record in records])
        self.assertTrue(all(record['captureComplete'] for record in records))
        self.assertEqual(3, len(list(output.rglob('*.apk'))))

    def test_missing_built_and_missing_runner_package_never_trigger_substitution(self):
        shutil.rmtree(self.project / 'app/build/outputs/apk')
        del self.source['packages'][self.main]
        self.update_source()
        _, manifest = self.capture()
        record = manifest['packages'][self.main]
        self.assertEqual([], record['builtApks'])
        self.assertEqual([], record['installedApks'])
        self.assertEqual(['no built APKs', 'Missing or mismatched runner package identity'],
                         [error['error'] for error in record['errors']])
        self.assertTrue(manifest['packages'][self.test]['installedApks'][0]['captureComplete'])

    def test_interrupted_runner_copy_keeps_partial_bytes_without_claiming_completeness(self):
        source = self.source['packages'][self.main]['apks'][0]
        path = self.namespace / source['file']
        path.unlink()
        Path(str(path) + '.partial').write_bytes(b'partial installed bytes')
        source.update(sizeBytes=None, sha256=None, captureComplete=False, errors=[{'error': 'interrupted copy'}])
        self.update_source()
        output, manifest = self.capture()
        record = manifest['packages'][self.main]['installedApks'][0]
        self.assertFalse(record['captureComplete'])
        self.assertIsNone(record['retainedPath'])
        self.assertIsNone(record['sha256'])
        self.assertEqual(b'partial installed bytes', (output / record['partialRetainedPath']).read_bytes())
        self.assertEqual(source['errors'], record['runnerErrors'])
        self.assertTrue(manifest['packages'][self.test]['installedApks'][0]['captureComplete'])

    def test_transport_size_and_sha_mismatches_are_rejected_before_dex_inspection(self):
        main = self.source['packages'][self.main]['apks'][0]
        test = self.source['packages'][self.test]['apks'][0]
        (self.namespace / main['file']).write_bytes(self.payloads[self.main][0][1][:-3])
        altered = bytearray(self.payloads[self.test][0][1])
        altered[0] ^= 1
        (self.namespace / test['file']).write_bytes(altered)
        output, manifest = self.capture()
        for package in (self.main, self.test):
            record = manifest['packages'][package]['installedApks'][0]
            self.assertFalse(record['captureComplete'])
            self.assertEqual([], record['dex'])
            self.assertTrue((output / record['retainedPath']).is_file())
            self.assertTrue(any('SHA256' in error['error'] for error in record['errors']))
        self.assertTrue(any('size' in error['error'] for error in manifest['packages'][self.main]['installedApks'][0]['errors']))

    def test_corrupt_apk_bytes_are_hashed_preserved_and_explicitly_marked(self):
        damaged = b'corrupt installed archive'
        self.replace_snapshot(damaged)
        self.write_built(self.main, b'corrupt built archive')
        output, manifest = self.capture()
        package = manifest['packages'][self.main]
        record = package['installedApks'][0]
        self.assertEqual(damaged, (output / record['retainedPath']).read_bytes())
        self.assertFalse(record['captureComplete'])
        self.assertIn('Could not inspect APK ZIP', record['errors'][0]['error'])
        self.assertIn('Could not inspect APK ZIP', package['builtApks'][0]['errors'][0])

    def test_corrupt_deflated_dex_keeps_exact_apk_and_manifest_and_continues_collection(self):
        compressed = io.BytesIO()
        with zipfile.ZipFile(compressed, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('AndroidManifest.xml', b'manifest')
            archive.writestr('classes.dex', b'compressed dex content' * 16)
            entry = archive.getinfo('classes.dex')
        damaged = bytearray(compressed.getvalue())
        payload = entry.header_offset + 30 + len(entry.filename.encode('utf-8')) + len(entry.extra)
        damaged[payload] = 0b111
        with zipfile.ZipFile(io.BytesIO(damaged)) as archive:
            with self.assertRaises(zlib.error):
                archive.read('classes.dex')
        self.replace_snapshot(bytes(damaged))
        output, manifest = self.capture()
        record = manifest['packages'][self.main]['installedApks'][0]
        self.assertFalse(record['captureComplete'])
        self.assertEqual(bytes(damaged), (output / record['retainedPath']).read_bytes())
        self.assertEqual(hashlib.sha256(damaged).hexdigest(), record['sha256'])
        self.assertTrue(any('zlib.error' in error['error'] for error in record['errors']))
        self.assertIsNone(record['dex'][0]['sha256'])
        self.assertTrue(manifest['packages'][self.test]['installedApks'][0]['captureComplete'])

    def test_duplicate_dex_names_keep_distinct_hashes_and_report_ambiguity(self):
        self.replace_snapshot(apk_bytes(duplicate=True))
        _, manifest = self.capture()
        record = manifest['packages'][self.main]['installedApks'][0]
        duplicates = [item for item in record['dex'] if item['name'] == 'classes.dex']
        self.assertEqual(2, len(duplicates))
        self.assertNotEqual(duplicates[0]['sha256'], duplicates[1]['sha256'])
        self.assertNotEqual(duplicates[0]['headerOffset'], duplicates[1]['headerOffset'])
        self.assertFalse(record['captureComplete'])
        self.assertIn({'error': 'Duplicate DEX entry: classes.dex'}, record['errors'])

    def test_escaping_snapshot_path_is_rejected_without_reading_or_deleting_external_file(self):
        outside = self.root / 'outside.apk'
        outside.write_bytes(b'outside evidence namespace')
        self.source['packages'][self.main]['apks'][0]['file'] = str(outside)
        self.update_source()
        _, manifest = self.capture(status=0)
        record = manifest['packages'][self.main]['installedApks'][0]
        self.assertFalse(record['captureComplete'])
        self.assertNotIn('sha256', record)
        self.assertEqual('Invalid snapshot file path', record['errors'][0]['error'])
        self.assertEqual(b'outside evidence namespace', outside.read_bytes())

    def test_malformed_runner_manifest_is_preserved_with_explicit_error(self):
        (self.namespace / 'manifest.json').write_text('{partial JSON')
        output, manifest = self.capture()
        self.assertTrue(any(error['error'] == 'Could not read runner manifest' for error in manifest['errors']))
        self.assertEqual('{partial JSON', (output / manifest['runnerCaptures'][0]['manifestPath']).read_text())
        self.assertEqual(2, len(list(output.rglob('*.apk'))))

    def test_missing_current_token_rejects_stale_output_and_does_not_use_built_apks(self):
        _, manifest = self.capture(status=0, token=RETRY_TOKEN)
        self.assertEqual([TOKEN], manifest['ignoredCaptureTokens'])
        self.assertEqual(0, manifest['errors'][0]['matchingSnapshots'])
        for record in manifest['packages'].values():
            self.assertTrue(record['builtApks'])
            self.assertEqual([], record['installedApks'])
        self.assertEqual(2, len(list(self.namespace.rglob('*.apk'))))

    def test_duplicate_token_snapshots_are_retained_but_not_accepted(self):
        self.make_snapshot(device='another-device')
        output, manifest = self.capture()
        self.assertEqual(2, manifest['errors'][0]['matchingSnapshots'])
        self.assertEqual(2, len(manifest['runnerCaptures']))
        self.assertFalse(any(item['accepted'] for item in manifest['runnerCaptures']))
        self.assertEqual(4, len(list(output.rglob('*.apk'))))
        self.assertTrue(all(not record['installedApks'] for record in manifest['packages'].values()))

    def test_manifest_token_must_match_the_requested_namespace(self):
        self.source['attemptToken'] = RETRY_TOKEN
        self.update_source()
        output, manifest = self.capture()
        self.assertEqual('Mismatched or malformed runner manifest', manifest['errors'][0]['error'])
        self.assertFalse(manifest['runnerCaptures'][0]['accepted'])
        self.assertEqual(2, len(list(output.rglob('*.apk'))))

    def test_matching_snapshot_outside_canonical_utp_output_is_not_a_fallback(self):
        elsewhere = self.project / 'app/build/reports/test-evidence/apk-provenance' / TOKEN
        elsewhere.parent.mkdir(parents=True)
        shutil.move(str(self.namespace), elsewhere)
        _, manifest = self.capture()
        self.assertEqual(0, manifest['errors'][0]['matchingSnapshots'])
        self.assertEqual([], manifest['runnerCaptures'])
        self.assertTrue((elsewhere / 'manifest.json').is_file())

    def test_retry_keeps_first_failure_separate_and_never_overwrites_it(self):
        first, original = self.capture()
        payloads = {self.main: [(self.main_path, apk_bytes(b'retry dex'))], self.test: self.payloads[self.test]}
        self.make_snapshot(token=RETRY_TOKEN, payloads=payloads)
        second, retry = self.capture(status=0, name='attempt-2', token=RETRY_TOKEN)
        first_apk = original['packages'][self.main]['installedApks'][0]
        self.assertEqual(hashlib.sha256((first / first_apk['retainedPath']).read_bytes()).hexdigest(), first_apk['sha256'])
        self.assertNotEqual(first_apk['sha256'], retry['packages'][self.main]['installedApks'][0]['sha256'])
        self.assertEqual([], list(second.rglob('*.apk')))
        with self.assertRaises(FileExistsError):
            collector.collect(first, self.project, 0, RETRY_TOKEN)
        self.assertEqual(original, json.loads((first / 'manifest.json').read_text()))

    def test_successful_retry_preserves_first_originals_after_retention_move_failed(self):
        with mock.patch.object(collector.shutil, 'move', side_effect=OSError('retention unavailable')):
            first, original = self.capture()
        self.assertTrue(any('preserve runner snapshot' in error['error'] for error in original['errors']))
        self.assertFalse(original['packages'][self.main]['installedApks'][0]['captureComplete'])
        prior = self.namespace / self.source['packages'][self.main]['apks'][0]['file']
        self.assertEqual(self.payloads[self.main][0][1], prior.read_bytes())
        second_namespace, _ = self.make_snapshot(token=RETRY_TOKEN)
        _, retry = self.capture(status=0, name='attempt-2', token=RETRY_TOKEN)
        self.assertTrue(retry['packages'][self.main]['installedApks'][0]['captureComplete'])
        self.assertEqual([TOKEN], retry['ignoredCaptureTokens'])
        self.assertEqual(self.payloads[self.main][0][1], prior.read_bytes())
        self.assertEqual(self.source, json.loads((self.namespace / 'manifest.json').read_text()))
        self.assertEqual([], list(second_namespace.rglob('*.apk')))
        self.assertEqual(original, json.loads((first / 'manifest.json').read_text()))

    def test_invalid_token_cannot_change_the_search_scope(self):
        _, manifest = self.capture(status=0, token='../../outside')
        self.assertEqual('Invalid attempt token', manifest['errors'][0]['error'])
        self.assertEqual([], manifest['runnerCaptures'])
        self.assertTrue(all(not record['installedApks'] for record in manifest['packages'].values()))


if __name__ == '__main__':
    unittest.main()
