# Native-informed parity implementation — 21 October 2026

This work starts from `adf0bc7208974f1579095ea5a890b7c1375f02f9` on `main` and uses the
user-selected IPA as a static reference. It closes concrete brush, animation and layered PSD
gaps. **Full Procreate parity remains unestablished.** The implementation and evidence below
must not be read as a reconstructed native renderer or physical-device acceptance.

## Reference evidence

The supplied archive reports Procreate **5.4.4 / build 56832**. Its SHA-256 is
`7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`.
The main ARM64 executable has `cryptid=0`; the source was inspected directly without an
encryption bypass. These observations identify the supplied bytes, not their official
distribution or signing authenticity.

[Native contract evidence](REA_NATIVE_CONTRACTS_2026-10-21.md) and its
[machine-readable record](REA_NATIVE_CONTRACTS_2026-10-21.json) contain addresses, hashes,
method/property correspondences and reproduction instructions. REA artifact inspection
succeeded. Its Mach-O provider required macOS, so the targeted native checks were reproduced
by an independent standard-library parser. No original executable, paid brush texture or
raw disassembly is committed.

The bounded verifier follows real archive keys through ARM64 float stores to the named
native getters. It also verifies the two pressure-curve indices and the independent PSD
mask-channel contract. Native animation declarations establish a feature surface; they
do not recover frame scheduling or blending equations.

## Brush behavior and interchange

### Correct native pressure channels

| Source archive field | Correct target |
| --- | --- |
| `dynamicsPressureOpacity` | Pressure → Flow |
| `dynamicsPressureOpacityTransfer` | Pressure → Opacity |
| `dynamicsPressureOpacityCurve` | Independent flow response |
| `dynamicsPressureTransferModulationCurve` | Independent opacity response |
| `dynamicsMix` | Wet dilution |
| `dynamicsPressureMix` | Wet attack |
| `dynamicsWetAccumulation` | Wet pull; legacy smudge accumulation is a missing-key fallback |

The old importer inferred channels from misleading serialized names. The importer, writer,
unmodified-source preservation logic and reference-corpus assertions now use the recovered
mapping. Pressure flow accepts signed values and reaches the actual paint/erase raster paths,
including live strokes and compound brush components.

Five previously retained-only pressure graphs now affect hue, saturation, brightness,
secondary colour and bleed. Flow, size, opacity and overall response remain independent.
Finger input uses touch taper and neutralizes pen-only pressure dynamics, including inverse
flow that could otherwise make a finger stroke disappear.

Wet charge, attack, dilution, pull and blur jitter now reach the canvas pickup model. These
are deterministic **ArtFlow approximations**, with default behavior retained where possible;
native pigment/fluid equations have not been recovered. Brush Studio exposes the connected
controls and the library codec validates and persists them. Fine imported grain supports
the renderer's existing minimum scale of `0.05`.

Existing saved brushes retain their effective parameter values. Reimporting a source brush
applies the corrected native channel interpretation. A default-omitted import-contract version
distinguishes earlier saved aliases from fresh native-informed imports during source-aware
export. This prevents an untouched old wet-mix or opacity value from being misidentified as a
user edit. Original source scalars and structures remain available for unedited archive export;
new default fields also remain omitted so historical signed document checksums stay valid.

The editor's share controls now expose `.artbrush`, `.brush` and `.brushset` routes. Dual-brush
exports include a second `Sub01` settings archive and its own shape/grain images. Unedited source
combine IDs are retained; newly selected local combine modes use approximate IDs and do not
establish native compound rendering equivalence. Fixed horizontal/vertical tip flips are baked
into exported shape images while random flip jitter stays independent. Embedded-image exports
remove obsolete source bundle paths without inventing missing image data.

### Remaining brush limits

All 33 observed source graphs are retained; four primary and five auxiliary graphs affect
rendering. **24 auxiliary graphs remain interchange-only**, including several wet, grain,
jitter, scatter, roundness and stamp-count responses. Native grade/load interpretation,
pressure filtering/interpolation, continuous grain motion, all observed compound modes,
texture sampling, input calibration and reference-device pixel equivalence remain open.

## Animation Assist

Preview and animated export now share the same frame-order plan. Playback range, hidden
frames, loop/one-shot behavior and ping-pong ordering use one implementation. Ping-pong does
not duplicate endpoints within a repeating cycle; finite legacy ping-pong performs a complete
forward-and-return pass, including the final first-frame hold. Each frame retains its duration.
An explicit export FPS or repeat override still wins when the user chooses it. Selected source
frames are resolved before rendering and memory budgeting, so excluded frames do not consume
the clip's allocation budget. Reverse samples reuse already rendered buffers.

GIF export keeps exact 8-bit RGB values when the image fits the 256-slot palette, including any
reserved transparency entry. Larger palettes retain the bounded quantization path. Independent
GIF decoding checks frame order, holds, transparency and exact small-palette colours.

Background, foreground and hidden roles use stable frame IDs. Multiple pinned backgrounds
and foregrounds survive reorder, undo/redo, save and reopen. Legacy first/last-frame switches
are materialized into stable IDs during editing, preserving old documents. New default-empty
role sets are omitted during serialization so older canonical document checksums remain valid.

The animation panel exposes previous/next onion colours, frame visibility and roles. Onion
opacity can be zero and the frame count is bounded at 12. Ghosts omit pinned composites so
backgrounds are not painted again for every neighboring frame.

The current-frame editable export stack now contains the same pinned layers and folder masks
as the merged preview. Hidden pins stay absent during ordinary playback/export and remain
accessible when explicitly selected for editing.

Remaining work includes reference-native hold-count semantics, `.procreate` animation metadata
interchange, physical playback pacing, long animation memory/performance, and runtime validation
of MP4 and APNG output on the device matrix.

## Editable PSD layer and folder masks

PSD export now writes masks as independent user-mask channel **−2**, with their own dimensions,
coverage and standard 20-byte mask record. Enabled/disabled state survives import and export.
Artwork alpha remains separate so covered pixels can be revealed after reopening the PSD.
Folder masks are retained and masked groups use isolated compositing.

Density, inversion and feather are evaluated into effective coverage. Export resizing treats
coverage as data rather than blending white over white. Selection export clips artwork once
and only crops the independent mask, avoiding squared feathered edges.

Resampling paint and an enabled mask separately can reveal hidden colours or change alpha.
Resized PSDs with an enabled independent layer or folder mask therefore display a correctly
rendered **Artwork (resized masks)** layer and retain the resized editable layers and masks
hidden underneath. The export panel and completed-export warning explain this consequence.
Original-size exports and integer placement/cropping keep masks directly editable; disabled-only
masks do not need this appearance layer because they do not affect the displayed artwork.

Layers with post-mask effects deliberately retain baked mask appearance because exporting the
mask after the effect would change the image. Those layers do **not** gain an independent
editable PSD mask. Adjustment/filter layers and other existing flattened fallback paths still
have their documented limits.

The PSD reader now checks mask rectangles before allocation, rejects overflowing coordinates,
and bounds each channel by its declared length. PackBits rows must decode within their own
recorded byte count. Malformed masks cannot borrow bytes from a following row or layer.
Legal no-op packets, zero-area masks and valid 8/16-bit channels remain supported.

Independent decoding also exposed a merged-preview mismatch: PSD RGB previews with declared
transparency store their colour over white. The exporter now applies that white matte to the
merged RGB planes only, and the reader reverses it for RGB composites with a negative layer
count. Editable layer RGBA stays byte-exact. An empty export layer list still becomes one
`Artwork` layer, so the transparency flag never relies on a nonexistent negative-zero count.

The preview has the normal quantization of an 8-bit white-matted image: low-alpha RGB cannot
always recover the original colour exactly. Nearest-integer matting contributes at most
`127.5 / alpha` RGB code values of recovery error, with byte-valued alpha from 1 to 254;
nearest-integer decoding adds at most 0.5, while a decoder that truncates adds less than one.
Alpha and opaque RGB remain exact. This does not reduce the precision of the separate layers.
Earlier ArtFlow exports already used negative layer counts but wrote straight merged RGB and
recorded no creator/version marker, only resolution. Their old previews cannot be identified
reliably from metadata. Import retains their intact layer pixels; it does not guess the preview
convention from colours. Other supported colour modes and unmarked composites retain their
existing interpretation.

**psd-tools 1.26.0 independently passed 43 checks each for raw and RLE production exports.**
Those checks cover document dimensions, both folder hierarchies/order, active and disabled
layer/folder mask rectangles and coverage, absolute-coordinate flags, unchanged source RGBA,
the following layer's channels, and the repaired merged preview within its quantization bound.
Five additional JVM regressions passed against literal merged-plane bytes and independent
raw/RLE/ZIP fixtures. The [synthetic fixture record](../app/src/test/resources/psd/README.md)
contains primary sources, generation instructions and hashes; psd-tools is not an app dependency.

These are standard PSD semantics, informed by the source writer. ArtFlow uses absolute mask
coordinates and flags 0/2; the examined native path uses relative coordinates and flags 1/3.
Byte-for-byte identity is neither required nor asserted.

## Build integration

The first actual Android build exposed 14 diagnostics in the starting application's integration:
missing settings/extension imports, a missing layer-gesture property, a duplicated haptics
argument, stale saved-preset naming, picker error-message arguments and an absent brush-set
share helper. These are repaired through the existing production APIs. The brush model's
missing wet-attack property and stale rendering enum references are also reconciled.

The Android CI build job now runs the standard-library reference parser tests in addition to
the existing artifact verifier suite. Combined variant compilation exhausted the shared in-process
compiler heap; Gradle now uses one worker within the existing heap budget. Host unit-test Kotlin
compilation can access JDK APIs such as the independent ImageIO GIF decoder, without changing
the Android application or instrumentation compile targets.

The complete JVM run also exposed ignored alpha-lock arguments in adjustment/fill paths and
missing cancellation forwarding to expensive blur operations. Those production paths now honor
their existing contracts. Saved QuickShape/touch-hold settings and disabled scrub gestures are
respected; existing stylus-button choices are reachable in Actions > Prefs. Canonical preference
keys retain fallback reads for earlier saved keys, including an intentional empty preset list.
Keyboard shortcuts now intercept the public `Window.Callback` API, preserving pre-view shortcut
ordering, text-field bypass and held-key behavior without calling a restricted AndroidX method.

No test lanes, assertions, lint rules or detekt baseline entries are removed. Three pre-existing
golden expectations were corrected only after independently compiling the unchanged starting
renderer and reproducing the same hashes as the new code. The [baseline proof](BRUSH_BASELINE_VALIDATION_2026-10-21.md)
records every source boundary, hash and compile-only adjustment. Conflicting older speed tests
now follow the signed reversal already pinned by the starting pressure-mapping tests.

## Verification record

The full JVM run used the actual Android Gradle project, with Gradle 8.14.3, Android Gradle
Plugin 8.10.1, Kotlin 2.0.21, Temurin JDK 17 and Android SDK 36. Both reference-data environment
variables were supplied. A fresh source-only verification checkout was used after stale generated
outputs repeatedly reappeared in the working checkout; its source files were compared with the
implementation checkout. No pruned source harness or replacement Android stubs were used for
the full-suite result.

| Check | Executed result | Scope |
| --- | --- | --- |
| Android Gradle `testDebugUnitTest` | **1,325 tests; 0 failures, 0 errors, 0 skipped; 223 suites** | Complete JVM suite, including repository and export integration |
| Supplied IPA brush corpus | **394 brushes, 140 compound brushes, 534 components passed** | Metadata import and persistence; 2,135 primary-channel curves parsed; source images and native rendering were not exercised |
| Genuine `.procreate` documents | **All 5 passed** | Avarel/silicate samples, including a mask document and documents with 26 blend modes; calibrated tolerances, not exact pixel equality |
| Independent PSD decoding | **86 checks passed** | 43 checks each for raw and RLE production exports using psd-tools 1.26.0 |
| Python reference/tool tests | **37 passed** | Includes 17 native-parser regressions |
| Python CI/artifact-verifier tests | **65 passed** | Existing fail-closed device-log and binary-verification logic |
| Actual IPA native contracts | **11 scalar, 2 curve and 1 PSD contract passed** | Hash-pinned static verification; no native app execution |

The final build/static-analysis invocation completed successfully with all of these requested
tasks: `assembleDebug`, `assembleDebugAndroidTest`, `ktlintCheck`, `detekt`, `lintDebug`,
`lintRelease` and `bundleRelease`. It used one worker, no build cache and disabled KSP incremental
processing in the isolated checkout. All 721 non-document source/configuration files and their
path inventory matched the implementation checkout. The last follow-up fixes were unused
imports and instrumentation fixtures; no production behavior changed after the full JVM run.

| Final gate | Executed result |
| --- | --- |
| Debug APK | **PASS** — compiled and packaged |
| Instrumentation APK | **PASS** — compiled and packaged, not executed locally |
| Release AAB | **PASS** — minified and packaged; unsigned candidate |
| `ktlintCheck` and `detekt` | **PASS**, without new suppressions or baseline changes |
| `lintDebug` and `lintRelease` | **PASS** — 0 errors; 149 debug and 147 release warnings remain |
| Packaged artifact preflight | **PASS** for the debug APK, instrumentation APK and release AAB; ZIP/manifest integrity, native ABI pairs, ELF LOAD/RELRO and applicable APK-entry 16 KB alignment checked |
| Production signing negative test | **PASS** — `help -PrequireReleaseSigning=true` failed with the required missing-signing-configuration diagnostic |

Device instrumentation and physical-device acceptance are separate execution gates. No local
emulator or physical-device run is claimed. The existing CI matrix covers API 26, 35 and 36,
including 16 KB configurations and a tablet, but a configured lane is not an executed result.
Native wet-paint equivalence, input latency and reference-app pixel equivalence remain unverified.

Reproduction commands from an SDK/JDK-configured checkout:

```bash
ARTFLOW_REFERENCE_IPA=/path/to/reference.ipa \
PROCREATE_SAMPLES=/path/to/silicate/demo_files \
./gradlew testDebugUnitTest --stacktrace
./gradlew ktlintCheck detekt lintDebug lintRelease --continue --stacktrace
./gradlew assembleDebug assembleDebugAndroidTest bundleRelease --stacktrace
python .github/scripts/verify_android_artifacts.py \
  app/build/outputs/apk/debug/*.apk \
  app/build/outputs/apk/androidTest/debug/*.apk \
  app/build/outputs/bundle/release/*.aab
python -m unittest discover -s tools -p 'test_*.py'
python -m unittest discover -s .github/scripts -p 'test_*.py'
python tools/ipa_native_contracts.py /path/to/reference.ipa --out native-contracts.json
```

The reference IPA is optional outside this audit; omitting it skips its corpus test. Supplying
the path is necessary to reproduce the source-corpus result. Device execution and a signed
production release must not be inferred from successful JVM checks or an unsigned bundle.
