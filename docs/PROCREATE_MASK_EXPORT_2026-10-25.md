# Native layer-mask export and typed edge tiles

## Confirmed representation mismatch

When given a mask, the previous low-level `.procreate` writer attached an object without a native
`type` field and wrote four-channel opaque-gray mask tiles. ArtFlow's reader understood that representation, so an
internal writer/reader round trip did not expose the native mismatch.

The actual editor export path had an additional problem: its snapshot preserved independent masks
only for PSD, and the native export adapter never passed a mask to the writer. Enabled masks were
baked into paint, losing concealed pixels and independent editability in the exported layer. Both
integration points therefore need correction alongside the codec.

The genuine `Mask_Test_File.procreate` from the pinned
[silicate corpus](https://github.com/Avarel/silicate/tree/0e12deb2ae1ffb152fc4a3d09add230590f52f68/demo_files)
declares its mask as type **3**, layer version 4. Its 14 stored mask tiles each have 65,536 decoded
bytes for a 256 × 256 tile: one byte of coverage per pixel. The exact sample is 232,281 bytes with
SHA-256 `17cd313a1b56950738de392595d33045c423469ff9385b5a63efb6e3f37aa83c`; its mask UUID is
`A925F0EE-1C42-48B2-8424-F7E1526D27D6`. Its paint and saved-composite tiles are four-channel.
The existing [mask import record](PROCREATE_MASK_IMPORT_2026-10-24.md) documents the independently
checked coverage and saved-native-composite pixels.

Fresh read-only inspection of the pinned reference executable establishes why the type matters.
The IPA SHA-256 is `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`;
the 19,119,424-byte `Payload/Procreate.app/Procreate` executable has SHA-256
`76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b`.
No reference executable instructions are run.

| Native path | Observed contract |
| --- | --- |
| `ValkyrieLayer.initWithCoder:` at `0x100b2c274` | Decodes `type` through `decodeIntegerForKey:` and stores it at `self+0xa0`. |
| `ValkyrieLayer.type` at `0x100b35268` | Reads the same field used by mask selection and backing allocation. |
| Owner's mask decode at `0x100b2c378`–`0x100b2c3a4` | Normalizes legacy mask type 2 to type 3; omitted/default type 0 is not normalized here. |
| Backing helper at `0x100b29dc4` | Types 0/1 select format 0 with four channels; types 2/3 select format 0 with one channel. |
| `reloadWithAllocator:` at the calls `0x100b2fdac`–`0x100b2fe6c` | Passes that type-derived channel count into the actual backing allocation call. |
| `maskChannel` at `0x100b2bca4` | Type 3 selects channel 0; default type 0 selects channel 3. |
| `initialiseBacking:layerType:` at `0x100b2fb1c` | Type 3 takes the full-coverage initialization branch. |
| `encodeWithCoder:` at `0x100b2c894` | Reads and archives the layer's `type`. |
| Layer `hidden` decode/encode and `isHidden` getter | The archived boolean and getter share `self+0x24`. |
| `ValkyrieComposite.buildActionsWithQuads:predictedQuads:` at `0x100a58ca4` | Reads the attached mask's `isHidden`; the hidden branch bypasses mask texture binding. |

Omitting the type therefore does not describe the recovered one-channel mask backing. This is a
specific archive-contract mismatch; it does not prove that an exported file has been opened in
the native application.

The sample's version 4 is not copied into the writer opportunistically. A separate static review
traces the existing default-version path: missing legacy whole-layer blobs fall through to tile
directories, and the type-3 load flag selects 255 as the blank/default byte. The checked operation
uploads nonblank decoded coverage unchanged; its parameter name `inverted` does not imply a
complement of those bytes. The newer version path consults tile revision/index metadata that this
writer does not emit. This review establishes no need to change the version field and is not a
substitute for opening the exported file in the native application.

## Coordinated writer and reader correction

The bounded correction writes mask type 3 and padded single-channel coverage tiles. Coverage 0
conceals artwork, 255 reveals it, and intermediate values remain unchanged. Off-canvas padding is
255. Entirely revealed tiles are omitted, matching the recovered full-coverage default. Ordinary
paint and saved-composite tiles retain their existing RGBA representation.

Import must change at the same time. On a 128 × 128 canvas, a padded 256 × 256 one-byte mask has
65,536 bytes—the same length as a cropped 128 × 128 RGBA tile. Byte length alone cannot select
the correct representation. The reader carries the declared mask type into layout selection:
types 2/3 establish one-channel precedence in this ambiguous case. Historical masks without that
type retain their previous RGBA precedence and alpha-aware coverage behavior. Unambiguous legacy
RGBA inputs remain readable.

The inspected native rendering path also supports disabled masks through the attached mask's
`hidden` field. Export, import and repository insertion preserve that state together with its
coverage. Separately resizing active paint and masks can reveal concealed pixels; those exports
keep a correctly rendered visible appearance and hidden editable sources. Disabled masks do not
trigger that appearance fallback. Layer effects that run after masking retain their existing baked
mask behavior; unsupported group-mask and group-blend semantics retain the rendered fallback.

The old ambiguous-length regression used a shared fixture helper that labeled even its deliberately
legacy RGBA data as type 3. Its legacy byte content and coverage assertions remain; the fixture now
states the missing-type assumption explicitly. A separate typed-native case covers the opposite
interpretation. Persisted `.artflow` masks remain coverage rasters and need no version migration.

### Editor export behavior

| Case | Exported behavior |
| --- | --- |
| Ordinary enabled layer mask | Concealed paint remains in the layer; a separate type-3 mask supplies coverage. |
| Ordinary disabled layer mask | Coverage remains editable and the mask is hidden; paint appearance stays unmasked. |
| Selection crop | Selection coverage clips paint alpha once; the independent layer mask is cropped to the same rectangle. |
| Resampled enabled mask | A rendered appearance is visible; separately resized paint and mask remain hidden editable sources. |
| Resampled disabled mask | Paint and coverage resize independently; the mask stays disabled without an extra appearance layer. |
| Layer with effects after masking | Existing rendered pixels retain the effect; the mask remains baked/omitted and the export warns that it is not independently editable. |
| Group mask | Native group-mask metadata remains unsupported. An active group mask uses the existing rendered appearance fallback; a disabled group mask leaves the visible pixels unchanged. Omission of either mask is disclosed. |

## Acceptance and limits

The complete reference-tool suite passes **76 Python tests**, including 13 new synthetic native
contract tests that require neither Capstone nor a reference IPA. The live hash-pinned analyzer
passes **68 instruction anchors**, retaining the existing document/profile checks and adding the
mask type, channel-count, archive state and ordinary mask-binding checks. It still explicitly reports
that native code was not executed and an exported file was not opened in the reference app.

The unchanged-code JVM regression run executes 13 mask cases and records four failures: missing
mask type, four-channel mask size instead of one-channel size, and incorrect typed ambiguous tile
coverage. Its original XML is retained outside the repository. The corrected complete JVM suite
passes **1,585 tests across 263 suites**, with no failures, errors or skips, including 16 new mask
codec, import and export-integration cases. The full local gate completes in 12 minutes 24 seconds:
Android-test compilation, ktlint, Detekt, debug and release lint, debug/test APK assembly, the release
bundle, and package integrity/native-alignment checks pass. Lint has no errors; its existing counts
are 154 warnings and five hints for debug, and 152 warnings and five hints for release. The new
device method's compiled Java signature is public and returns void.

The new Android case exercises actual export, independent plist and Apple/LZ4 tile inspection,
import, save, a fresh repository reopen, enabled/disabled state, mask toggling and independently
decoded PNG output. Both enabled and disabled archives, raw coverage, metadata, expected images,
reopened/toggled images and saved documents are retained. The candidate inventory has **266 device
identities**, retaining all 265 from run 319 and adding this one case. Compilation alone does not
establish that this device case has executed.

This correction concerns ordinary layer-mask representation and typed tile disambiguation. It does
not establish native group-mask metadata, every legacy format version, native-app opening, native
brush/rendering equivalence, or full Procreate feature and quality parity.
