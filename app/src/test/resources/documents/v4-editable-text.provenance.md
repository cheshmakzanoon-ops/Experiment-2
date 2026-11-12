# Historical Fixture Provenance: v4-editable-text.json

## Finding
- Finding ID: A01
- Target Milestone: Milestone 1 (Artwork Preservation)
- Acceptance Test: ART-001, ART-002, ART-003

## Context
In commit `0de05ae`, non-destructive vector typography fields (`rotationDegrees`, `scaleX`, `scaleY`, `baselineShift`, `kerning`, `tracking`) were added to `TextLayerContent`. Because `CanvasDocumentCodec` encodes defaults by default (`encodeDefaults = true`), historical documents containing editable text layers would be re-encoded with these fields present, invalidating the SHA-256 document checksum.

## Schema
This fixture represents a version 4 document saved with an editable text layer before the introduction of vector transforms.
The text layer contains strictly the historical 5-field schema:
- `text`: "Historical Typography"
- `style`: Standard font styling (fontFamily, fontSize, etc.)
- `color`: Int color ARGB
- `x`: 10.0
- `y`: 20.0

Checksum: `deabd779490e744bc0a3f66085346a4ab4e1b2e492189407ed30c60d12aa7188`
