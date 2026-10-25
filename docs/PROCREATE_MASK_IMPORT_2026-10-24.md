# Native single-channel mask import

The [25 October follow-up](PROCREATE_MASK_EXPORT_2026-10-25.md) establishes the native mask-type
contract, resolves typed edge-tile ambiguity, and addresses independent native mask export. This
earlier record preserves the evidence and limits of the initial import correction.

## Confirmed defect

`ProcreateReader.Tiles.place` previously accepted only four-byte RGBA tiles. The genuine
`Mask_Test_File.procreate` instead stores each mask pixel as one byte of coverage. Its 14 mask
tiles each decompress to 65,536 bytes for a 256 × 256 tile; its 20 paint tiles and 64 saved-composite
tiles each decompress to 262,144 bytes. The reader silently discarded every mask tile and
returned a mask that revealed the entire layer.

The inspected source is
[Avarel/silicate at 0e12deb2ae1ffb152fc4a3d09add230590f52f68](https://github.com/Avarel/silicate/tree/0e12deb2ae1ffb152fc4a3d09add230590f52f68/demo_files).
The 232,281-byte sample has SHA-256
`17cd313a1b56950738de392595d33045c423469ff9385b5a63efb6e3f37aa83c`.
The source document is fetched by the existing CI sample step; it is not copied into this repository.

The mask's archive UUID is `A925F0EE-1C42-48B2-8424-F7E1526D27D6`. Decoding its bytes directly as
coverage, using the same calibrated orientation as the paint and saved composite, explains the
visible discrepancy. Coverage zero conceals the paint, 255 reveals it, and absent mask tiles
reveal it. Intermediate values are retained unchanged.

| Upright canvas coordinate | Source mask coverage | Saved native composite | Old recomposite | Corrected recomposite |
| --- | ---: | --- | --- | --- |
| (1244, 616) | 128 | `ff7f7f7f` | `ff000000` | `ff7f7f7f` |
| (1280, 640) | 128 | `ff7f7f7f` | `ff000000` | `ff7f7f7f` |
| (1024, 1024) | 255 | `ff000000` | `ff000000` | `ff000000` |

The existing four-pixel-grid report measured a mean absolute RGB-channel difference of
2.081298828125 before the correction. An independent full-size placement of the source coverage,
followed by the production compositor, compares all 4,194,304 pixels against the saved native
composite: 270 pixels differ by one unit in each RGB channel, all other pixels match, and alpha
matches everywhere. The resulting mean absolute RGB-channel difference is
0.00006437301635742188. These are observed residuals, **not pixel equality or a general parity
tolerance**. The genuine-file regression asserts the demonstrated coverage and composite pixels
directly rather than introducing a broad mean-error threshold.

## Bounded correction and compatibility

The mask call now explicitly permits one-byte tiles. Full and cropped one-byte tile layouts are
placed without resampling, then share the existing orientation and mask-coverage conversion.
Ordinary paint layers and the saved composite still require RGBA tiles. Existing RGBA masks,
including their alpha handling, retain their previous decoding and quantization.

Some byte lengths are inherently ambiguous without additional format information: a padded
16 × 16 one-byte tile and a cropped 8 × 8 RGBA tile are both 256 bytes. Established whole/cropped
RGBA interpretations retain precedence. An entirely ambiguous edge-only native mask is therefore
still unresolved by byte length alone. The implementation does not guess a new native type-code
contract or change previously readable RGBA files to resolve that case.

This changes newly decoded external masks. Persisted ArtFlow layer masks are already stored as
coverage rasters and require no document-version migration.

## Verification scope

`ProcreateMaskTilesTest` adds seven JVM regressions: all 256 coverage values and missing tiles,
mask-only acceptance, cropped and padded edge placement, a reference-fixture quarter-turn/mirror,
legacy RGBA alpha and ambiguous-size precedence, and malformed/out-of-bounds inputs. A bounded
standalone JVM run also includes the existing reader/writer suites: 27 tests pass.

`ProcreateMaskImportDeviceTest` adds an Android regression through `ProcreateImport`,
`LayerImports`, real project storage, a fresh repository, and PNG export. Its self-authored archive
uses native one-byte mask layouts and an Android-decoded thumbnail; no Procreate artwork is
embedded in the test. It checks coverage, preserved concealed paint, persisted pixels and exported
pixels, including toggling the mask after reopening. Compilation and emulator execution belong to
the combined candidate's subsequent Gradle and GitHub Actions gates.

The other genuine blend-file residuals and native layer/group semantics are separate questions.
This correction makes no claim about their equivalence, native brush transfer functions, or
overall Procreate feature and quality parity.
