# Hash-verified IPA / Mach-O static inventory

This is read-only package analysis, not source decompilation or 1:1 behavior recovery.
The user-supplied binary and licensed resources are never committed.

- Source SHA-256: `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`
- Source bytes: 777,430,382
- ZIP members: 2,327
- Expanded bytes declared: 865,713,740
- Application roots: Payload/Procreate.app
- Embedded framework names: 0
- Bundle Info.plist records: 40
- Bundle-declared executable headers: 4

## Declared bundle executables (static observation)

- `Payload/Procreate.app/PlugIns/Add-to-Procreate.appex/Add-to-Procreate`: parsed
  - architecture=arm64; encrypted=False
- `Payload/Procreate.app/PlugIns/OpenRecentArtworkWidget.appex/OpenRecentArtworkWidget`: parsed
  - architecture=arm64; encrypted=False
- `Payload/Procreate.app/PlugIns/Thumbnails.appex/Thumbnails`: parsed
  - architecture=arm64; encrypted=False
- `Payload/Procreate.app/Procreate`: parsed
  - architecture=arm64; encrypted=False

## Evidence boundaries

- Native functions and encrypted application code are not recovered.
- Executable metadata is not evidence of observed runtime behavior.
- No application code or unpacked proprietary resources redistributed.

See [the machine-readable inventory](REA_IPA_STATIC_INVENTORY.json).
Encryption flags do not disclose source code, cryptographic keys, or plaintext instructions.
This report is limited to the unencrypted metadata visible in the supplied package.

## REA CLI status

Exit code: 1 (nonzero means this IPA inspection mode did not complete successfully).
