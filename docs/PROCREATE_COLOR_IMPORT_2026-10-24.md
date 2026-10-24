# Native Procreate RGB profile import — 24 October 2026

## Demonstrated mismatch

All five current genuine sample files contain Display P3 matrix/TRC colourimetry in their
document ICC metadata and PNG thumbnails. They contain two distinct 536-byte original profile
payloads. One archive labels its profile `ColorspaceDerived`, so names alone cannot identify
the encoding. The fixed file inputs and their four unique byte sequences are recorded in
[the hierarchy audit](PROCREATE_HIERARCHY_IMPORT_2026-10-24.md).

Native tile decompression returns the file's channel values. Android's
[`Bitmap.getPixels`](https://developer.android.com/reference/android/graphics/Bitmap#getPixels(int[],int,int,int,int,int,int))
returns straight sRGB colour integers, including when the decoded bitmap uses Display P3.
The previous reader discarded the document profile, compared raw P3 tile channels against an
sRGB thumbnail on Android, and inserted the raw channels as though they were sRGB.

The old minimal JVM PNG helper returned raw channels and ignored embedded ICC data. That made
its genuine-file thumbnail comparison different from the Android path. Its raw decoder remains
available for codec tests; the new explicit `decodeSrgb` helper is used for recognized-profile
calibration fixtures and genuine samples. It is not a replacement for Android's arbitrary ICC
decoder.

## Corrected boundaries

- The reader carries the recognized source profile for native layer, saved-composite and
  background values. Those values remain in their original encoding.
- Calibration explicitly requires a straight-sRGB thumbnail callback. Each candidate channel
  order is resolved before unpremultiplication and profile conversion. Converted sRGB colours
  are then averaged for the orientation comparison, with the background in the same space.
  Mask coverage is independent of colour-profile conversion.
- Opening a new Procreate artwork preserves its recognized sRGB or Display P3 profile.
  Insertion into an existing canvas converts the imported layers into that canvas's profile,
  leaving existing layer values intact. The memory-based flattened fallback follows the same
  source-to-destination conversion.
- Background insertion accepts its source profile explicitly. Ordinary interface calls retain
  the default sRGB interpretation.
- An embedded unsupported or malformed profile retains the historical sRGB fallback and
  produces an import notice. Missing profile metadata retains the existing sRGB default.

The import memory budget still defaults to half the runtime's maximum memory. The explicit
budget parameter makes the normal and flattened paths reproducible in the regression test; it
does not change the application's default limit.

These changes affect new external imports. They do not reinterpret already saved artworks or
rewrite brush libraries, and introduce no additional ArtFlow document-version migration.
A historically mistagged imported artwork cannot be automatically repaired without identifying
its actual original encoding.

## Bounded ICC recognition

The matcher recognizes canonical RGB sRGB or Display P3 monitor profiles by bounded header,
tag, matrix, white-point and transfer-curve checks. It supports parametric curve types 3 and 4
with the sRGB transfer parameters and matching sampled curves. Matrix/TRC values, not the
description string, determine recognition. LUT transforms, non-RGB profiles, other primaries
or transfer curves and invalid lengths/offsets remain unsupported.

The implementation follows the matrix/TRC representation described by the
[ICC profile specification](https://www.color.org/v4spec/). Recognition tolerances account for
fixed-point encoding and small matrix-rounding differences; they are not a general colour
conversion policy. ArtFlow still uses its existing bounded sRGB/Display P3 conversion and
8-bit channel storage. General ICC transforms, profile-managed CMYK, native export metadata,
arbitrary rendering intents and physical wide-gamut display qualification remain open.

## Independent fixtures and checks

The committed [fixture generator](../tools/fixtures/make_procreate_color_fixture.py) uses
Python's standard library to create original matrix/TRC profiles, a transformed BGRA document
and its exact 3,072-pixel native-channel oracle. No original ICC payload, native executable or
reference artwork is embedded in these fixtures. All seven generated files were independently
regenerated into a temporary directory and matched the candidate bytes exactly.

LittleCMS 2.19 with relative-colorimetric intent independently confirms the test's fixed colour
expectations. Examples include P3 `(230,70,45)` to sRGB `(250,49,23)`, and the P3 background
`(240,30,90)` to sRGB `(255,0,86)`. The complete fixture contract and precise one/two-code-value
conversion tolerances are in
[the fixture provenance](../app/src/test/resources/procreate/color/README.md). Raw native channels,
alpha, saved rasters and reopened native values use exact assertions.

JVM checks cover recognized and misleading profile names, parametric and sampled transfer
curves, different primaries/gamma, unsupported LUT/CMYK profiles, truncated/invalid payloads,
calibrated layout, source-channel preservation, unsupported metadata and the background boundary.
The genuine-file test verifies complete file hashes, both original ICC hashes and exact
recognized profiles, alongside the hierarchy and mask checks.

Two Android cases exercise actual platform thumbnail decoding, native new-document import,
profile/background persistence, actual storage, tagged preview decoding and a fresh repository.
Existing-canvas insertion and forced flattened fallback are checked for both supported profiles,
with existing artwork unchanged and no double conversion.

The exact publication's completed Actions matrix remains the device acceptance gate. These
contracts do not establish overall Procreate rendering or feature equivalence.
