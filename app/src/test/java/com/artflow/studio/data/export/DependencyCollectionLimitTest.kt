package com.artflow.studio.data.export

import com.artflow.studio.data.local.CanvasDocumentCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DependencyCollectionLimitTest {
    @Test
    fun dependenciesAtTheSupportedCollectionLimitsRemainReadable() {
        assertEquals(CanvasDocumentCodec.MAX_LAYERS, ArtflowPackage.MAX_FONT_DEPENDENCIES)
        val dependencies =
            ArtflowPackage.Dependencies(
                fonts = List(ArtflowPackage.MAX_FONT_DEPENDENCIES) { ArtflowPackage.PackagedFont("font:Font$it.ttf", "Font $it") },
                grainIds = List(ArtflowPackage.MAX_GRAIN_DEPENDENCIES) { "custom-${it.toString(16)}" },
            )

        assertEquals(dependencies, ArtflowPackage.decodeDependencies(ArtflowPackage.encodeDependencies(dependencies)))
    }

    @Test
    fun duplicateDeclarationsCannotBypassTheFontCountLimit() {
        val font = ArtflowPackage.PackagedFont("font:Poster.ttf", "Poster")
        assertThrows(IllegalArgumentException::class.java) {
            ArtflowPackage.Dependencies(fonts = List(ArtflowPackage.MAX_FONT_DEPENDENCIES + 1) { font })
        }
        val fonts = List(ArtflowPackage.MAX_FONT_DEPENDENCIES + 1) { """{"id":"font:Poster.ttf","label":"Poster"}""" }
        val bytes = """{"fonts":[${fonts.joinToString(",")}]}""".toByteArray()

        val failure = assertThrows(IllegalArgumentException::class.java) { ArtflowPackage.decodeDependencies(bytes) }
        assertEquals("Too many font dependencies", failure.message)
    }

    @Test
    fun duplicateDeclarationsCannotBypassTheGrainCountLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtflowPackage.Dependencies(grainIds = List(ArtflowPackage.MAX_GRAIN_DEPENDENCIES + 1) { "custom-a" })
        }
        val grains = List(ArtflowPackage.MAX_GRAIN_DEPENDENCIES + 1) { "\"custom-a\"" }
        val bytes = """{"grainIds":[${grains.joinToString(",")}]}""".toByteArray()

        val failure = assertThrows(IllegalArgumentException::class.java) { ArtflowPackage.decodeDependencies(bytes) }
        assertEquals("Too many grain dependencies", failure.message)
    }
}
