# IPA brush-resource dependency and reader coverage — 21 October 2026

## Provenance / confidence

This document is based on the SHA-pinned reference IPA (\`7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e\`) and the **completed** earlier [REA/keyed-archive workflow run 39179569624](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/39179569624), artifact \`12155827024\` (\`compound-structures.json\`). Reader call sites were inspected at source revision \`ace5ba3d9f81edd58d38d8143e24f03b082dbddd\` in \`ProcreateBrush.kt\`.

The corpus was analyzed using [\`tools/rea_brush_dependency_audit.py\`](../tools/rea_brush_dependency_audit.py). A set of synthetic Python regression tests passed locally. Only metadata and statistics were examined; no iOS executable code was executed, decrypted, or decompiled.

## Settings imported versus settings present

| Category | Observed distinct fields | Directly referenced by current Kotlin brush reader | Not referenced |
| --- | ---: | ---: | ---: |
| **All observed settings fields** | **264** | **52** | **212** |
| \`dynamicsPressure*\` | 49 | 4 | 45 |
| \`dynamicsTilt*\` | 12 | 2 | 10 |
| \`dynamicsSpeed*\` | 2 | 2 | 0 |
| \`shape*\` | 23 | 8 | 15 |
| \`texture*\` | 14 | 3 | 11 |
| \`grain*\` | 6 | 1 | 5 |
| \`plot*\` | 18 | 5 | 13 |

The family rows are overlapping categories only when names share a prefix; they are not meant to sum to 264. The 52 fields include names, path flags and source mode markers. **Referencing** a serialized field does not establish that the engine faithfully implements it. Nor does an unread field necessarily correspond to a user-visible feature; some are metadata, legacy settings or internal controls. This is a *code-access inventory*, not a functional-parity percentage.

## Assets and subcomponent relationships

| Static archive observation | Count |
| --- | ---: |
| Logical \`.brush\` containers | 394 |
| Secondary \`Sub01/Brush.archive\` components | 140 |
| Total settings archives | 534 |
| \`QuickLook/Thumbnail.png\` previews | 394 |
| Signature picture members | 7 |
| Author picture members | 7 |
| Other shape/grain PNGs stored **inside these brush containers** | 6 |
| Stored bundled shape path references | 534 (297 distinct paths) |
| Non-null bundled grain path references | 504 (234 distinct paths) |
| Direct \`Shape.png\`, \`Grain.png\`, \`Sub01/Shape.png\` or \`Sub01/Grain.png\` members | **0** |

The six embedded shape/grain PNG members belong to three brush packages. Most reference brushes instead identify bundled shape and grain files **by path**, which may resolve to assets elsewhere inside the original IPA. This limited brush-container inventory cannot establish whether each referenced file exists in the full application archive.

Consequently, extracting a \`.brush\` container does **not** produce a self-contained brush with its visual resources. A compatible reconstruction needs to resolve the requested shape and grain to resources or original, independently created substitutes, then test tip sampling, inversion, grain blending, motion, spacing and pressure.

## Reproduction

From a local copy of the successful Actions artifact:

\`\`\`bash
python tools/rea_brush_dependency_audit.py \
  compound-structures.json \
  app/src/main/java/com/artflow/studio/core/export/ProcreateBrush.kt \
  --out brush-reader-gap.json
python -m unittest discover -s tools -p 'test_rea_brush_dependency_audit.py' -v
\`\`\`

The output reports only observed settings **key names**, resource **path-count statistics**, and a small list of non-sensitive shape/grain member basenames, not the images, raw proprietary brush dictionaries or application bytes. The original archive identity and its 394/534 cardinality are validated before a report is accepted.

## Recovery criteria still unmet

1. **Complete bundle resource inventory:** Verify all 297 referenced shape paths and 234 grain paths against actual IPA members, resolving bundle-relative lookup semantics and checking hashes. The 777 MB IPA must be accessible to the analysis runner for this.
2. **Actual semantics of all retained settings:** Classify each serialized property as metadata, unused/legacy, imported, preserved, applied, approximated or tested against reference output; unknown mode IDs must remain unknown.
3. **Rendering and input:** Exercise deterministic strokes on real reference hardware and ArtFlow, record pressure/tilt/speed traces and compare perceptual/pixel outputs and frame times.
4. **Native code and app behavior:** Mach-O architecture, \`LC_ENCRYPTION_INFO\`, dependency graph, Objective-C/Swift metadata, native code bodies, shaders, UI states, file formats and graphics algorithms require separate evidence. This corpus establishes none of them.

**Verdict:** This is a verified static brush-schema and dependency analysis only. It does not amount to complete reverse engineering or 1:1 application parity.
