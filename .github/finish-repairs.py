"""Apply reviewed source edits and the hash-checked release documentation."""
import hashlib
import json
import lzma
from pathlib import Path

ROOT = Path.cwd().resolve()


def replace(path, old, new):
    file = ROOT / path
    text = file.read_text(encoding='utf-8')
    if text.count(old) != 1:
        raise RuntimeError(f'Expected one edit target in {path}: {old[:70]}')
    file.write_text(text.replace(old, new), encoding='utf-8')


path = 'app/src/main/java/com/artflow/studio/data/export/Mp4Encoder.kt'
replace(path, 'import android.media.MediaMuxer\n', 'import androidx.media3.muxer.MediaMuxerCompat\n')
replace(path, 'MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)',
        'MediaMuxerCompat(output.absolutePath, MediaMuxerCompat.OUTPUT_FORMAT_MP4)')
replace(path, 'private val muxer: MediaMuxer,', 'private val muxer: MediaMuxerCompat,')
replace(path, '// MediaMuxer uses the timestamp of this empty EOS sample for the LAST frame duration.',
        '// Portable muxing honors the final duration even on Android 8, whose platform muxer ignores this EOS.')
replace('app/build.gradle.kts', '    // Room Database\n',
        '    // Portable MP4 container writing; no player, networking, or native codec dependency.\n'
        '    implementation("androidx.media3:media3-muxer:1.10.1")\n'
        '    // Update the transitive path library for current 16 KB native alignment.\n'
        '    implementation("androidx.graphics:graphics-path:1.1.0")\n\n    // Room Database\n')
path = 'app/src/androidTest/java/com/artflow/studio/data/ExportDeviceTest.kt'
replace(path, '            assertEquals(66, result.width)',
        '            evidence(result, "timing-diagnostic.mp4")\n            assertEquals(66, result.width)')
replace(path, '                assertTrue(abs(format.getLong(MediaFormat.KEY_DURATION) - 480_000L) <= 4000L)',
        '                val duration = format.getLong(MediaFormat.KEY_DURATION)\n'
        '                assertTrue("Expected 480000 microseconds, got $duration", abs(duration - 480_000L) <= 4000L)')

packed = b''.join((ROOT / f'.github/release-doc-parts/{i}.xzpart').read_bytes() for i in range(3))
if hashlib.sha256(packed).hexdigest() != 'e466c6acc88593d95e046bc228818a2e553b3a0b37130676486f3e86c35571c0':
    raise RuntimeError('Release documentation checksum mismatch')
entries = json.loads(lzma.decompress(packed))
allowed = {'README.md', 'agent.md', 'CONTRIBUTING.md', 'docs/privacy-policy.md', 'docs/play-listing.md',
           'docs/RELEASE_READINESS.md', '.github/scripts/verify_android_artifacts.py',
           '.github/scripts/test_verify_android_artifacts.py', '.github/scripts/run-device-tests.sh',
           '.github/workflows/android-ci.yml'}
if {e['path'] for e in entries} != allowed or len(entries) != len(allowed):
    raise RuntimeError('Unexpected documentation paths')
for entry in entries:
    file = ROOT / entry['path']
    old = file.read_bytes() if file.exists() else b''
    if hashlib.sha256(old).hexdigest() != entry['old']:
        raise RuntimeError(f"Documentation source changed: {entry['path']}")
    lines = old.decode('utf-8').splitlines(keepends=True)
    limit = len(lines)
    for start, end, replacement in reversed(entry['edits']):
        if not 0 <= start <= end <= limit:
            raise RuntimeError('Invalid documentation edit range')
        lines[start:end] = replacement.splitlines(keepends=True)
        limit = start
    new = ''.join(lines).encode('utf-8')
    if hashlib.sha256(new).hexdigest() != entry['new']:
        raise RuntimeError('Documentation edit checksum mismatch')
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_bytes(new)
replace('docs/RELEASE_READINESS.md', 'end-of-stream. Gallery publishing',
        'end-of-stream. MP4 uses AndroidX Media3 1.10.1 portable muxing for consistent final-frame\n'
        'duration on older Android devices. Gallery publishing')
replace('.github/workflows/android-ci.yml', '        api: [26, 36]',
        '        include:\n'
        '          - api: 26\n            target: google_apis\n            page_size: 4096\n'
        '          - api: 35\n            target: google_apis_ps16k\n            page_size: 16384\n'
        '          - api: 36\n            target: google_apis\n            page_size: 4096')
replace('.github/workflows/android-ci.yml', '          target: google_apis\n          disable-animations:',
        '          target: ${{ matrix.target }}\n          disable-animations:')
replace('.github/workflows/android-ci.yml', '      - name: Run instrumentation tests\n        uses:',
        '      - name: Run instrumentation tests\n        env:\n'
        '          EXPECTED_PAGE_SIZE: ${{ matrix.page_size }}\n        uses:')
replace('.github/scripts/run-device-tests.sh', 'trap collect EXIT\n',
        'trap collect EXIT\n'
        'page_size=$(adb shell getconf PAGE_SIZE | tr -d "\\r")\n'
        'echo "Runtime page size: $page_size" > "$root/page-size.txt"\n'
        'test "$page_size" = "${EXPECTED_PAGE_SIZE:-$page_size}" || exit 1\n')
print('Applied portable MP4 muxing, native dependency update and verified release documentation/CI.')
