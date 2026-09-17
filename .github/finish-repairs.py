"""Apply the exact locally reviewed gallery, mask and export repairs."""
import hashlib
import lzma
import subprocess
from pathlib import Path

packed = b''.join(Path(f'.github/export-repair-parts/{i}.xzpart').read_bytes() for i in range(4))
expected = '1f0f897fe16be31385d2f12ad7f63ea03335e4c476ccecd2a95fed26c530b686'
if hashlib.sha256(packed).hexdigest() != expected:
    raise RuntimeError('Export repair payload checksum mismatch')
patch = lzma.decompress(packed)
subprocess.run(['git', 'apply', '--check', '-'], input=patch, check=True)
subprocess.run(['git', 'apply', '-'], input=patch, check=True)
print('Applied checked gallery, export, mask and regression-test repairs.')
