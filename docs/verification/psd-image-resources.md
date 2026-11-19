# PSD resource boundaries and color limitations

The image-resource pass is bounded by the resource section's declared end. Pascal names, data
lengths and even-byte padding are checked before reads or skips; malformed metadata cannot borrow
bytes from the following layer or image-data sections. Unknown resources are skipped in place,
including empty and odd-sized payloads. Embedded profile bytes are observed without copying or
interpreting their color tables. Duplicate resolution/profile/alpha-identifier records are rejected
instead of selecting contradictory metadata.

## Transparency evidence and precedence

The [Adobe PSD/PSB specification](https://www.adobe.com/devnet-apps/photoshop/fileformatashtml/)
defines resource framing/padding, the Alpha Identifiers resource (1053), ICC Profile (1039), and
the negative layer-count transparency marker. Its resource table does not explain identifier zero.
The zero-ID fallback is therefore scoped using the independent
[psd-tools transparency implementation](https://github.com/psd-tools/psd-tools/blob/709462086e5454df9e472a8254af38847304c576/src/psd_tools/api/utils.py)
and its [resource parser](https://github.com/psd-tools/psd-tools/blob/709462086e5454df9e472a8254af38847304c576/src/psd_tools/psd/image_resources.py),
plus inspected producer files from that pinned corpus:

1. A negative primary/alternate layer count or matching merged-transparency tag identifies the
   first extra plane. This explicit marker takes precedence over resource identifiers.
2. A layerless composite may identify transparency through a unique zero ID in resource 1053.
   The identifiers describe trailing planes; the resulting index must be outside the base-color
   planes and inside the actual header channel count.
3. A positive primary/alternate layer stack retains the existing saved-selection interpretation.
   Extra channels alone do not imply transparency. Stale identifiers that outnumber actual extra
   channels cannot convert a base-color plane into alpha.

Resource 1053 is read as bounded uint32 identifiers; its enclosing resource length already gives
the payload size. The real fixtures corroborate that there is no additional count word inside the
payload. Partial words, ambiguous repeated zero IDs, conflicting duplicate records and more than
the format's channel limit are rejected before pixel decoding.

## Independently inspected fixtures

The files below were downloaded for a local diagnostic probe from
[`psd-tools` revision `709462086e5454df9e472a8254af38847304c576`](https://github.com/psd-tools/psd-tools/tree/709462086e5454df9e472a8254af38847304c576/tests/psd_files).
They are external reference evidence; the new committed regressions build small PSD/PSB inputs
directly from the format, without depending on a network download.

| File | Observed metadata | SHA-256 |
| --- | --- | --- |
| `gray0.psd` | 8-bit grayscale, negative layer count, zero alpha ID, ICC profile | `f7160dd9dea521cb05e586dd62cff3f7a2429b070b2aba38cdc71b5522e9217c` |
| `transparentbg-gimp.psd` | 8-bit RGB, negative layer count, no alpha-ID resource | `9c700d3e7ad0429ac3ea83a40bb02f364a3d4e7713e7d608e23a8e03014d3b9c` |
| `colormodes/4x4_8bit_rgba.psd` | Positive layer count, auxiliary ID 6, ICC profile; extra plane stays auxiliary | `1f87ed0f6bace7587c0ab3fb48ffe22a7796a42e11743b6a10c42a4d2bd56d0f` |
| `cmyk-alpha-spot.psd` | Layerless CMYK, auxiliary IDs 3/2, ICC profile; two extras stay auxiliary | `3f868555748e87a357890f2f20afa150f5820a460d73d3d0c1d2c52b14ebea0b` |
| `transparentbg.psd` | Layerless 32-bit RGB, zero alpha ID; 32-bit decoding remains unsupported | `d6b3ebbc2928e80d6cb9e53e2bfc935083a2f76def4325547c382c0141fda8bb` |

The diagnostic probe also successfully inspected the pinned `16bit5x5.psd`, `0layers.psd` and
`0layers_tblocks.psd`. A clearly derived fixture removed only `gray0.psd`'s layer-and-mask section,
retaining its original resource and merged-image bytes. Decoding that layerless derivative through
the new zero-ID path produced the same 143,600 composite pixels as the original explicit-marker
path. This is a mutation test of real producer bytes, not a claim that the derivative was saved by
Photoshop. The large CMYK reference was inspected as metadata only, without decoding its rasters.

## User-facing limits

`ReadInfo` reports CMYK input, an embedded ICC resource, the selected transparency plane and the
number of unused auxiliary planes. The import controller includes applicable limitations in its
success message after the atomic repository publication:

- CMYK values use an approximate sRGB conversion.
- Embedded ICC profiles are not applied, so source colors may differ.
- Extra alpha/spot channels are not imported into the editable artwork.
- Alternate layer records that the decoder represents through the merged composite are reported
  as one flattened image, preserving the distinction from imported editable layers.

These limitations apply to both gallery handoff and direct PSD insertion because both paths use
the same controller. A rejected, cancelled or stale-destination import emits no success or color
notice. Existing artwork/profile and the one-step undo boundary retain their existing contract.
This change adds resource-boundary safety and truthful reporting; it does not establish full
Photoshop color, spot-ink, effect, or 32-bit compatibility.

## Verification

The final standalone Kotlin/JUnit run passed all 96 cases across 14 PSD/PSB test classes,
including all 18 channel-semantics cases. These include the new resource cases and the previous
RGB/grayscale/CMYK, PSD/PSB, 8/16-bit and RAW/RLE/ZIP/prediction matrix. Assigned-file ktlint and
detekt passed with the existing configuration and baseline.
Four new real-controller/repository tests cover the success notice, stale destination,
malformed metadata and explicit alternate-layer flattening. Their integrated Gradle result
belongs to the integrator's verification record; source presence alone is not device or release
acceptance.
