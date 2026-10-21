# Independent merged-preview fixtures

These three tiny synthetic PSDs were generated with **psd-tools 1.26.0**, Pillow
13.0.0 and NumPy 2.5.4. They contain no third-party artwork. They test RGB merged
preview white matting independently of `PsdCodec.write`, including raw, PackBits
RLE and ZIP compression. The layer's straight RGBA is deliberately different
from its merged preview's stored RGB.

Primary sources:

- [Adobe PSD specification](https://www.adobe.com/devnet-apps/photoshop/fileformatashtml/):
  a negative layer count identifies the first extra channel as merged transparency.
- [psd-tools source](https://github.com/psd-tools/psd-tools): the installed 1.26.0
  package's `api/numpy_io.py::_restore_background` mattes RGB over white;
  `api/pil_io.py::_remove_white_background` reverses that representation.
- [psd-tools 1.26.0 package](https://pypi.org/project/psd-tools/1.26.0/).

Reproduce in a separate environment (this package is not an app/test dependency):

```python
from pathlib import Path
from PIL import Image
from psd_tools import PSDImage
from psd_tools.constants import Compression

output = Path("app/src/test/resources/psd")
source = Image.frombytes("RGBA", (3, 1), bytes([
    18, 52, 86, 128, 101, 67, 33, 255, 17, 34, 51, 0,
]))
for compression in (Compression.RAW, Compression.RLE, Compression.ZIP):
    document = PSDImage.new("RGBA", (3, 1), color=(0, 0, 0, 0), compression=compression)
    document.background_color = None
    document.create_pixel_layer(source, name="Independent source", compression=compression)
    # Explicitly declare the merged transparency channel before its encoder runs.
    document._record.layer_and_mask_information.layer_info.layer_count = -1
    document.save(output / f"merged-preview-{compression.name.lower()}.psd")
```

| File | Bytes | SHA-256 |
|---|---:|---|
| `merged-preview-raw.psd` | 302 | `fa2c0d2c3263b1fff8700147f2b2d01326e6f56209d9366d8e2b805863f96c3d` |
| `merged-preview-rle.psd` | 326 | `58731dc4682528425d52691be58dde4ed9a54d0115fe3cf0242115ba7e6468e6` |
| `merged-preview-zip.psd` | 342 | `9a6d481211673899856f228f75e81bca888b0e824c8d766cb2d9fa1c5f0d1e53` |

The inverse uses nearest-integer rounding; psd-tools' PIL conversion truncates,
so its visible RGB differs by one code value at alpha 128. Low-alpha RGB has the
normal quantization of an 8-bit matted preview. Layer RGBA remains exact.

Compatibility: the base ArtFlow writer emitted negative layer counts and
straight merged RGB without any creator/version marker (only resolution).
There is no reliable metadata-based way to distinguish those old previews from
standard PSDs. Import keeps their independent layer pixels intact. The reader
does not guess a matte convention from pixel colors. White-unmatting is scoped
to RGB composite data with a negative layer count; other supported color modes
and unmarked composites keep their existing interpretation.
