# Native layer Darken: bounded static contract and genuine-file checks

Date: 2026-10-24. This record distinguishes the recovered static layer contract, genuine saved-pixel evidence, and the bounded ArtFlow implementation. It does not claim native application execution or complete rendering parity.

## Finding

The saved blend samples select archive mode **19**, which the pinned Procreate 5.4.4 executable translates to GPU mode **35** in its actual layer compositor. For ordinary finite premultiplied RGBA inputs, with identity channel swizzles and the preserve-target branch disabled, that shader computes:

`R = componentwise_min(B, S + (1 - S.a) * B)`

The minimum includes alpha. Here `S` is already scaled by the layer's opacity. The mask factor is subsequently applied as `mix(B, R, coverage)`. In real arithmetic this preserves the destination alpha. In particular, a transparent zero destination remains exactly zero for finite nonnegative source values. This explains the observed native `00000000` versus ArtFlow `fe151633` discrepancy without changing any group formula or color profile.

The same layer shader has a separate **GPU47 / archive34** branch: unpremultiply the two RGB inputs, take the RGB minimum, then use the source-over mixer. Its real-arithmetic equation is the one used by ArtFlow's existing standard Darken mode:

`R.rgb = (1-S.a)*B.rgb + (1-B.a)*S.rgb + B.a*S.a*min(unpremultiply(B), unpremultiply(S))`

`R.a = S.a + (1-S.a)*B.a`

These are two different contracts. Existing ArtFlow Darken pixels must retain their existing contract.

## Pinned inputs and reproducibility

The verifier hashes the whole IPA before reading its two bounded ZIP members. It parses ARM64 and AIR as data; it neither executes native instructions nor JITs shaders. It does not extract or output artwork, profiles, executable contents or IR.

| Input | Bytes | SHA-256 |
| --- | ---: | --- |
| Established reference IPA | 777430382 | `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e` |
| `Payload/Procreate.app/Procreate` | 19119424 | `76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b` |
| `Payload/Procreate.app/ValkyrieMetal.metallib` | 1370648 | `30ed6b20d7592fe45b4b94c4a2e7e820a1871f705e32ab280fcd0bde7709c6de` |
| `ValkyrieFragmentBlend` AIR bitcode, wrapper `0xc639` | 32512 | `7057e66b46f286d2496a07b75f93fd59f4b50132128d953c96fa282812a130a8` |

Use Python with `capstone==5.0.6` and `llvmlite==0.44.0`:

```sh
python tools/ipa_layer_blend_contracts.py "$ARTFLOW_REFERENCE_IPA" \
  --check app/src/test/resources/layers/native-layer-blend-fixtures.json \
  --out layer-blend-contracts.json
```

The verifier reuses the published 35-entry archive-to-GPU translation check and additionally checks method ownership, decoded selectors, native string keys, class references, selected instruction dataflow and selected AIR operations. The JSON includes nine exact rational scalar fixtures, checked against the committed [derived fixture file](../app/src/test/resources/layers/native-layer-blend-fixtures.json). The additional layer linkage checks cover nine method identities, seven strings, 18 selectors, 38 instructions, three class references, and 25 AIR dataflow anchors. These fixtures describe recovered arithmetic; they are not captured GPU executions. Seven standard-library tests also reject truncated/wrong module wrappers, incomplete or unpinned bitcode, altered fixture values, and mismatched fixture provenance. The required Actions reference step runs this verifier and uploads only its small derived JSON report.

## Actual layer-pipeline linkage

The finding does not infer layer behavior from a brush shader. The following linked path is present in the pinned executable:

| Stage | Address | Verified connection |
| --- | --- | --- |
| `ValkyrieLayer.initWithCoder:` | `0x100b2c274` | Selects `extendedBlend2`, then `extendedBlend`, then `blend`; stores the decoded integer at layer offset `0x48`. An explicitly present zero in a higher-priority key wins. |
| `ValkyrieLayer.blend` | `0x100b351a4` | Loads the integer at offset `0x48`. |
| `ValkyrieCompositeAction.blendLayer:source:predicted:` | `0x100a5fda8` | Reads the layer's blend and opacity, storing them on the action. |
| `ValkyrieCompositeResolvedAction.actionWithAction:` | `0x100a60174` | Calls `ValkyrieLayer.fragmentBlendMode:` on the action's mode and stores the resulting GPU mode at resolved-action offset 24. Copies opacity into fragment parameters at byte 64. |
| `ValkyrieComposite.executeBlendAction:encoder:` | `0x100a5b18c` | Captures the resolved action; its block loads that offset into the blitter's `blend` argument. The retain helper is independently resolved as `_objc_retain_x2`. |
| `ValkyrieShaderBlitter.blitterMatchedToEncoder:...blend:...` | `0x100b603fc` | Retains the mode through its cache key and supplies it to `ValkyrieFragmentBlend` construction; uses that fragment function in the render pipeline. The pipeline call passes `alphaBlending=false`. |
| `ValkyrieFragmentBlend.initOnDevice:blend:...` | `0x100b4adc8` | Supplies the blend integer to Metal function constant index0 and creates the named `ValkyrieFragmentBlend` function. |
| AIR `ValkyrieFragmentBlend` | wrapper `0xc639` | The initialized function constant is read by `safelyBlend` and dispatched to `inline_blend`. GPU35 reaches the componentwise four-channel minimum; GPU47 reaches the unpremultiply/RGB-min/source-over-mixer path. |

The AIR source input is the sampled `sourceTexture` or `sourceColor` attachment. The destination input is `baseColor`, not a brush amount texture. Source opacity is multiplied into all four source channels before blending. The final result passes through a configurable result swizzle, so the ordinary-RGBA restriction is material.

The `safelyBlend` helper saturates the destination and replaces a NaN-containing destination with zero. Its separate preserve-target branch unpremultiplies the destination, blends with a temporary alpha of one, then repremultiplies by the original destination alpha and restores that alpha. Masking-mode bit13 selects this branch. The static report and fixtures distinguish it from the ordinary branch; the complete host routing from every possible clipping/group configuration is not yet a proved contract.

## Export keys are also established

`ValkyrieLayer.encodeWithCoder:` at `0x100b2c894` writes the actual mode to `extendedBlend2`. At `0x100b2ca1c` through `0x100b2ca24`, it explicitly maps mode34 to19 for `extendedBlend`. The legacy `blend` fallback table begins at `0x100c7f908`; the entry for34 is `0x100c7f908 + (34-20)*8 = 0x100c7f978`, whose value is19. Therefore the pinned native writer uses:

| Layer contract | `extendedBlend2` | `extendedBlend` | `blend` |
| --- | ---: | ---: | ---: |
| Legacy Darken, archive19 | 19 | 19 | 19 |
| Source-over Darken, archive34 | 34 | 19 | 19 |

The fallback allows an older native reader to recognize a Darken variant; it cannot preserve the newer alpha equation in a reader that ignores `extendedBlend2`. ArtFlow now follows these explicit fields in `ProcreateLayerBlendModes`, a layer-specific map that leaves brush and dual-mode interchange unchanged. Opening the resulting archive in the native application remains an unperformed validation step.

## Agreement with genuine saved pixels

The original archive metadata is independently read with Python `plistlib`. The main Darken layer is UUID `6C209783-2DA6-4426-BD7C-2D70438E3AD8`, under the group named `Darken`, visible and unclipped with opacity1. The base and File2 archives contain `blend=extendedBlend=19`; File3/reference also contain `extendedBlend2=19`. The newer pair have a second source19 layer under `Shade`.

`ProcreateDarkenSampleProbe.kt` streams the existing raw native layer decoder, independently evaluates the recovered real equation at four specific coordinates, and compares against the raw saved native composite. It uses the pre-profile Reader contract and raw PNG calibration callback; layer and composite channels share the same source color encoding. It checks that active contributors at these points are visible, unmasked, unclipped and opacity1, rather than assuming clipping or group behavior.

| Coordinate | Active source19 pixel | Destination immediately before source19 | Saved native pixel | Existing ArtFlow pixel | Derived legacy-contract pixel |
| --- | --- | --- | --- | --- | --- |
| `(1340,1336)` | `fe151633` | `00000000` | `00000000` | `fe151633` | `00000000` |
| `(1336,1396)` | `fe151633` | `01000000` | `01000000` | `fe151633` | `01000000` |
| `(1336,1372)` | `fe151633` | `04004040` | `04004040` | `fe151633` | `04004040` |
| `(2028,244)`, File3/reference only | `fe151633` | `00000000` | `00000000` | `fe151633` | `00000000` |

All four selected points agree in each of the four blend files; the last coordinate is empty in the two 53-layer files. There are14 nonempty source19 comparisons across those files, not16. File3 and reference are byte-identical, so these are three distinct archive byte streams and ten nonempty comparisons when duplicate archives are counted once. This is focused evidence and does not replace full-image validation or establish a threshold for all blend modes.

| Genuine file | SHA-256 |
| --- | --- |
| `Reference_Blend_File.procreate` | `4dca07aea1389ed521eb24f43e1a4e03dcd32d723c657d38f3e44ee69ced02f4` |
| `Reference_Blend_File 2.procreate` | `097c338d054452887e2f4835d26a58a41ca854d4777223512334d3895b90daf0` |
| `Reference_Blend_File 3.procreate` and `reference.procreate` | `8b288e0886b3cc68030dcfdeedd7ceeb37bb1e41cc3d29ac5061d968821d59db` |

The saved files expose document-format versions1/2, not a sufficient authoring application version. Their exact stored-pixel consequences agree with the pinned shader's equation; this does not prove their authoring binaries used identical shader code.

## Implemented compatibility boundary

The persisted `Layer.blendContract` defaults to `STANDARD`, with `EncodeDefault.NEVER`. The document checksum reserializes defaults, so omitting this new default preserves historical signed bytes. Fresh native source19 imports opt into `PROCREATE_DARKEN_19`; source34 and existing v1–v4 ArtFlow drawings retain ordinary standard Darken. The imported state is kept distinct from choosing a standard blend option.

New saves use version5 so older readers reject the rendering contract. Original checksums are verified before migration; original-version cutoffs remain group changes below3 and historical secondary-grain replay changes below4. Root layers and every frame carry the marker through copies, snapshots, undo, and save/reopen. An explicit standard blend selection clears it even when the public enum is already `DARKEN`; undo restores the imported state. Flattening creates neutral standard layers containing the already rendered pixels. Ordinary `BlendModes.DARKEN` is unchanged.

The CPU implementation is deliberately limited to ordinary imported source19 layers with the app's eight-bit straight-ARGB buffers. It computes the recovered premultiplied minimum using Double arithmetic and rounds back to bytes. A layer mask still uses ArtFlow's existing alpha quantization. For finite nonnegative coverage, scaling the source before this minimum is algebraically equivalent to native post-blend mixing in real arithmetic, but no claim is made that the intervening byte rounding matches every native half-precision result.

Clipped layers retain ArtFlow's existing rule: blend against an opaque temporary base and restore its exact alpha. For Darken this is algebraically consistent with the recovered preserve-target shader branch, but the implementation does not pretend that all native group, hidden-layer, clipping, or state-reset routes have been reconstructed. Static fixtures cover that shader branch separately; application tests name the existing ArtFlow clipping rule explicitly.

PSD standard Darken cannot encode ordinary native19 over a transparent or partial destination. The established appearance-fallback policy therefore writes a visible `Artwork (native layer blending)` layer and includes editable source layers and masks hidden, with an explicit warning. Native `.procreate` export instead carries the proven source19 fields; ordinary standard Darken carries source34 and its legacy fallbacks. PNG export simply uses the correctly composited artwork.

## Historical and new-artwork checks

The self-authored [signed version4 fixture](../app/src/test/resources/documents/v4-standard-darken.json) was generated with the unchanged published version4 codec before compiling this change. Its [provenance record](../app/src/test/resources/documents/v4-standard-darken.provenance.md) records the exact 1,990 bytes, original checksum and old-renderer pixel hash. The new codec must re-encode that original document byte for byte, reject a corrupted checksum before migration, preserve standard Darken, and render the old pixels. Version5 tests retain native contracts across root layers and frames while rejecting invalid marker/mode/version combinations.

A separate control was run against the frozen published version4 runtime, whose JAR SHA-256 is `944b95a6efa8849fda85cf04d869e5f6eda698266a4ddfeb93ebeffc61a83e52`. Both a correctly signed standard version5 document and an unsigned version5 document carrying the new native marker decoded to version5 and were rejected by the old `requireValidHeader` with `Unsupported project version 5`. `ProjectStorage` invokes that check immediately after decoding both ordinary saved manifests and packaged imports. The unsigned case ensures that rejection does not depend solely on a checksum difference caused by the unknown marker. No reference-application binary was executed by this control.

The independent 32-by-16 image fixture uses a source with straight ARGB `553cb45a` over four eight-column base bands. Its source and base premultiplied byte values are exact rational inputs.

The used LayersSheet → layer-row D button → blend dialog route now labels this state **Imported Darken**
and explains that choosing a mode replaces the imported effect and may change its appearance.
No standard option is highlighted while the native contract is active. Done and Back only dismiss;
an explicit mode still follows the existing immediate-selection action. A separate component-scoped
Android regression exercises this actual route, callback identity/count and the subsequent standard
selection state, retaining `layers-imported-darken.png`. It does not substitute for the repository
storage/undo lifecycle case.

| Base straight ARGB | Native19 output | Historical standard output |
| --- | --- | --- |
| `00000000` | `00000000` | `553cb45a` |
| `aa963cb4` | `aa823ca5` | `c66f4d8d` |
| `ffd25a96` | `ffa05a82` | `ffa05a82` |
| `551ed25a` | `551ed25a` | `8e2ac05a` |

The standard values were also captured from the unchanged old renderer. Further JVM checks cover intermediate masks, opacity, zero-source identity, every destination alpha byte, the established clipping path, key precedence including present zero, native versus standard export fields, repository edits, duplicate layers, copied animation frames, undo/redo, and neutral flattening. The Android lifecycle case runs a real authored archive through import, editable repository state, original-checksum rejection, disk save, fresh reopen, PNG, and PSD; its unmasked fixture does not stand in for device-level mask coverage.

`RealProcreateFilesTest` gates the three exact saved native points shown above for the four blend files. It also checks that each pinned archive's native flat layer UUID index equals the imported hierarchy's complete leaf order and multiplicity. Whole-image recomposition differences remain descriptive evidence, with no newly invented tolerance or claim of equality. The combined local JVM run reports rounded mean RGB differences of 0.36 for each blend file after this correction, compared with 0.58 for the 53-layer files and 0.81 for the 55-layer files at revision `33eeb87`. This whole-canvas mean includes transparent and background regions; it is not a perceptual acceptance score, and nonzero residuals in other modes remain.

Half arithmetic, host sampling and whole-pipeline image quality remain separately bounded. No current evidence supports a general replacement of ArtFlow's blend equations or a claim of complete Procreate parity.
