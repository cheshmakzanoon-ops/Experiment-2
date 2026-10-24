# Signed version 4 standard-Darken fixture

`v4-standard-darken.json` was encoded with the unchanged published version 4
`CanvasDocumentCodec` from commit `33eeb87cd9156be88099ee02d675c1a3063d5cd2`,
before the native layer contract or version 5 was compiled. It is self-authored
test metadata, not a reference-application asset. The Android test carries the
same 1,990 bytes as `assets/migration/standard-darken-v4.artflow`.

- Manifest SHA-256: `3814c6bd8a3b22dd733ecdbdbce285b8481220906264abbeb201db60715372a6`
- Stored canonical checksum: `3784ea394063ae4b4a31cda0a33714f86d710c8c349511f78b2d988c691342b9`
- Width 32, height 16, DPI 72, transparent background, root layers `Base` and
  `Darken`, empty animation frame list, savedAt 0, nextLayerId 3.
- Source is straight ARGB `553cb45a`; the base has four eight-column bands:
  `00000000`, `aa963cb4`, `ffd25a96`, `551ed25a`.
- The unchanged old renderer produced these standard-Darken bands:
  `553cb45a`, `c66f4d8d`, `ffa05a82`, `8e2ac05a`.
- SHA-256 of the 512 big-endian rendered ARGB integers, without dimensions:
  `d4c364947957eb1d28c19bfd7f69b4e7d63106ea8235654cac56d535f0170374`.

The new decoder must verify the original checksum before migration, retain
standard Darken, and reproduce those pixels. Explicit native source19 layers
use independent rational bands `00000000`, `aa823ca5`, `ffa05a82`, `551ed25a`;
their corresponding full-pixel hash is
`cdd509411a1ba3ceb86b652d1f91e4122ad9833a3bf0ad6e0db6bf5e2eb4b359`.
Those new values do not replace the historical fixture or its old-renderer
expectations.
