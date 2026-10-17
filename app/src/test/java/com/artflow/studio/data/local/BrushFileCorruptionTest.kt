package com.artflow.studio.data.local

import com.artflow.studio.core.export.Corruption
import com.artflow.studio.core.export.TestPng
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brush files under corruption: a shared `.artbrush` (JSON with an embedded image) and a Procreate `.brushset`
 * zip. A damaged file must import what it can or be refused with IllegalArgumentException (the JSON decoder's
 * own failures are IllegalArgumentException too).
 */
class BrushFileCorruptionTest {
    private fun refusal(failure: Throwable) = failure is IllegalArgumentException

    @Test
    fun aCorruptedSharedBrushFileIsDecodedOrRefused() {
        val tile = CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { (it % 251).toByte() })
        CustomGrains.register("custom-7e2a", tile)
        val text =
            BrushFiles.encode(
                listOf(SavedBrush("fuzz", "Fuzz brush", BrushParams(size = 33f, shapeId = "custom-7e2a", textureId = null))),
            )
        Corruption.check("artbrush", text.toByteArray(Charsets.UTF_8), seed = 5001L, accepted = ::refusal) { bytes ->
            BrushFiles.decode(String(bytes, Charsets.UTF_8))
        }
    }

    /** A set cut off inside its first entry header used to surface the zip layer's EOFException. */
    @Test
    fun aTruncatedBrushSetIsRefusedAsDamaged() {
        val set = requireNotNull(javaClass.getResourceAsStream("/procreate/fixture.brushset")).use { it.readBytes() }
        val failure = runCatching { BrushArchives.read(set.copyOf(30)) { null } }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalArgumentException)
    }

    @Test
    fun aCorruptedBrushSetArchiveIsReadOrRefused() {
        val set = requireNotNull(javaClass.getResourceAsStream("/procreate/fixture.brushset")).use { it.readBytes() }
        // The test decoder stands in for the platform's, which returns null for damaged images rather than throwing.
        val decode: (ByteArray) -> PixelBuffer? = { runCatching { TestPng.decode(it) }.getOrNull() }
        Corruption.check("brushset", set, seed = 5002L, accepted = ::refusal) { bytes ->
            BrushArchives.read(bytes, decode)
        }
    }
}
