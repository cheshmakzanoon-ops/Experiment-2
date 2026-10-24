# Native compound-brush mode precedence — 24 October 2026

This narrow static inspection establishes how the supplied reference chooses its serialized
compound-brush mode. It does not establish the complete compound rendering equations or pixel parity.

## Reference and method

- IPA SHA-256: `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`.
- Executable: `Payload/Procreate.app/Procreate`, 19,119,424 bytes.
- Executable SHA-256: `76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b`.
- Reused `tools/ipa_native_contracts.py` for bounded Mach-O reads, Objective-C metadata,
  chained pointer resolution, CFString references and called-selector verification.
- Capstone 5.0.6 decoded the bounded ARM64 instruction sequence. No reference code was executed.

## Verified contract

`-[ValkyrieBrush initWithCoder:]` starts at `0x100a3f508`. Its mode-selection sequence does the following:

| Address | Observed operation |
| --- | --- |
| `0x100a414a4` | Resolves the CFString `dualBlendModeExtended` into register x21. |
| `0x100a414b4` | Calls `containsValueForKey:` with that key. |
| `0x100a414b8` | Branches directly to integer decoding when the extended key exists, including zero. |
| `0x100a414bc` | Otherwise resolves the legacy CFString `dualBlendMode`. |
| `0x100a414cc` | Calls `containsValueForKey:` for the legacy key. |
| `0x100a414dc` | Calls `decodeIntegerForKey:` with the selected key. |
| `0x100a414e4` | Supplies integer zero when both keys are absent. |
| `0x100a414e8` | Stores the selected integer at instance offset `0x520`. |
| `0x100a4695c` | The `dualBlendMode` getter directly loads offset `0x520` and returns. |

Thus the effective source identifier is **extended field, otherwise legacy field, otherwise zero**.
This follows the branches and shared stored/getter field; it is not inferred from field names.

The importer now follows this precedence. Export retains both original scalar keys for an unchanged
extended mode. Editing the local combine mode continues to remove the overriding extended key.
Synthetic regressions cover extended precedence, explicit zero, legacy fallback, missing-key default,
retention of distinct legacy/extended values, and replacement after editing.

## Limits

This contract identifies the active source value. It does not equate the archive identifiers with GPU
blend IDs, prove the three existing local combine approximations, or validate rendered output inside
Procreate. Shader-ID correspondence and compound stroke synthesis require separate evidence.
The source archive, executable, shader library and disassembly are not included in the repository.
