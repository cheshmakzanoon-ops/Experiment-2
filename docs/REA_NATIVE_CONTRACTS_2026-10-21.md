# Native archive and export contracts — 21 October 2026

This session downloaded the actual user-selected IPA and inspected its ARM64 code and
Objective-C metadata. The findings below establish specific serialized-field mappings and
mask-export behavior. They do **not** establish complete reconstruction or pixel parity.

## Source and tools

| Item | Observed value |
| --- | --- |
| Source | [User-selected Drive file](https://drive.google.com/file/d/1xPr6e53L76Cq8exGFWOSvq96wt-NlLVC/view) |
| IPA bytes | 777,430,382 |
| IPA SHA-256 | `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e` |
| Bundle-reported application | Procreate 5.4.4, build 56832 |
| Main executable | `Payload/Procreate.app/Procreate`, ARM64, 19,119,424 bytes |
| Executable SHA-256 | `76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b` |
| Encryption command | `cryptid=0` in this supplied binary |
| Decoded metadata | 1,707 classes plus metaclasses; 20,368 method records |
| Chained fixups | 134,018 local rebases; 29,856 external binds kept unresolved |

REA **6.7.0** `inspect-artifact` completed through its `rea-artifact-graph` provider and
recorded the same source hash. Evidence ID:
`ev_59d0f4948742caedce62a6891152e1d00aae4683caabf6cc9f479b0364251cd7`.
REA's `inspect-macho` returned `capability_unavailable`: that provider requires macOS.
The fallback used LIEF 0.17.6 for an independent initial relocation check and LLVM/Capstone
for targeted instruction inspection. The committed reproduction tool uses **only Python's
standard library** and independently verifies the important instruction/data relationships.

The source IPA, executable, textures, raw disassembly and full method dumps remain outside the
repository. The committed JSON contains hashes, addresses, selected names and verified facts.
The hashes identify the supplied bytes; official distribution authenticity and signing provenance
were not verified.

## Brush pressure channels: a misleading archive name

The old reader assigned `dynamicsPressureOpacity` to opacity based on its spelling. The native
initializer instead decodes that key into the field returned by **`dynamicsPressureFlow`**.
The separately stored `dynamicsPressureOpacityTransfer` controls native **opacity**.

| Serialized key | Native property | Initializer store | Getter | Field offset |
| --- | --- | --- | --- | --- |
| `dynamicsPressureOpacity` | `dynamicsPressureFlow` | `0x100a3ff4c` | `0x100a45da8` | `0x1d8` |
| `dynamicsPressureOpacityTransfer` | `dynamicsPressureOpacity` | `0x100a408a4` | `0x100a461ec` | `0x278` |
| `dynamicsMix` | `dynamicsDilution` | `0x100a40490` | `0x100a46468` | `0x2f4` |
| `dynamicsMixSoftening` | `dynamicsGrade` | `0x100a404a4` | `0x100a46478` | `0x2f8` |
| `dynamicsPressureMix` | `dynamicsAttack` | `0x100a40054` | `0x100a46488` | `0x2fc` |
| `dynamicsWetAccumulation` | `dynamicsWetPull` | `0x100a40950` | `0x100a46498` | `0x300` |

For every row the verifier resolves the actual CFString key, follows the direct call to
`decodeFloatForKey:`, checks the float-store instruction, and compares its field offset with
the named getter's direct float load. Additional checks cover `dynamicsLoad`, pressure bleed,
blur, blur jitter and stamp-count jitter. The wet-pull initializer uses smudge accumulation
as a fallback when its dedicated key is absent; the table describes the present-key path.

The curve assignments require the same distinction:

| Native property | Curve index selected by native bridge | Serialized curve key |
| --- | --- | --- |
| `dynamicsPressureFlow` | 3, bridge `0x100159080` | `dynamicsPressureOpacityCurve` |
| `dynamicsPressureOpacity` | 10, bridge `0x1001590b0` | `dynamicsPressureTransferModulationCurve` |

`+[ValkyrieBrush serializedKeyForCurve:]` begins at `0x100a3f274`. Its compiled jump table maps
index 3 to the return at `0x100a3f34c` and index 10 to `0x100a3f328`. The verifier checks each
bridge's selected property/index and resolves the corresponding return string. Neither these
mappings nor the field names specify Procreate's interpolation or wet-mixing equations.

## PSD masks remain separate from layer transparency

`SiPSDDataSourceLayer` declares separate `maskVisible`, `maskRect` and `maskdata` accessors.
More substantially, the native writer provides these observed behaviors:

- `SiPSDRecordLayer.record`, starting at `0x100b718d4`, writes bytes `FF FE` for user-mask
  channel **−2** at `0x100b71d28`–`0x100b71d2c`, followed by its independent channel length.
- The mask record uses outside coverage **255**. Its flags are **1** for visible and **3** for
  disabled: relative rectangle coordinates plus the disabled bit. The record is 20 bytes.
- `dataForMask`, starting at `0x100b72738`, requests `layer.maskdata`, invokes the channel
  compactor with **one channel** and **no inversion**, then records `maskChannelLength`.

ArtFlow's writer can represent the same editable-mask semantics using **absolute coordinates**
and flags **0/2**. It need not duplicate the reference writer's exact byte layout. A declaration
of `maskRect` alone is not proof that a particular export path reads it: the inspected native
`dataForMask` path reads the layer's rectangle.

## Animation evidence

The supplied binary declares independent previous/next onion-skin colours and primary-frame
mixing on `ValkyrieComposite`. `ValkyrieLayer` and `ValkyrieGroup` expose animation hold length
and background status. `ValkyrieDocument` has separate background/foreground item collections
and visibility-filtered accessors. The JSON records their native method addresses.

These declarations corroborate the feature surface. They do not prove playback timing,
visibility filtering rules, interpolation, colour blending, or rendering equivalence. Native
UI text also explicitly allows multiple foreground/background items in this particular build;
an implementation restricted to one pinned frame must retain that limitation in its parity report.

## Reproduce and test

```bash
python tools/ipa_native_contracts.py /path/to/reference.ipa \
  --out native-contracts.json
python -m unittest discover -s tools -p test_ipa_native_contracts.py -v
```

Executed locally: **17 synthetic regression tests passed**, then the full-source verification
passed with **11 scalar contracts, 2 curve contracts and the PSD mask contract**. Synthetic tests
cover relative/absolute method lists, pointer rebasing, refused external binds, malformed metadata,
unsupported formats, page-boundary errors, changed keys and mismatched stored/getter fields.

The parser intentionally rejects other architectures, encrypted code, unsupported pointer formats,
multiple-start pages and an unexpected source hash. It is a narrow evidence verifier, not a general
decompiler. [Machine-readable evidence](REA_NATIVE_CONTRACTS_2026-10-21.json) includes every checked
address and the remaining limits. Apple's [chained-fixup layout](https://github.com/apple-oss-distributions/dyld/blob/main/include/mach-o/fixup-chains.h)
defines the supported pointer representation.
