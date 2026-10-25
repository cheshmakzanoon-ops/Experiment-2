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

### Published revision and Actions build

[Run 320](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40151899389) tests the
published correction at `09940e4e03f99b61c07f2922a7229d4faee40c7b`. Its source artifact
`12583011748` is 2,114,337 bytes, SHA-256
`ac3b25e981cbe77ac1fc65b555b1ea869cadcc4c44405be4a261909a4ad5d857`.
All 928 source files match the commit byte-for-byte; the immutable source declares exactly 266
device identities with no earlier identities removed. The later OBJ material-import work is not
part of this source.

Build job `121044979633` passes. The original report artifact `12582957949` is 798,177 bytes,
SHA-256 `e66e159b589e60904cc44fb9d84d8e2117a4217e885c3bfc4143fafe81b6dc4e`.
Its raw JVM XML contains **1,585 unique passes across 263 suites**, with no failures, errors or
skips. The job also passes **190 Python checks** (114 CI-script and 76 reference-tool tests), the
68 native-document instruction anchors, the signing rejection guard, pinned reference/corpus
checks, static analysis, debug/release lint, APK/bundle packaging and native-alignment checks.
Lint remains at 154 debug and 152 release warnings, plus five hints per variant and no errors.

### Final device result

**Run 320 fails the required matrix.** Six configurations pass their complete inventories and
minified launches on their first attempts. The main API 35 / 16 KB job crashes twice across its
original attempt and single permitted recovery. The passing independent repeat does not replace
that failed lane.

| Configuration | Passed | Failed | Allowed skips | Missing | Minified launch | New mask case |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| API 26 / 4 KB | 264 | 0 | 2 | 0 | Pass | Pass |
| API 35 / 16 KB main | 1 | 1 | 0 | 264 | Not reached | Not reached |
| API 35 / 4 KB | 266 | 0 | 0 | 0 | Pass | Pass |
| API 35 / 16 KB independent repeat | 266 | 0 | 0 | 0 | Pass | Pass |
| API 36 / 4 KB | 266 | 0 | 0 | 0 | Pass | Pass |
| API 36 / 16 KB | 266 | 0 | 0 | 0 | Pass | Pass |
| API 36 tablet / 4 KB | 266 | 0 | 0 | 0 | Pass | Pass |

Final totals are **1,595 passes, one failure, two permitted skips and 264 missing identities**, with
no error or unexpected identities. The two API 26 skips retain their original API 33 pointer-flag
and API 29 scoped MediaStore gates. Raw XML, source inventory, runtime measurements and published
summaries agree. Both main-lane crash tombstones independently report 16,384-byte pages; that lane
does not reach the later runtime-evidence test.

Independent plist, bounded LZ4, raw-buffer, PNG and saved-manifest inspection passes on all six
complete lanes. Across their 12 enabled/disabled native archives, masks declare integer type 3,
retain the correct hidden state and contain exactly 65,536 coverage bytes. Canvas coverage has
5,376 zero, 5,632 half-coverage and 5,376 full-coverage pixels; all 49,152 off-canvas bytes remain 255.
Underlying ink and archived composites match independent expectations. Each lane's four fresh-reopen
and toggle PNGs match all 16,384 pixels exactly, and root/frame mask-enabled values persist correctly.
All 494 extracted files in each passing artifact match the original ZIP members. The main lane
produces no native-mask archive and does not execute the new case in either attempt.

### Preserved runtime failures and exact installed binaries

The original main-lane attempt has **153 passes, one empty failure and 112 missing identities**.
`StudioWorkspaceUiTest.smudgeSidebarUsesItsOwnOpacityAndLeavesPaintUnchanged` starts at
02:08:33.482 UTC. App PID 4577 / HeapTaskDaemon TID 4589 receives SIGSEGV at 02:08:33.828,
fault address `0xc`, with top frame `MarkCompact::ScanObject<true>+36` (`libart.so` PC `0x641f94`).
The strict existing classifier permits one verified reboot.

The final attempt has the one-pass result shown in the table. The failing playback case starts at
02:10:01.441; PID 3804 / DefaultDispatch TID 4191 receives SIGSEGV at 02:10:01.838, address `0x10`,
with top frame `interpreter::DoCall<false>+183` (PC `0x6e8007`). Its first managed frame is
`ProjectStorage.maximumStoredProjectId`, followed by project creation. That identifies code in use,
not the cause of the invalid native state. The same playback case passed in the original attempt.
Both crashes name ART Build ID `e4a3c6c284a8b17d09fd84d661cf181b`; neither contains an application
assertion body or a `VerifyError`. The second failure remains failed after the recovery limit is used.

The pre-test capture now retains the actual app/test APKs from both failed processes:

| Package | Bytes | SHA-256 |
| --- | ---: | --- |
| App | 24,348,791 | `c395122ccc9b8d54f6da63c4675fa24d52a6795d39347a31fc5bba2becd569a6` |
| Test | 1,792,435 | `1498f713681c8c9d96ca43e2feee3bfee55b2b8cffe9bf44a79ab58868f9f684` |

Each package is byte-for-byte identical across the two failed attempts. All eight capture tokens,
process IDs/times and runner/host handoffs verify without errors. All 18 app and seven test DEX
names, lengths and hashes match across the seven lanes, including the passing independent 16 KB
repeat. The retained failed APKs additionally pass ZIP CRC/no-duplicate-entry checks and all 25 DEX
header-size, SHA-1, Adler32 and SDK checksum checks. Targeted disassembly finds one application
declaration each for the named storage/coroutine classes, with none duplicated in the test APK.
These structural checks do not execute ART verification or inspect the crashing process's heap,
class-loader state or generated code. Passing-lane byte comparisons use preserved capture metadata;
successful APK payloads are removed as designed. Both failed attempts retain their app and test files.

A bounded graphics-path follow-up verifies the installed version and native bytes as AndroidX
1.1.0. Its API 35 traversal uses the platform iterator, while native loading/conic conversion still
exist. The first attempt's explicit compatibility load passes; the second attempt's retained log
has no graphics-path load/test occurrence before its early crash. The inspected official fixes
provide no matching repair. Neither loading/alignment checks nor log absence proves general native
memory safety. Dependencies, runtime flags, assertions and recovery rules remain unchanged.
The earlier run 318 `VerifyError` still lacks its original failed APK and cannot be diagnosed by
substituting this newer binary. The runtime cause and repair remain unresolved.

### Original device artifacts

Every original ZIP was checked against the Actions byte count and digest before extraction.
Logs, installed APKs and independent analysis outputs remain outside the source repository.

| Artifact suffix | Artifact ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| `api-26` | 12582704886 | 3,430,939 | `137df6fd7e28901545235d882eaf020ba0d6607aeabf3428154abdf5e6bb6ef0` |
| `api-35` | 12583845072 | 56,883,582 | `f80849f50e7f788710f5c85a56c0d14742c925fadd614a448c440b8d7f3015a4` |
| `api-35-standard-4k` | 12582679973 | 8,813,463 | `c3b4a410ca6877c7b6872d43344b55bd03d8a0c04fbcceaad1bcb8064e40efbc` |
| `api-36` | 12583711603 | 8,758,560 | `6a0d1315530f221ad9c608204f6777d203cdbda82cb12ffc0363c648c9d4b754` |
| `api-35-16k-repeat` | 12583508237 | 11,055,095 | `6f49be10cedfbaf7ec75b1687710ab7b3a47807b22f34ec88a90856d38ccd270` |
| `api-36-16k` | 12583567174 | 8,976,828 | `f2ae3e4459ba63c036e1f802eb085221f5df40730391948b5336bbac5f94fd5c` |
| `api-36-tablet` | 12583284916 | 12,128,690 | `82dc14fb4ca4fc57eeeb989c4ee729368c99ea7b38d1e940e44ab368a4d8d0b0` |

### Later revision: run 322

[Run 322](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40155809360), on
`185115f32090c034fe78bc3d2eac214ff0eca06f`, independently passes the same native-mask
device identity and raw archive/PNG/state audit on five complete configurations: API 26, API 35
standard 4 KB, API 36 standard 4 KB, API 36 16 KB and API 36 tablet. Both Android 15 16 KB
configurations stop at separate ART SIGSEGVs before this case executes; the main has 266 missing
cases on its final attempt, and the independent repeat has 185. Consequently this newer revision
does **not** have a complete seven-lane acceptance result, despite the five positive archives.

This correction concerns ordinary layer-mask representation and typed tile disambiguation. It does
not establish native group-mask metadata, every legacy format version, native-app opening, native
brush/rendering equivalence, or full Procreate feature and quality parity.
