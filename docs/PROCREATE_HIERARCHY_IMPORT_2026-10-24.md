# Native Procreate hierarchy import — 24 October 2026

## Demonstrated defect

The genuine blend-reference documents contain two different layer collections:

- `layers` is a flat index of the 53 or 55 pixel layers.
- `unwrappedLayers` contains the top-level pixel layers and 26 or 27 `SilicaGroup` objects.
  Each group points to its own `children` array.

The old reader used only `layers`. Its decoded output therefore contained no folders even though
the archive retained them. The earlier zero-groups observation described that decoded output; it
was not a complete census of the source archive.

An independent traversal of the native tree confirms that its leaf UUIDs exactly equal the flat
index, in both order and multiplicity, for every current sample. The hierarchy does not require
copying or inventing additional paint layers. Every group in these samples is visible, unclipped
and has opacity 1; these files do not establish all possible group rendering semantics.

## Import behavior

The reader now prefers `unwrappedLayers` when it contains a valid `NS.objects` list. A valid empty
native tree remains empty. When the field is absent, null or not an array, the existing `layers`
path remains available for older ArtFlow exports and independent fixtures.

Groups read their native `isHidden` Boolean before the older `hidden` spelling. Pixel layers
continue to use their own `hidden` field. Names, order, child relationships and opacity reach the
existing production group importer. Its ordinary imported groups use Pass Through. Faded groups
still isolate through the existing ArtFlow compositor, as before.

This correction preserves organization and the supported visibility/opacity behavior. It does not
establish native group blend-mode, mask, clipping, lock, privacy or animation metadata equivalence.
No blend equation or saved ArtFlow document is changed. Native export metadata remains outside
this correction.

## Fixed genuine inputs

Actions fetches the sample repository at
[`0e12deb2ae1ffb152fc4a3d09add230590f52f68`](https://github.com/Avarel/silicate/tree/0e12deb2ae1ffb152fc4a3d09add230590f52f68/demo_files).
The existing genuine-document test verifies the complete archive hashes before applying fixed
structural, profile and mask expectations. The binary documents remain downloaded test inputs,
outside the application and source artifacts.

| Sample | Pixel layers | Groups | Full archive SHA-256 |
| --- | ---: | ---: | --- |
| `Mask_Test_File.procreate` | 1 | 0 | `17cd313a1b56950738de392595d33045c423469ff9385b5a63efb6e3f37aa83c` |
| `Reference_Blend_File 2.procreate` | 53 | 26 | `097c338d054452887e2f4835d26a58a41ca854d4777223512334d3895b90daf0` |
| `Reference_Blend_File 3.procreate` | 55 | 27 | `8b288e0886b3cc68030dcfdeedd7ceeb37bb1e41cc3d29ac5061d968821d59db` |
| `Reference_Blend_File.procreate` | 53 | 26 | `4dca07aea1389ed521eb24f43e1a4e03dcd32d723c657d38f3e44ee69ced02f4` |
| `reference.procreate` | 55 | 27 | `8b288e0886b3cc68030dcfdeedd7ceeb37bb1e41cc3d29ac5061d968821d59db` |

The last file and the third are byte-identical. The five-file gate therefore has four distinct
archive byte sequences, not five independent visual reference outputs.

The genuine-file report's group headers explicitly use Pass Through to match `LayerImports`.
Using the default Normal header after exposing the hierarchy would measure a different path.
Reported recomposition differences are diagnostic; they are not a broad pixel-equality gate.
The mask fixture has separate exact coverage/sample checks described in
[the mask correction](PROCREATE_MASK_IMPORT_2026-10-24.md).

## Regression coverage and acceptance

Five new JVM cases cover the native tree's precedence and unique leaves, nested order, native
versus legacy visibility fields, legacy fallback, explicit empty native trees and the existing
nesting-depth rejection.

The added Android case passes a self-authored archive through `ProcreateImport` and
`LayerImports`, with both the flat index and native hierarchy present. It verifies three original
paint layers and two nested folders, native hidden-group state, parent identities, half-opacity
composition, actual save and a fresh repository reopen. It exports PNG through the application and
independently decodes its pixels with Android. Revealing the hidden source ink, undoing and redoing
must retain the folder relationships and exact expected pixels. The input archive, saved document
manifest and exported PNG are retained as device evidence.

These are candidate source checks until the exact publication's full Actions matrix completes.
The current Actions result and [emulator acceptance record](EMULATOR_PARITY_2026-10-24.md) identify
executed results. Neither a reader/writer self-round-trip nor source presence proves successful
opening or equivalent rendering in the native Procreate application.
