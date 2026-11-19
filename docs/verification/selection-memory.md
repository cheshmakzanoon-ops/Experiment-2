# Selection and history memory boundaries

## Retained history policy

`CanvasRepositoryImpl` applies one allowance to the combined undo and redo stacks:

- At most 30 snapshots in total.
- At most `min(192 MiB, maximum application heap / 4)` of retained pixel and
  selection backing arrays, using the injected `CanvasRasterBudget` in tests.
- Each distinct `IntArray` used by layer pixels or masks costs four bytes per
  element. Each distinct selection `ByteArray` costs one byte per element.
- Shared backing arrays count once across both stacks, including arrays shared
  by different buffer wrappers. Snapshot count and array identity are separate
  constraints.

The repository checks these limits after an edit records history, after undo,
and after redo. It removes the farthest available entry from the longer stack.
When the stack lengths match, it prefers retaining the inverse of the latest
navigation action. It never removes a nearer entry while retaining a more distant
entry in the same direction.

**A complete snapshot can exceed the allowance by itself. In that case the
repository drops that snapshot, and the artwork can have no undo available.**
The edit and the current artwork remain intact. Keeping one oversized entry
would defeat the memory cap; the implementation does not promise a minimum
number of undo steps. Large artworks therefore have shorter history than small
ones, even when both artworks can be opened on the device.

## Selection ownership

Selections held inside history are immutable by convention: repository code
replaces an active selection instead of modifying its coverage. Restoring a
snapshot can consequently share its selection array internally. Public setters
copy incoming masks, and public getters return copies. Mutating either a mask
previously supplied to the repository or a mask obtained after undo/redo cannot
change current or retained historical coverage.

## Saved selection admission

Saved selection slots have two separate allowances in
[`CanvasRasterBudget`](../../app/src/main/java/com/artflow/studio/data/repository/canvas/CanvasRasterBudget.kt):

- **Retained memory:** at most `maximum application heap / 8` for both the
  repository and UI copies. A slot uses one coverage byte per canvas pixel in
  each copy. This check applies to incoming documents as well as slot edits and
  runs before decoding slot coverage or constructing the owned replacement set.
- **Serialized metadata:** new or changed slot sets reserve their worst-case
  encoded size within 24 MiB of the 32 MiB document limit, leaving 8 MiB for
  other metadata. The calculation includes zlib expansion, the selection
  header, Base64 expansion, and JSON array punctuation. Tests may narrow this
  allowance but cannot widen the production ceiling.

The encoded allowance is conservative: a new slot set can be rejected even if
its particular coverage would compress enough. For example, one 4096 × 4096
mask fits the storage allowance, while eight 2048 × 2048 masks do not, even
though their two retained copies fit the saved-slot allowance on a 512 MiB heap.
Memory admission remains independent, so passing the format check alone does
not mean a slot fits a device.

Existing documents have already passed the full metadata size check. Loading
therefore uses the heap allowance without applying the new worst-case encoding
rule. Readable, sufficiently compressed older slot sets remain accessible when
they fit the device's memory. An unchanged set or a strict removal of existing
slots is also permitted above the new encoding allowance. The removal check
accounts for duplicate masks; an unrelated or duplicated replacement cannot
use this exception.

The repository checks admission and constructs its owned copies before changing
the saved slot list. The UI publishes accepted slots only after the repository
accepts them, so rejection preserves both views and reports the applicable
limit. Successful autosave recovery refreshes the UI slots before further edits,
including when the recovered document contains no slots.

## Metadata publication

[`ProjectStorage`](../../app/src/main/java/com/artflow/studio/data/local/ProjectStorage.kt)
validates the header, structure, and complete encoded document size before
replacing either the ordinary or autosave manifest. Both readers and writers
use the same 32 MiB production ceiling; test injection can only narrow it.
Writes rejected by these checks preserve the previous manifests and their recovery relationship.

The 8 MiB reserve is headroom, not a proof that every other possible document
field will fit. The full-document publication check remains necessary. Encoding
currently builds its checksum and JSON representations in memory before the
final byte-size check; this guard does not provide streaming serialization or
an encoding-heap ceiling.

## What this policy does not establish

This is a bound on arrays retained by history, not a total-process heap guarantee.
Live artwork, rendering, codec and replacement buffers, snapshot object overhead,
and other application state also consume memory. Device qualification must still
measure actual peak heap and drawing behavior for supported artwork sizes.

## Regression coverage

- [`CanvasBufferAccountingTest`](../../app/src/test/java/com/artflow/studio/data/repository/canvas/CanvasBufferAccountingTest.kt)
  covers backing-array identity across raster wrappers and selections.
- [`CanvasSelectionBudgetTest`](../../app/src/test/java/com/artflow/studio/data/repository/canvas/CanvasSelectionBudgetTest.kt)
  covers memory and encoded-size arithmetic, boundaries, and oversized inputs.
- [`CanvasSelectionMemoryTest`](../../app/src/test/java/com/artflow/studio/data/repository/canvas/CanvasSelectionMemoryTest.kt)
  covers selection-only history growth with shared rasters, retained bytes during
  navigation, the single oversized snapshot boundary, rejected slot/load
  atomicity, and public mask ownership after history restoration.
- [`CanvasSavedSelectionStorageBudgetTest`](../../app/src/test/java/com/artflow/studio/data/repository/canvas/CanvasSavedSelectionStorageBudgetTest.kt)
  covers rejected additions and the legacy-set no-op/removal exception.
- [`SavedSelectionsTest`](../../app/src/test/java/com/artflow/studio/presentation/ui/viewmodel/SavedSelectionsTest.kt)
  covers UI publication order, rejection, messages, and owned mask copies.
- [`CanvasRecoverySelectionsTest`](../../app/src/test/java/com/artflow/studio/presentation/CanvasRecoverySelectionsTest.kt)
  covers recovered slot replacement and removal through the actual recovery
  callback, followed by saving and reopening another slot edit.
- [`ProjectMetadataBoundsTest`](../../app/src/test/java/com/artflow/studio/data/local/ProjectMetadataBoundsTest.kt)
  covers exact byte boundaries, reader/writer parity, and preservation of
  committed ordinary and recovery manifests when validation fails.

These are focused JVM regressions. Their presence does not establish that a
particular revision has passed them or replace the required device campaigns;
execution results belong in the exact-revision verification record.
