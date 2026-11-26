# Pinned grapheme segmentation data

`GraphemeBreakTest-17.0.0.txt` is the unmodified Unicode 17.0.0 conformance corpus
(766 cases), downloaded from:

https://www.unicode.org/Public/17.0.0/ucd/auxiliary/GraphemeBreakTest.txt

The runtime table in `GraphemeData.kt` combines Grapheme_Cluster_Break,
Indic_Conjunct_Break and Extended_Pictographic from this same Unicode release.
`tools/generate_grapheme_data.py` records every source URL and SHA-256 checksum,
verifies the downloaded inputs, and regenerates the table and test fixture.
The table has 833 sorted disjoint ranges (6,664 bytes of primitive Long payload);
Hangul LV/LVT is calculated arithmetically. No device Unicode property lookup is used.

Rules: https://www.unicode.org/reports/tr29/tr29-47.html#Grapheme_Cluster_Boundary_Rules

The Unicode License V3 notice is retained in
`app/src/main/resources/META-INF/UNICODE-LICENSE.txt`, including in packaged resources.
The corpus is a test resource; it is not included in the application.

This pins default extended grapheme boundaries, not font coverage, glyph shaping,
locale-specific word breaking or future Unicode versions. Unpaired UTF-16 surrogates
retain their original code units and the default Other property.
