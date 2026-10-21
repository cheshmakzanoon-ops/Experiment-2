# Verified REA keyed-archive class graphs — 21 October 2026

## Evidence, not recovered native implementation

The [successful 19 October REA inspection run](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/39179569624) emitted three `rea-compound-*.json` reports from exact archived `Brush.archive` components, together with the 394-brush / 534-component `compound-structures.json` corpus. The source IPA SHA-256 is `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`. The [new graph audit](../tools/rea_keyed_graph_audit.py) verifies each REA sample's SHA-256 against its parent archive's declared hash, object IDs, reference targets, class names and control-point structures.

| Serialized object class | Sample 0 | Sample 1 (secondary component) | Sample 2 | Total |
| --- | ---: | ---: | ---: | ---: |
| `SilicaBrush` | 1 | 1 | 1 | 3 |
| `ValkyrieBrushRollParameters` | 12 | 12 | 12 | 36 |
| `ValkyrieMagnitudinalCurve` | 11 | 10 | 11 | 32 |
| `NSMutableArray` (curve point arrays) | 11 | 10 | 11 | 32 |
| `NSArray` (other saved arrays) | 1 | 1 | 1 | 3 |
| `NSDate` | 1 | 1 | 1 | 3 |
| **All object table entries, including scalar and descriptors** | **75** | **68** | **73** | **216** |

The 32 curve objects refer to **71 stored control-point strings** through `NSMutableArray.NS.objects` UID links. REA class descriptors show a superclass chain containing `NSObject` and, for mutable arrays, `NSArray`. The `ValkyrieMagnitudinalCurve` and `ValkyrieBrushRollParameters` names are concrete serialized class identities, not guessed names. The three inspected graph samples do **not** represent the application's complete binary implementation or all 534 brush components.

## Validated graph shape

A `SilicaBrush` archive root has primitive settings and UID links to other serialized objects. Each sampled `ValkyrieMagnitudinalCurve` stores a `points` UID to an `NSMutableArray`; its elements are scalar strings containing pairs of normalized coordinates. Twelve roll-parameter objects each include `activationFalloff`, `activationOrigin`, `mode` and `activationRange`. A creation-date `NSDate` has `NS.time`.

ArtFlow's keyed writer now uses matching serialization classes for source curves and roll metadata, and its importer retains the bounded typed fields. This establishes **static schema consistency**, not Apple's decoding acceptance, interpolation method, feature behavior, or pixel equivalence. REA itself explicitly reports that unknown archived classes are not instantiated and class-specific semantics remain unknown.

## Reproduce

Retrieve the evidence artifact from run 39179569624, then run:

```bash
python -m unittest discover -s tools -p 'test_rea_keyed_graph_audit.py' -v
python tools/rea_keyed_graph_audit.py \
  compound-structures.json rea-compound-0.json rea-compound-1.json rea-compound-2.json \
  --out rea-keyed-graph-report.json
```

The audit rejects an evidence hash not belonging to the pinned source, malformed UIDs, unexpected class names, invalid point strings and incomplete roll parameters. It writes only aggregate data, evidence digests and structural descriptions. No original brush files, source binaries, paid images or decrypted instructions are committed.
