# Native artifact preflight: RELRO range correction

This records a verifier repair, not a native-library exemption or a production certification.

## Inspected binary evidence

The exact debug APK and release AAB built at `9dd0ccd81c174bd539eb7cfacd748ebe41f939b7`
were downloaded from Android CI run `35224555562`, artifact `10498513433`.
Both contain the same AndroidX Graphics Path 1.1.0 native library bytes:

| ABI | Bytes | SHA-256 |
|---|---:|---|
| arm64-v8a | 9952 | `17ee8d0a8f3c2a7b7b2f0b4e1a93c09e4fbdd1c9bfb335fee238c2767c2c03c1` |
| x86_64 | 10104 | `c48fe70862aae15ba6fdb173d31a0285b3ae7ae2588f5d724f8d502a2208364c` |

`readelf -W -l` reports 0x4000 LOAD alignment for both. Their raw GNU_RELRO
ranges end at 0x6000; the owning RW LOAD also ends at 0x6000. Bionic rounds the
protected region to [0x4000, 0x8000). The next live writable bytes start at
0x9ed8 (arm64) or 0x9f70 (x86_64), outside the protected region. Therefore the
rounded tail is padding, not live writable storage.

The previous test `(RELRO.vaddr + RELRO.memsz) % 16384 == 0` incorrectly rejected
these layouts. It also missed an unsafe unaligned START when the end happened
to align. The replacement checks actual page-rounded protection against every
LOAD's live virtual-memory interval, rejects writable bytes outside RELRO,
rejects loss of execute permission, and rejects unmapped protected pages. It
has no dependency name/hash allowlist and retains every LOAD/ZIP alignment gate.
Multiple RELRO intervals are treated as a union when checking protected bytes.

## Regression controls

The host suite includes safe padding, writable prefix/tail collisions, a
neighboring writable LOAD, executable overlap, unmapped ranges, virtual-address
overflow, malformed headers, APK ZIP misalignment, and per-library 32/64-bit ABI
pairing. Numeric fixtures reproduce both packaged 64-bit program-header layouts;
a 16-byte expansion into their formerly harmless padding must fail.

The modified verifier passes the exact APK and AAB above. This is static evidence
only. `NativeLibraryCompatibilityTest` additionally loads the real packaged
library explicitly on all device-test lanes and repeatedly iterates real paths.
Its result must come from the corresponding CI report, not this document.

## Primary implementation references

- Android Bionic loader, `_phdr_table_set_gnu_relro_prot` and
  `_extend_gnu_relro_prot_end`:
  https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker_phdr.cpp
- Android 16 KB guidance, including the unsafe writable-tail example and ZIP/LOAD checks:
  https://developer.android.com/guide/practices/page-sizes
- AndroidX path iterator API:
  https://developer.android.com/reference/kotlin/androidx/graphics/path/PathIterator

The guidance's conservative raw-end rule is not used to override the loader's
actual protection behavior. Physical arm64 testing and the complete signed-release
process remain separate gates even when host checks and x86_64 emulators pass.
