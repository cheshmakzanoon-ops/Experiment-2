package com.artflow.studio.data.local

import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrushFileSnapshotTest {
    @Test
    fun aCallerReplacementCannotSeparateTheLibraryFromItsPackagedTexture() {
        val firstTile = tile(19)
        val laterTile = tile(41)
        val firstId = CustomGrains.idFor(firstTile)
        val laterId = CustomGrains.idFor(laterTile)
        CustomGrains.register(firstId, firstTile)
        CustomGrains.register(laterId, laterTile)
        try {
            val first = SavedBrush("snapshot", "First brush", BrushParams(textureId = firstId))
            val later = SavedBrush("snapshot", "Later brush", BrushParams(textureId = laterId))
            val changing =
                object : AbstractList<SavedBrush>() {
                    override val size = 1
                    private var reads = 0

                    override fun get(index: Int): SavedBrush = if (reads++ == 0) first else later
                }
            val stableWire = wire(BrushFiles.encode(listOf(first)))
            val changingWire = wire(BrushFiles.encode(changing))
            val (brushes, images) = BrushFiles.decode(changingWire)

            assertEquals(listOf(first), brushes)
            assertEquals(setOf(firstId), images.keys)
            assertArrayEquals(firstTile.values, images.getValue(firstId).values)
            assertEquals(stableWire, changingWire)
            assertArrayEquals(firstTile.values, requireNotNull(CustomGrains.get(firstId)).values)
            assertArrayEquals(laterTile.values, requireNotNull(CustomGrains.get(laterId)).values)
        } finally {
            CustomGrains.removeIfSame(firstId, firstTile)
            CustomGrains.removeIfSame(laterId, laterTile)
        }
    }

    @Test
    fun reportedOversizedInputIsRefusedBeforeReadingOrCopyingAnyBrush() {
        var reads = 0
        val oversized =
            object : AbstractList<SavedBrush>() {
                override val size = Int.MAX_VALUE

                override fun get(index: Int): SavedBrush {
                    reads++
                    error("Oversized sharing must not read a brush")
                }

                override fun iterator(): Iterator<SavedBrush> = error("Oversized sharing must not start copying")
            }

        assertThrows(IllegalArgumentException::class.java) { BrushFiles.encode(oversized) }
        assertEquals(0, reads)
    }

    @Test
    fun aGrowingIteratorCannotCopyPastTheSupportedBrushCount() {
        var reads = 0
        val growing =
            object : AbstractList<SavedBrush>() {
                override val size = 1

                override fun get(index: Int): SavedBrush = error("The test supplies its own growing iterator")

                override fun iterator(): Iterator<SavedBrush> =
                    object : Iterator<SavedBrush> {
                        override fun hasNext() = reads < BrushLibraryCodec.MAX_BRUSHES + 2

                        override fun next(): SavedBrush {
                            if (!hasNext()) throw NoSuchElementException()
                            return SavedBrush("growing-${reads++}", "Growing list", BrushParams())
                        }
                    }
            }

        assertThrows(IllegalArgumentException::class.java) { BrushFiles.encode(growing) }
        assertEquals(BrushLibraryCodec.MAX_BRUSHES + 1, reads)
    }

    private fun tile(value: Byte) = CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { value })

    private fun wire(encoded: String): String =
        encoded.encodeToByteArray(throwOnInvalidSequence = true).decodeToString(throwOnInvalidSequence = true)
}
