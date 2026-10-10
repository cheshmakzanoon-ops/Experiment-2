package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

class ProcreateNanOpacityTest {
    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/procreate/$name")).use { it.readBytes() }

    @Test
    fun aNonFiniteOpacityInTheDocumentReadsAsOpaque() {
        // The fixture's archive with every layer opacity replaced by NaN, which a binary plist can carry.
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(resource("turned.procreate").inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        entries["Document.archive"] = resource("nan-opacity.archive")
        val files =
            object : ProcreateReader.Files {
                override val names = entries.keys

                override fun read(name: String) = entries[name]
            }
        val document = ProcreateReader.open(files) { null }
        val layers = document.layers()
        assertTrue(layers.isNotEmpty())
        layers.forEach { assertEquals(it.name, 1f, it.opacity, 0f) }
    }
}
