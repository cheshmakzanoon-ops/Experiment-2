# Import and saved-asset memory boundaries

## Scope and evidence

This record describes the source policies added by the
[production follow-through](../release/PRODUCTION_FOLLOWTHROUGH_2026-11-19.md).
The wave-eleven checkpoint
[`1e438bb`](https://github.com/cheshmakzanoon-ops/Experiment-2/commit/1e438bbf3db3d29cc178819bab4a0f3cbc6311b7)
contains the startup, grain ownership, font identity and initial Procreate opening changes.
The later ZIP, provider-input, brush/palette encoding and publication-ownership policies below
require qualification on a revision containing those changes. Local compilation, a fixture sanity
check and a declared device test are separate from a passing hosted instrumentation run. Execution
results belong in the follow-through record and the exact-revision Actions artifacts.

The related [selection/history policy](selection-memory.md) documents the combined undo/redo
allowance, saved-selection admission and manifest-publication checks. Photo, model and PSD
preparation retain their [admission and ownership policy](import-admission-and-ownership.md).
The [still-export policy](still-export-memory.md) covers additional export pixels and encoder work.

## Shared allowance

[`ImportMemoryBudget`](../../app/src/main/java/com/artflow/studio/core/pixels/ImportMemoryBudget.kt)
uses the maximum Java heap `H` and an estimate of currently used Java heap `U`:

```text
B = min(H / 3, (H - U) / 2)
```

All quantities below are bytes; integer division rounds down. `MiB` means `1,048,576` bytes.
An operation can receive a smaller injected allowance for testing, and later decoder checks
can further narrow it using current headroom. A format ceiling does not mean that a file fits
every device or the current editor session.

These are admission estimates for identified buffers and retained assets. They do not bound
all process memory: unmodelled object overhead, native decoder allocations, live artwork, rendering,
other operations and platform behavior still require measured device qualification.

## Gallery provider input and cancellation

[`GalleryInputSource`](../../app/src/main/java/com/artflow/studio/presentation/ui/screens/gallery/GalleryInputSource.kt)
admits palette payloads and ambiguous/unlabelled non-native archive copies before opening their
full payload stream. Their encoded limit is `min(64 MiB, min(injectedAllowance, currentB) / 4)`;
the production allowance defaults to `B` and headroom is checked again inside the existing
nonblocking `ImportPreparation` boundary. The quarter-budget limit allows the
growing stream buffer, returned copy and bounded scratch buffer to coexist. Known model imports
use the same formula; brush-only archives retain the brush-specific calculation below. Native
artwork and Procreate providers keep their separate disk-staging path.

Header classification reads at most 64 bytes and remains independent of the full-import admission.
Display-name queries, MIME queries and header reads run on IO, so provider metadata does not run
on the gallery's UI dispatcher. Input checks run around blocking reads and zero-length bulk reads
make progress through a checked single-byte fallback. The gallery's optional-read wrappers retain
ordinary metadata/format fallbacks and rethrow cancellation or fatal failures they receive;
individual format parsers still require their own allocation and error-path checks. Payload IO or
permission failures retain the unreadable-file UI outcome; admission errors retain their useful
memory message. See
[`GalleryImports`](../../app/src/main/java/com/artflow/studio/presentation/ui/screens/gallery/GalleryImports.kt).

These checks cannot force a provider that never returns to release its blocking call. The owning
stream closes when control returns, and cancellation stops subsequent classification, publication
or queue advancement. The [incoming-import lifecycle](incoming-import-lifecycle.md) describes
the sequential collector and pending-artwork ownership.

## Native artwork and Procreate archives

[`StagedImport`](../../app/src/main/java/com/artflow/studio/data/export/StagedImport.kt) and
[`ProcreateImport`](../../app/src/main/java/com/artflow/studio/data/export/ProcreateImport.kt)
copy provider input to an owned file, with a 512 MiB compressed-file ceiling. That is a disk
staging limit. Native imports from the repository's byte-array API also stage the existing
compressed bytes and delegate to the same streamed repository path; they no longer expand
the entire package through the in-memory reader before grain admission.

[`ZipDirectoryPreflight`](../../app/src/main/java/com/artflow/studio/data/export/ZipDirectoryPreflight.kt)
reads a bounded footer and fixed directory headers before constructing the platform `ZipFile`.
It checks ordinary/ZIP64 declarations, actual entry counts and spans. Conflicting declarations
are rejected before the platform can choose a different allocation size. Valid small ZIP64
archives and supported trailing padding remain readable.

Let `C` be central-directory bytes and `N` its entry count. The index estimate is
`I = 8 × C + 512 × N`.

| Boundary | Native `.artflow` | Procreate document |
| --- | --- | --- |
| Directory entries | At most 4,200 | At most 10,000 |
| Index estimate | `I <= min(16 MiB, H / 16)` | `C <= B / 8` and `I <= B / 4` |
| Document metadata | Complete native document at most 32 MiB | Encoded `Document.archive` at most `min(32 MiB, B / 16)` |
| Decoded Procreate metadata | Separate native document validation applies | At most `min(64 MiB, H / 8, B / 4)` |
| Procreate thumbnail entry | Native preview checks remain separate | At most `min(16 MiB, B / 16)` |
| Procreate chunk entry | Native entry and raster checks remain separate | At most `min(64 MiB, B / 16)` |

The native policy is implemented in
[`ArtflowPackage`](../../app/src/main/java/com/artflow/studio/data/export/ArtflowPackage.kt).
Recognition and gallery name inspection use the same admitted ZIP constructor and do not read
payloads. An unlabelled non-native ZIP can receive two bounded index inspections: native
recognition followed by brush-layout classification. Native per-entry, expanded-total,
allowlist, CRC, document and grain checks still apply after directory admission.

[`ProcreateReadBudget`](../../app/src/main/java/com/artflow/studio/core/export/ProcreateReadBudget.kt)
keeps encoded metadata, the complete decoded-metadata allowance, a 1 MiB auxiliary reserve
and the index estimate charged while opening later entries. If their sum is `R`, a raster
plane costs `P = 4 × width × height`, a square tile plane costs `T = 4 × tileSize²`, and the
chunk allowance is `K`, raster admission requires `R + 2P + 2T + 3K <= B` before calibration.
Thumbnail dimensions are inspected before pixel decoding. Its check includes three encoded
copies plus `16 × width × height + 16 × max(width, height) + 1 MiB` of decoder work.
Calibration separately accounts for its retained thumbnail and raster/tile workspace.

[`ProcreateReader`](../../app/src/main/java/com/artflow/studio/core/export/ProcreateReader.kt)
limits the complete hierarchy to 1,024 visited layers and groups, including repeated references,
and at most 32 nested groups. The
[`binary-plist decoder`](../../app/src/main/java/com/artflow/studio/core/export/KeyedArchive.kt)
charges offset tables, strings, data and containers before allocating them. Its default decoded
allowance is `min(64 MiB, H / 8)`, and callers can narrow it. Repeated decoding of aliased objects
consumes the cumulative allowance; a compact shared-reference graph cannot receive a new full
allowance for every reference.

## Brush archives and ABR tips

[`BrushDecodeBudget`](../../app/src/main/java/com/artflow/studio/core/export/BrushDecodeBudget.kt)
starts with a 64 KiB bookkeeping reserve and checks:

| Allocation/work | Admission |
| --- | --- |
| Encoded brush input | At most `min(64 MiB, (B - 64 KiB) / 4)` before stream growth |
| Retained 256 × 256 coverage tiles | At most `min(16 MiB, H / 32, B / 4)`; 64 KiB per tile |
| Archive path/name work | Charge `512 + 8 × characterCount` for each retained path |
| Next expanded entry | At most the format limit and one third of the remaining allowance |
| One decoded settings allowance | `M = min(8 MiB, H / 8, 32 × encodedSettingsBytes + 64 KiB)`; charge `2M` before decoding |
| Next brush image | Its decoder allowance excludes already retained data and six tile-sized working buffers |
| Next ABR tip | Admit raw samples plus the new coverage plane before allocation; keep prior source coverage and requested output tiles charged |

The format caps in
[`BrushArchives`](../../app/src/main/java/com/artflow/studio/data/local/BrushArchives.kt) remain
separate: at most 2,000 archive entries, 100 primary brushes, 201 folders, 64 MiB expanded bytes,
2 MiB per settings entry and 16 MiB per image entry. Device memory can impose smaller limits.
Ignored entries are drained within the expanded-input allowance too.

[`PaddedBrushTip`](../../app/src/main/java/com/artflow/studio/data/local/PaddedBrushTip.kt)
samples the centered source rectangle directly into the fixed tile. A long narrow tip no longer
requires a full square `IntArray` containing mostly padding. The integer/fractional box filter
and coverage rounding retain the previous appearance. The ABR reader refuses excess tips
instead of silently returning only the first permitted tips.

Production image imports use
[`BitmapPixelBridge.decodeBrushImage`](../../app/src/main/java/com/artflow/studio/data/renderer/BitmapPixelBridge.kt),
which checks dimensions and admits exact geometry before decoding pixels. It does not silently
resize a brush image to make it fit. The legacy caller-supplied decoder API remains available
for trusted callers, including metadata-only reference checks; it cannot bound allocations made
inside an arbitrary external callback.

Ordinarily damaged optional Procreate brush settings can retain the existing fallback behavior.
The typed plist allocation-limit failure propagates, so memory refusal does not silently remove
settings from an otherwise imported brush. Unambiguous brush-only ZIP layouts go directly to
the importer and are decoded once. Recognized palette/model members preserve their own route,
even if another entry is named `Shape.png` or `Grain.png`.

## ArtFlow brush JSON and saved libraries

[`JsonPreflight`](../../app/src/main/java/com/artflow/studio/core/serialization/JsonPreflight.kt)
scans text before Kotlin serialization constructs its object collections. It validates JSON
structure and escapes, bounds nesting and token work, and rejects duplicate decoded keys
(including differently escaped spellings of the same key). It copies only bounded object keys;
it does not materialize value strings, numeric arrays or the complete JSON tree during the scan.
Unicode escape digits must be ASCII hexadecimal characters; Unicode lookalikes are rejected.

| Boundary | Limit/accounting |
| --- | --- |
| JSON container nesting | 16 levels |
| Decoded key or number length | 128 characters |
| Default lexical work | At most 524,288 tokens per scan; reserve 128 bytes per token |
| Saved-library lexical work | At most 3,145,728 tokens, derived from its existing character ceiling; the same 128-byte token reservation applies |
| Text held during a scan | Reserve two bytes per UTF-16 character |
| Saved library | At most 128 brushes and 3,145,728 UTF-16 characters |
| Source scalar map | At most 256 entries; value strings at most 256 characters |
| Source structured metadata | At most 19 objects, with up to eight named fields or 16 array items per object |
| Auxiliary curves / pressure points | At most 35 auxiliary curves and 16 points per applicable array |
| Shared `.artbrush` file | At most 4 MiB of UTF-8 input, with an accompanying character-length check |
| Declared tile map | At most 512 entries before deserialization |

The defaults also bound other object/array collections; format-specific rules in
[`BrushLibraryCodec`](../../app/src/main/java/com/artflow/studio/domain/model/brush/BrushLibraryCodec.kt)
and [`BrushFiles`](../../app/src/main/java/com/artflow/studio/data/local/BrushFiles.kt) select the
appropriate per-property ceilings. Validation of the supported version, brush identities,
names, parameter ranges and finite dual-brush structure remains in place after deserialization.
The outer brush file and its nested library share one `JsonReadBudget`; decoding the nested
string does not reset the operation's allowance.

Saved-library scans use the established character ceiling as their work bound: each charged
value or object-key token starts at a distinct code unit. Dense ordinary and dual-brush libraries
from earlier writers can exceed the generic 524,288-token limit while still fitting their existing
character and field limits. The format-specific bound keeps those libraries readable when their
text and every token fit the device's allowance. Generic JSON scans and palette writer admission
retain their original 524,288-token ceiling. No token bypasses the pre-deserialization memory charge.

Only tiles referenced by the decoded brushes are materialized. Every referenced custom identity
and encoded tile is checked before the first tile decoder call. Each tile must have the canonical
87,384-character representation of 65,536 coverage bytes, including its alphabet, two padding
characters and zero unused padding bits. Retained tile output must fit
`min(16 MiB, H / 32, B / 4)`. The budget also reserves one decoder's encoded
input and possible result copy. Unused tile declarations still consume bounded JSON/map work,
but do not allocate coverage arrays or trigger tile decoding.

Brush-file export first captures one bounded list of brush references for both library metadata
and image-reference collection. It checks the reported count before copying and the actual count
during iteration, reserving 128 bytes for the list and 32 bytes for each retained reference in the
brush-file budget. Replacing a caller's list entry between serialization and texture collection
therefore cannot mix one brush's metadata with another brush's tiles. The deeper saved-library
snapshot described below separately owns the serializer's nested collections.

Export validates the library before traversing image references, checks the same tile allowance,
and estimates UTF-8 plus JSON escaping before producing Base64/file output.
It reserves eight times the estimated output bytes for encoding workspace and verifies the
final 4 MiB byte ceiling. Sharing an oversized set fails with guidance to share fewer brushes.
Saved-library encoding first takes an owned, admitted snapshot of every nested collection through
[`BrushEncodingSnapshot`](../../app/src/main/java/com/artflow/studio/domain/model/brush/BrushEncodingSnapshot.kt).
[`BoundedJsonOutput`](../../app/src/main/java/com/artflow/studio/core/serialization/BoundedJsonOutput.kt)
then counts a streaming serialization without retaining its output, before allocating the complete
string from that stable snapshot. If `L = 3,145,728` characters and `S` is the snapshot's reserved
storage, the final UTF-16 character ceiling is `min(L, floor((B - S - 64 KiB) / 12))`.
The allowance covers serializer growth/pooling, original and final text, escape workspace and
the writer's fixed reserve. Isolated UTF-16 surrogates become legal JSON escapes before UTF-8
conversion, preserving accepted names and metadata, including a name truncated between an
emoji's two code units. Valid pairs remain unchanged. Escape expansion is admitted before its
buffer allocation. The resulting string must still meet `L` and the structural/token admission
using the same operation budget. A library satisfying each per-brush field limit can still exceed
the aggregate text or memory ceiling. Unsupported collections are rejected without replacing the
prior durable library.

## Procreate `.swatches` palettes

[`ProcreateSwatches`](../../app/src/main/java/com/artflow/studio/core/color/ProcreateSwatches.kt)
admits every visited ZIP member, including ignored files before or after `Swatches.json`.
Ignored members are drained explicitly through the same counter; advancing to the next member
cannot perform unbounded hidden inflation. Duplicate `Swatches.json` basenames are refused,
while a single metadata member inside a folder remains supported.

| Boundary | Limit/accounting |
| --- | --- |
| Encoded archive | 2 MiB |
| Selected JSON member | 4 MiB inflated |
| All visited members combined | 8 MiB inflated; stop after at most one excess byte |
| Archive entries / decoded member name | 16 entries / 4,096 characters |
| Palette array / slots per palette | 64 palettes / 65,536 slots, including empty or unreadable slots |
| Value strings / object entries | 4,096 characters / 256 entries |
| JSON depth, duplicate keys and lexical work | The shared `JsonReadBudget` scanner's limits above |

The operation reserves archive bytes and 256 KiB of ZIP workspace before opening the archive.
Selected bytes reserve three copies' worth of growth/output storage; text and JSON tokens are
admitted before a DOM parser runs. Collection limits therefore refuse oversized documents
before their arrays or maps exist. These individual ceilings remain subject to the shared
heap-aware operation allowance. Null slots, ordinary malformed swatches, finite-number
tolerance, hue wrapping, folder names and short unknown metadata retain their established
behavior. Ordinary damaged or unrecognized input returns no palettes.

Gallery decoding passes the caller's cancellation check through ZIP reads and palette
conversion. Cancellation and fatal failures propagate. A recognized palette's JSON resource
refusal reports guidance to use fewer colours or a smaller palette. During an optional archive
probe, a palette-specific refusal before finding `Swatches.json` yields no palette so a valid
brush or model can continue through its own format allowance. The bounded probe does not
grant an unbounded retry.

Export validates the colour count, 80-character name and finite timestamp before traversing
colours. It reserves the exact document token count using the shared scanner's ceiling, then
streams one swatch at a time through bounded JSON and ZIP outputs. It retains thirty-slot
padding and HSB quantization while refusing outputs that exceed the reader's byte or work
limits. The budget includes the later reader's expanded buffers, text and DOM estimate.
Palette names use the same lossless surrogate escaping before UTF-8 output, under a maximum
482-character escaped representation for the accepted 80 UTF-16-unit name and JSON quotes.

## Retained grains and publication ownership

[`GrainRegistry`](../../app/src/main/java/com/artflow/studio/core/render/GrainRegistry.kt) enforces
two independent allowances:

| Retention | Limit |
| --- | --- |
| Installed coverage arrays plus outstanding retained-capacity obligations | `min(64 MiB, H / 8)` |
| Concurrent incoming coverage buffers | `min(32 MiB, H / 16)` |

Incoming imports reserve two coverage-buffer copies for their complete grain batch. Matching IDs
share the retained obligation at the largest promised size, while each active import pays its
own buffer charge. A failed reservation does not replace existing entries. There is no eviction:
removing a texture from the renderer's registry can change brush appearance.

[`GrainStorage`](../../app/src/main/java/com/artflow/studio/data/local/GrainStorage.kt) reserves
before startup reads, save writes and imports. Exact-size reads prevent a file growing after
the size check from expanding an admitted read. Native package imports reserve the complete
grain batch before reading grain payloads or creating the destination project files.

[`BrushImports`](../../app/src/main/java/com/artflow/studio/data/local/BrushImports.kt) parses,
remaps and validates a complete brush batch before installing its first asset. Its transaction
owner exists outside cancellable dispatcher delivery. Gallery and Brush Studio await
[`BrushLibraryStore`](../../app/src/main/java/com/artflow/studio/data/repository/BrushLibraryStore.kt),
which retains the grain ownership immediately after the durable database write inside a narrow
non-cancellable publication block. Failure or cancellation before commit removes the import's
provisional assets; cancellation after an entered successful commit preserves the rows and assets.
Rollback preserves shared assets and independently saved replacements.

Native repository imports apply the same ownership distinction through
[`ProjectRepositoryImpl`](../../app/src/main/java/com/artflow/studio/data/repository/ProjectRepositoryImpl.kt).
For native import and duplication, storage synchronously records successful directory creation
inside its own rollback scope. Only that callback establishes the repository's destination cleanup
ownership. A reserved ID or earlier absence check does not: a competing directory that appears
before storage's creation check is refused and left intact. A callback failure triggers storage's
rollback path for the operation's newly created directory.

Cleanup checks whether Room committed before removing an owned project's files or grain claims.
If that status cannot be read, it preserves assets that may belong to a committed artwork. Repository
import, duplication and staged-input cleanup preserve the original error or cancellation and attach
subsequent cleanup errors as suppressed. Repository diagnostics also survive coroutine recovery
copies of the primary exception. Cleanup failure after otherwise successful work still surfaces.
Later project allocation avoids reusing retained directory IDs.

## Startup readiness and fonts

[`BrushAssetStartup`](../../app/src/main/java/com/artflow/studio/data/local/BrushAssetStartup.kt)
owns IO initialization for the process. Application startup and Activity visits call the same
idempotent initializer. The gallery/editor and their import/navigation effects are composed only
after loading succeeds or the artist explicitly chooses **Open with available brushes**.
Failure and cancellation do not automatically mark assets ready. Retry is available after a
failed attempt; Activity recreation neither duplicates the work nor silently retries it.

Strict startup scans the available files, retains other readable/admissible tiles, and reports
load or capacity failures. The explicit continuation screen explains that brushes with missing
shapes or textures may paint differently. It does not delete their stored files or certify that
every brush has its original appearance.

Native packages retain raster pixels and font dependency metadata without bundling imported font
binaries. A dependency can carry an optional lowercase SHA-256 fingerprint. Import notices
distinguish a missing font, a different local file and an unverifiable identity, including older
packages without a source digest. A same-name local font cannot establish the missing source
identity. Editing text still requires the appropriate font to preserve its typography.

[`FontLibrary.FingerprintSession`](../../app/src/main/java/com/artflow/studio/data/local/FontLibrary.kt)
validates each original reference before consulting its per-operation file cache. It only hashes
readable, unlinked app-managed files of at most 20 MiB; repeated aliases read the same file once
per import/export operation. Failed reads remain unknown within that operation, and a later
operation can observe changed contents. These identities cover managed imported font files;
they do not certify identical platform-font rendering across devices.

Native dependency manifests accept at most 1,024 font declarations and 4,200 grain declarations,
counting duplicates before grouping or font IO. Actual artwork references drive font/grain
notices; unused declarations do not trigger local file reads. Conflicting raw or normalized font
fingerprints are rejected even when their declarations are unused. See
[`ProjectStorage`](../../app/src/main/java/com/artflow/studio/data/local/ProjectStorage.kt) for the
reference audit and raster-preserving outcome.

## Regression coverage and remaining qualification

| Boundary | Source regressions |
| --- | --- |
| Procreate metadata, hierarchy and opening work | [BinaryPlistBudgetTest](../../app/src/test/java/com/artflow/studio/core/export/BinaryPlistBudgetTest.kt), [ProcreateNodeBudgetTest](../../app/src/test/java/com/artflow/studio/core/export/ProcreateNodeBudgetTest.kt), [ProcreateOpeningBudgetTest](../../app/src/test/java/com/artflow/studio/core/export/ProcreateOpeningBudgetTest.kt) |
| ZIP construction and archive lifetime | [ArtflowZipAdmissionTest](../../app/src/test/java/com/artflow/studio/data/export/ArtflowZipAdmissionTest.kt), [ProcreateZipIndexTest](../../app/src/test/java/com/artflow/studio/data/export/ProcreateZipIndexTest.kt), [ProcreateOpeningOwnershipTest](../../app/src/test/java/com/artflow/studio/data/export/ProcreateOpeningOwnershipTest.kt) |
| Early ABR/archive decoding and sampling parity | [BrushDecodeAdmissionTest](../../app/src/test/java/com/artflow/studio/data/local/BrushDecodeAdmissionTest.kt) |
| Brush JSON structure, shared allowance, UTF-8 output, legacy dense libraries and referenced tiles | [JsonPreflightTest](../../app/src/test/java/com/artflow/studio/core/serialization/JsonPreflightTest.kt), [BrushJsonAdmissionTest](../../app/src/test/java/com/artflow/studio/data/local/BrushJsonAdmissionTest.kt), [LegacyBrushLibraryAdmissionTest](../../app/src/test/java/com/artflow/studio/domain/model/brush/LegacyBrushLibraryAdmissionTest.kt) |
| Lossless text and consistent encoding snapshots | [BoundedJsonOutputTest](../../app/src/test/java/com/artflow/studio/core/serialization/BoundedJsonOutputTest.kt), [BrushEncodingSnapshotTest](../../app/src/test/java/com/artflow/studio/domain/model/brush/BrushEncodingSnapshotTest.kt), [BrushTextPreservationTest](../../app/src/test/java/com/artflow/studio/data/local/BrushTextPreservationTest.kt), [BrushFileSnapshotTest](../../app/src/test/java/com/artflow/studio/data/local/BrushFileSnapshotTest.kt) |
| Palette ZIP expansion, pre-DOM limits, mixed-format probes and streaming output | [ProcreateSwatchesBudgetTest](../../app/src/test/java/com/artflow/studio/core/color/ProcreateSwatchesBudgetTest.kt), [ProcreateSwatchesJsonAdmissionTest](../../app/src/test/java/com/artflow/studio/core/color/ProcreateSwatchesJsonAdmissionTest.kt), [ProcreateSwatchesWriteBudgetTest](../../app/src/test/java/com/artflow/studio/core/color/ProcreateSwatchesWriteBudgetTest.kt) |
| Gallery input growth, progress and cancellation | [GalleryInputSourceTest](../../app/src/test/java/com/artflow/studio/presentation/ui/screens/gallery/GalleryInputSourceTest.kt), [GalleryIncomingImportTest](../../app/src/test/java/com/artflow/studio/presentation/ui/screens/gallery/GalleryIncomingImportTest.kt) |
| Retention, shared ownership and publication rollback | [GrainRegistryTest](../../app/src/test/java/com/artflow/studio/core/render/GrainRegistryTest.kt), [GrainSharedOwnershipTest](../../app/src/test/java/com/artflow/studio/data/local/GrainSharedOwnershipTest.kt), [BrushImportTransactionTest](../../app/src/test/java/com/artflow/studio/data/local/BrushImportTransactionTest.kt), [ByteArrayPackageImportTest](../../app/src/test/java/com/artflow/studio/data/repository/ByteArrayPackageImportTest.kt) |
| Created-directory ownership and cleanup double faults | [ProjectDestinationOwnershipTest](../../app/src/test/java/com/artflow/studio/data/repository/ProjectDestinationOwnershipTest.kt), [ProjectPublicationCleanupTest](../../app/src/test/java/com/artflow/studio/data/repository/ProjectPublicationCleanupTest.kt) |
| Startup state and stored-file loading | [AssetInitializationTest](../../app/src/test/java/com/artflow/studio/core/lifecycle/AssetInitializationTest.kt), [GrainStartupReadTest](../../app/src/test/java/com/artflow/studio/data/local/GrainStartupReadTest.kt), [BrushAssetStartupUiTest](../../app/src/androidTest/java/com/artflow/studio/BrushAssetStartupUiTest.kt) |
| Font identity and per-file work | [FontDependencyIdentityTest](../../app/src/test/java/com/artflow/studio/data/local/FontDependencyIdentityTest.kt), [FontFingerprintSessionTest](../../app/src/test/java/com/artflow/studio/data/local/FontFingerprintSessionTest.kt) |
| Actual Android ZIP/bitmap decoding, Room rollback and gallery routing | [ImportOpeningDeviceTest](../../app/src/androidTest/java/com/artflow/studio/data/ImportOpeningDeviceTest.kt), [ProjectGrainImportRollbackDeviceTest](../../app/src/androidTest/java/com/artflow/studio/data/ProjectGrainImportRollbackDeviceTest.kt), [GalleryPackageImportDeviceTest](../../app/src/androidTest/java/com/artflow/studio/data/GalleryPackageImportDeviceTest.kt) |

The optimized inventory is derived from the checked-out source by
[`release_qualification.py`](../../.github/scripts/release_qualification.py). A new source case
requires its own recorded execution; a fixture decoded with desktop ImageIO does not qualify
Android bitmap behavior. The complete debug matrix, optimized selected inventory, installed
upgrade and normal release UI workflows remain required on the final source revision.

The [release sign-off](../release/RELEASE_SIGNOFF.md) also retains repeated-workflow requirements
and separate publisher, physical-device/stylus and store evidence. Physical measurements must
cover peak memory, actual drawing behavior, startup/import failure recovery and font/grain
preservation. An installed-upgrade seed that did not import custom fonts or grains does not
establish their later usability. No memory formula or emulator run substitutes for those checks.
