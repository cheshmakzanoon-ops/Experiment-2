"""Finish the exact formatted candidate, refusing changed edit targets."""
from pathlib import Path
import subprocess


def replace(path, old, new):
    file = Path(path)
    text = file.read_text(encoding='utf-8')
    if text.count(old) != 1:
        raise RuntimeError(f'Expected one edit target in {path}')
    file.write_text(text.replace(old, new), encoding='utf-8')


replace('app/src/main/java/com/artflow/studio/presentation/ui/components/export/ExportSheet.kt',
        'targetWidth = null, targetHeight = null', 'targetWidth = null')
replace('app/src/androidTest/java/com/artflow/studio/ExportUiRegressionTest.kt',
        'PdfPageSize.A4,', 'PdfPageSize.A4_PORTRAIT,')
# Follow-up diffs are readable tracked files; git refuses mismatched source context.
for patch in sorted(Path('.github/export-repair-parts').glob('fix-*.patch')):
    subprocess.run(['git', 'apply', '--check', str(patch)], check=True)
    subprocess.run(['git', 'apply', str(patch)], check=True)
print('Applied final source and regression-test corrections.')
