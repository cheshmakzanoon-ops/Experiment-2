import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest import mock

import collect_native_tombstones as collector

TOKEN = '00000000-0000-4000-8000-000000000001'


def tombstone(pid=1234, package='com.artflow.studio', stamp='2026-10-25 03:00:02.123456789+0000'):
    return (f"*** *** ***\nTimestamp: {stamp}\nPage size: 16384 bytes\nCmdline: {package}\n"
            f"pid: {pid}, tid: {pid}, name: app  >>> {package} <<<\n"
            "signal 11 (SIGSEGV), code 1, fault addr 0x10\n"
            "    rax 0000000000000010\nbacktrace:\n    #00 pc 00000000008551c4 libart.so\n"
            "\n--- --- ---\npid: 1234, tid: 1250, name: worker\nbacktrace:\n"
            "    #00 pc 0000000000000010 libc.so\n\nmemory map (2 entries):\n"
            "    12340000-12341000 r-x /apex/com.android.art/lib64/libart.so\n").encode()


class FakeAdb:
    def __init__(self, files):
        self.files = files
        self.calls = []
        self.uid = b'0\n'
        self.failure = None
        self.transfer = None
        self.hashes = {}
        self.sizes = {}
        self.stat_count = {}
        self.changed_mtime = False

    def run(self, arguments, destination=None):
        self.calls.append((list(arguments), destination is not None))
        if self.failure:
            error = self.failure(arguments, destination)
            if error:
                raise collector.CaptureError(error)
        if arguments == ['shell', 'id', '-u']:
            return self.uid
        if arguments == ['shell', 'cat', '/proc/sys/kernel/random/boot_id']:
            return b'00000000-0000-4000-8000-000000000099\n'
        if arguments[:3] == ['shell', 'date', '-u']:
            return b'2026-10-25T03:00:10Z\n'
        if arguments == ['shell', 'ls', '-1', '/data/tombstones']:
            return ('\n'.join(self.files) + '\n').encode()
        if arguments[:3] == ['shell', 'stat', '-c']:
            name = arguments[-1].split('/')[-1]
            self.stat_count[name] = self.stat_count.get(name, 0) + 1
            size = self.sizes.get(name, len(self.files[name]))
            modified = 100 + int(self.changed_mtime and self.stat_count[name] > 1)
            return f'{size}:{modified}\n'.encode()
        if arguments[0] == 'shell' and len(arguments) == 2:
            match = re.fullmatch(r'head -c ([0-9]+) /data/tombstones/(tombstone_[0-9]{2,3}) \| sha256sum', arguments[1])
            if match:
                size, name = int(match[1]), match[2]
                value = self.hashes.get(name, hashlib.sha256(self.files[name][:size]).hexdigest())
                return (value + '  -\n').encode()
        if arguments[:3] == ['exec-out', 'head', '-c']:
            size, name = int(arguments[3]), arguments[4].split('/')[-1]
            data = self.files[name][:size]
            if destination is None:
                return data
            if self.transfer:
                return self.transfer(name, data, destination)
            destination.write_bytes(data)
            return b''
        raise AssertionError(arguments)


class NativeTombstoneCaptureTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.provenance = self.root / 'apk-manifest.json'
        self.source = {'schemaVersion': 1, 'attemptToken': TOKEN, 'instrumentationStatus': 37,
                       'runnerCaptures': [{'accepted': True, 'pid': 1234,
                                           'capturedAtUtc': '2026-10-25T03:00:01.000001Z'}]}
        self.write_source()
        self.adb = FakeAdb({'tombstone_02': tombstone()})

    def write_source(self):
        self.provenance.write_text(json.dumps(self.source))

    def capture(self, status=37, token=TOKEN, name='captured'):
        output = self.root / name
        result = collector.collect(output, self.provenance, status, token, self.adb)
        self.assertEqual(result, json.loads((output / 'manifest.json').read_text()))
        self.assertFalse(any(args[0] in ('root', 'reboot') or args[:2] in
                             (['shell', 'stop'], ['shell', 'start']) for args, _ in self.adb.calls))
        return output, result

    def test_exact_original_app_text_and_sections_survive_with_verified_hashes(self):
        self.adb.files['tombstone_02.pb'] = b'not independently attributed'
        output, result = self.capture()
        self.assertEqual('captured', result['captureStatus'])
        record = result['files'][0]
        self.assertTrue(record['transferIntegrityVerified'])
        self.assertTrue(record['identityMatchesAttempt'])
        self.assertEqual(tombstone(), (output / record['retainedPath']).read_bytes())
        self.assertEqual(hashlib.sha256(tombstone()).hexdigest(), record['local']['sha256'])
        self.assertEqual(record['local']['sha256'], record['remoteBefore']['sha256'])
        self.assertEqual(record['local']['sha256'], record['remoteAfter']['sha256'])
        self.assertEqual('not_assessed', record['debuggerContentCompleteness'])
        self.assertTrue(result['candidates'][0]['protobufPresent'])
        self.assertEqual([], list(output.rglob('*.pb')))

    def test_exact_pid_process_and_device_time_filter_out_unrelated_reports(self):
        self.adb.files.update({
            'tombstone_03': tombstone(pid=5678),
            'tombstone_04': tombstone(package='com.google.android.gms'),
            'tombstone_05': tombstone(stamp='2026-10-25 03:00:00.999999+0000'),
            'tombstone_06': tombstone(stamp='2026-10-25 03:00:11.000000+0000'),
            'tombstone_07;touch bad': tombstone(),
            '../tombstone_08': tombstone(),
        })
        output, result = self.capture()
        self.assertEqual(['files/tombstone_02'], [r['retainedPath'] for r in result['files']])
        self.assertEqual(1, len(list((output / 'files').iterdir())))
        self.assertEqual(5, len(result['candidates']))
        self.assertFalse(any('touch bad' in ' '.join(a) or '../' in ' '.join(a) for a, _ in self.adb.calls))

    def test_missing_or_mismatched_runner_identity_never_queries_device(self):
        variants = [dict(self.source, attemptToken='00000000-0000-4000-8000-000000000002'),
                    dict(self.source, instrumentationStatus=0),
                    dict(self.source, runnerCaptures=[]),
                    dict(self.source, runnerCaptures=[{'accepted': False, 'pid': 1234}]),
                    dict(self.source, runnerCaptures=[{'accepted': True, 'pid': True}]),
                    dict(self.source, runnerCaptures=[{'accepted': True, 'pid': 1234,
                                                      'capturedAtUtc': '2026-10-25 03:00:01'}])]
        for index, source in enumerate(variants):
            with self.subTest(index=index):
                self.provenance.write_text(json.dumps(source))
                _, result = self.capture(name=f'invalid-{index}')
                self.assertEqual('unavailable', result['captureStatus'])
                self.assertTrue(result['errors'])
                self.assertEqual([], self.adb.calls)
        self.provenance.unlink()
        _, result = self.capture(name='missing')
        self.assertEqual('unavailable', result['captureStatus'])
        self.assertEqual([], self.adb.calls)

    def test_success_status_never_reads_tombstones(self):
        _, result = self.capture(status=0)
        self.assertEqual('unavailable', result['captureStatus'])
        self.assertEqual([], self.adb.calls)

    def test_existing_nonroot_access_suffices_and_permission_failure_is_honest(self):
        self.adb.uid = b'2000\n'
        _, result = self.capture()
        self.assertEqual('captured', result['captureStatus'])
        self.adb.failure = lambda args, _: 'Permission denied' if args[:2] == ['shell', 'ls'] else None
        _, result = self.capture(name='denied')
        self.assertEqual('unavailable', result['captureStatus'])
        self.assertIn('Permission denied', ' '.join(result['errors']))

    def test_empty_directory_or_only_other_processes_reports_no_matching_crash(self):
        for index, files in enumerate(({}, {'tombstone_01': tombstone(pid=5678)})):
            self.adb.files = files
            _, result = self.capture(name=f'empty-{index}')
            self.assertEqual('no_matching_tombstones', result['captureStatus'])
            self.assertEqual([], result['files'])

    def test_disappeared_or_unreadable_header_is_recorded_without_transfer(self):
        self.adb.failure = lambda args, _: 'No such file or permission denied' if args[0] == 'exec-out' else None
        output, result = self.capture()
        self.assertEqual('unavailable', result['captureStatus'])
        self.assertTrue(result['candidates'][0]['errors'])
        self.assertFalse((output / 'files').exists())

    def test_oversized_or_unknown_size_is_refused_before_hash_or_transfer(self):
        for index, size in enumerate((0, collector.MAX_FILE_BYTES + 1)):
            self.adb.calls.clear()
            self.adb.sizes['tombstone_02'] = size
            _, result = self.capture(name=f'size-{index}')
            self.assertFalse(result['files'][0]['transferIntegrityVerified'])
            self.assertEqual(0, result['reservedBytes'])
            self.assertFalse(any('sha256sum' in ' '.join(a) or dest for a, dest in self.adb.calls))
        self.adb.calls.clear()
        self.adb.failure = lambda args, _: 'stat unavailable' if args[:2] == ['shell', 'stat'] else None
        _, result = self.capture(name='no-stat')
        self.assertFalse(any('sha256sum' in ' '.join(a) or dest for a, dest in self.adb.calls))

    def test_truncated_transfer_with_matching_header_is_retained_as_partial(self):
        def truncate(name, data, destination):
            destination.write_bytes(data[:-40])
            raise collector.CaptureError('ADB transfer interrupted')
        self.adb.transfer = truncate
        output, result = self.capture()
        record = result['files'][0]
        self.assertFalse(record['transferIntegrityVerified'])
        self.assertTrue(record['retainedPath'].endswith('.partial'))
        self.assertEqual(tombstone()[:-40], (output / record['retainedPath']).read_bytes())
        self.assertEqual(len(tombstone()), result['reservedBytes'])
        self.assertEqual(len(tombstone()) - 40, result['observedTransferredBytes'])

    def test_unknown_truncated_header_and_rotated_identity_never_leave_uploaded_bytes(self):
        for index, payload in enumerate((b'*** incomplete', tombstone(pid=7777),
                                         tombstone(stamp='2026-10-25 02:00:00+0000'))):
            self.adb.transfer = lambda name, data, path, payload=payload: path.write_bytes(payload)
            output, result = self.capture(name=f'rotated-{index}')
            self.assertIsNone(result['files'][0]['retainedPath'])
            self.assertFalse(result['files'][0]['transferIntegrityVerified'])
            self.assertEqual([], list(output.rglob('*.partial')))
            self.assertFalse((output / 'files').exists())
            self.assertEqual(len(tombstone()), result['reservedBytes'])

    def test_changed_remote_metadata_or_unavailable_hash_never_claims_exact_transfer(self):
        self.adb.changed_mtime = True
        output, result = self.capture()
        self.assertFalse(result['files'][0]['transferIntegrityVerified'])
        self.assertEqual(tombstone(), (output / result['files'][0]['retainedPath']).read_bytes())
        self.adb.changed_mtime = False
        self.adb.failure = lambda args, _: 'sha256sum unavailable' if 'sha256sum' in ' '.join(args) else None
        _, result = self.capture(name='no-hash')
        self.assertEqual('incomplete', result['captureStatus'])
        self.assertFalse(result['files'][0]['transferIntegrityVerified'])
        self.assertTrue(result['files'][0]['identityMatchesAttempt'])

    def test_same_size_copied_content_change_is_not_verified_with_stable_remote_metadata(self):
        altered = tombstone().replace(b'libart.so', b'libart.sz')
        self.assertEqual(len(tombstone()), len(altered))
        self.adb.transfer = lambda name, data, path: path.write_bytes(altered)
        output, result = self.capture()
        record = result['files'][0]
        self.assertEqual('incomplete', result['captureStatus'])
        self.assertTrue(record['identityMatchesAttempt'])
        self.assertFalse(record['transferIntegrityVerified'])
        self.assertEqual(record['remoteBefore'], record['remoteAfter'])
        self.assertNotEqual(record['remoteBefore']['sha256'], record['local']['sha256'])
        self.assertEqual(altered, (output / record['retainedPath']).read_bytes())

    def test_total_byte_and_file_count_limits_report_skipped_matches(self):
        self.adb.files['tombstone_03'] = tombstone()
        with mock.patch.object(collector, 'MAX_TOTAL_BYTES', len(tombstone())):
            _, result = self.capture()
        self.assertEqual(len(tombstone()), result['reservedBytes'])
        self.assertEqual(len(tombstone()), result['observedTransferredBytes'])
        self.assertEqual(0, result['files'][1]['reservedBytes'])
        with mock.patch.object(collector, 'MAX_FILES', 1):
            _, result = self.capture(name='count')
        self.assertEqual(1, len(result['files']))
        self.assertIn('Retained file count limit', result['candidates'][1]['errors'][0])
        with mock.patch.object(collector, 'MAX_CANDIDATES', 1):
            _, result = self.capture(name='headers')
        self.assertEqual(['tombstone_03'], result['skippedCandidateNames'])

    def test_local_digest_failure_still_consumes_budget_without_exposing_staging(self):
        original = collector.file_digest
        def fail_local(path):
            if path.name.startswith('tombstone_'):
                raise PermissionError('local hash denied')
            return original(path)
        self.adb.files['tombstone_03'] = tombstone()
        with mock.patch.object(collector, 'file_digest', side_effect=fail_local), \
                mock.patch.object(collector, 'MAX_TOTAL_BYTES', len(tombstone())):
            output, result = self.capture()
        self.assertEqual(2, len(result['files']))
        self.assertEqual(len(tombstone()), result['reservedBytes'])
        self.assertEqual(0, result['files'][1]['reservedBytes'])
        self.assertEqual([], list(output.rglob('*.partial')))
        self.assertIsNone(result['files'][0]['retainedPath'])

    def test_local_rename_failure_keeps_tracked_app_bytes_and_budget(self):
        with mock.patch.object(Path, 'rename', side_effect=PermissionError('rename denied')):
            output, result = self.capture()
        record = result['files'][0]
        self.assertFalse(record['transferIntegrityVerified'])
        self.assertTrue(record['retainedPath'].endswith('.partial'))
        self.assertEqual(tombstone(), (output / record['retainedPath']).read_bytes())
        self.assertEqual(len(tombstone()), result['reservedBytes'])

    def test_last_file_existence_error_preserves_record_and_reserved_budget(self):
        with mock.patch.object(Path, 'is_file', side_effect=PermissionError('file check denied')):
            _, result = self.capture()
        self.assertEqual(len(tombstone()), result['reservedBytes'])
        self.assertFalse(result['files'][0]['transferIntegrityVerified'])
        self.assertIn('Could not check retained file', result['files'][0]['errors'][-1])

    def test_adb_deadline_and_command_timeout_are_bounded(self):
        with mock.patch.object(collector.time, 'monotonic', side_effect=[0, 61]), \
                mock.patch.object(collector.subprocess, 'run') as run:
            adb = collector.Adb()
            with self.assertRaisesRegex(collector.CaptureError, 'deadline exhausted'):
                adb.run(['shell', 'id', '-u'])
            run.assert_not_called()
        with mock.patch.object(collector.time, 'monotonic', side_effect=[10, 61]), \
                mock.patch.object(collector.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'0', b'')) as run:
            self.assertEqual(b'0', collector.Adb().run(['shell', 'id', '-u']))
            self.assertEqual(4, run.call_args.kwargs['timeout'])


if __name__ == '__main__':
    unittest.main()
