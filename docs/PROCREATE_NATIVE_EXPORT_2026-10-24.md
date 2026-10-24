# Native document export structure, P3 and transparency — 24 October 2026

## Confirmed gaps and changes

The exporter placed its nested group tree in the archive's flat pixel-layer index. It also wrote
the legacy group visibility key without the native `isHidden` key. Newly exported documents now
write the tree to `unwrappedLayers` and a separate top-first `layers` index. The two structures
reference the same pixel-layer objects exactly once. Masks and the saved composite are excluded
from that index; groups carry their native visibility field and the existing compatibility alias.

Procreate export previously converted Display P3 artwork to sRGB because the format did not
carry a colour profile. The export pipeline now preserves its supported P3 channel values and
writes the application's generated matrix/TRC profile into the native profile descriptor.
Layers, saved composite and background use the same declared encoding. sRGB exports keep their
existing channel interpretation and absent-profile convention.

The lifecycle review also found that a hidden native background left a newly created ArtFlow
canvas at its default opaque white. Opening a native document as a new artwork now sets its
background to the native colour or transparent zero. Insertion into an existing artwork retains
the existing nullable-background behavior. The same new-document branch already owns source
profile preservation; an explicit source profile is still passed for background conversion.

These changes apply to new imports and exports. Existing saved artworks are not reinterpreted
by these metadata corrections.

## Native evidence and its boundary

The required reference gate runs
[`ipa_native_document_contracts.py`](../tools/ipa_native_document_contracts.py). It hashes the
whole pinned reference IPA and its bounded executable member before checking selected method
identities, archive strings, Objective-C calls and ARM64 dataflow as data. It neither executes
the native binary nor emits code or native assets.

The input is the same Procreate 5.4.4 reference used by the other native contract checks:
IPA SHA-256 `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`;
executable SHA-256 `76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b`.

| Native encoder evidence | Verified connection |
| --- | --- |
| `ValkyrieDocument.encodeWithCoder:`, `0x100b1a144` | The selected 2D branch writes the nested field separately from the flattened layer index. |
| `unwrappedLayers`, key at `0x100b1a43c`, call at `0x100b1a448` | Object field offset `0x120`, identical to the named getter. |
| `flattenedLayers`, call at `0x100b1a4c8` | The resulting object is passed to the `layers` encoder at `0x100b1a51c`. |
| `colorProfile`, key at `0x100b1a678`, call at `0x100b1a684` | Object field offset `0x1d0`, identical to the named getter. |
| `ValkyrieColorProfile.encodeWithCoder:`, `0x100b0e588` | Writes ICC bytes through `encodeBytes:length:forKey:` under `SiColorProfileArchiveICCDataKey`, and a separate name under `SiColorProfileArchiveICCNameKey`. |

The tool checks five method identities, five archive fields, five additional source selectors
and twenty instruction anchors. Its report explicitly marks reference execution and exported
file opening in Procreate as false. The pinned genuine archives independently provide the
`SiColorProfile` descriptor class, native group visibility key and flat-index/tree leaf identity
and order. Five sample filenames represent four distinct archive byte streams.

This is bounded archive evidence. It does not validate every reader version, group compositing
route, native ICC transform or required document metadata field.

## Colour contract

The writer accepts already encoded native channels and tags them without converting them.
The existing export pipeline supplies the requested output encoding consistently. For P3,
the descriptor contains the application's generated ICC v2 matrix/TRC profile; no original
native profile payload is copied into the repository. The document reader recognizes its
colourimetry using the existing bounded P3/sRGB matcher.

The device check uses four opaque native P3 colours, including values that differ materially
when converted to sRGB. Every native channel is checked exactly after file decoding, import
and storage reopen. The emitted PNG is independently decoded by Android and compared with
fixed LittleCMS 2.19 relative-colorimetric expectations, allowing at most one RGB code value
and requiring exact opaque alpha.

| Native P3 RGB | Independent sRGB RGB |
| --- | --- |
| 230, 70, 45 | 250, 49, 23 |
| 20, 180, 120 | 0, 183, 115 |
| 40, 70, 230 | 29, 71, 239 |
| 190, 30, 185 | 208, 0, 191 |

## Regression coverage

Four JVM cases check native field/object identity rather than relying only on the reader's
tree traversal; mask/composite exclusion; unconverted P3 layer/composite/background channels;
sRGB compatibility; and an explicitly empty hidden group. The existing writer suite additionally
covers nested groups, odd dimensions, tile boundaries, blend metadata and mask round trips.

Two device identities use the actual rendered export and native import paths:

- A P3 canvas with a hidden nested group exports through `renderedExport`, carries exact native
  colour/profile/background and archive structure, imports into a new project, saves, reopens
  through a fresh repository and exports an independently decoded PNG. Hidden editable source
  pixels and parent relationships remain present.
- A transparent native export imports into an untouched default-white new canvas, saves and
  reopens with a transparent background and exact pixels. Inserting the same file into an
  existing coloured canvas keeps its background.

The retained evidence includes both exported native archives, reopened artwork manifests
and PNG previews. The exact publication's completed Actions matrix is the Android acceptance
gate. Earlier runs do not accept these later declarations.

## Remaining limits

Exported files still require direct opening and editing in a versioned Procreate installation.
The current native tile writer, legacy RGBA mask representation and absent thumbnail do not
gain blanket native-app acceptance from these metadata checks. Native group blend/mask behavior
uses the existing rendered-appearance fallback where necessary. Animation, editable text,
guides, 3D document metadata, arbitrary ICC profiles and profile-managed CMYK are not added.

The exporter still stores premultiplied 8-bit tiles; partial-alpha colour rounding remains part
of that existing format boundary. This change does not establish physical wide-gamut display,
native GPU rounding, whole-image pixel equivalence or complete Procreate feature/quality parity.
