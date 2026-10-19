package com.artflow.studio.data.local

import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Opt-in, hash-pinned source corpus. No reference code is executed or assets copied into the app. */
class ReferenceBrushCorpusTest {
    @Test fun everyReferenceBrushImportsAsOnePersistableLogicalBrush() {
        val path = System.getenv("ARTFLOW_REFERENCE_IPA")
        assumeTrue("Reference IPA not configured; synthetic tests remain mandatory", !path.isNullOrBlank())
        val file = File(requireNotNull(path))
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        assertEquals(REFERENCE_SHA256, sha)
        var brushes = 0
        var compound = 0
        var components = 0
        var signed = 0
        var curves = 0
        ZipFile(file).use { ipa ->
            val entries = ipa.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.endsWith(".brush")) continue
                val bytes = ipa.getInputStream(entry).use(BrushArchives::readInput)
                // Metadata/persistence check: bundled proprietary textures are deliberately not loaded.
                val imported = BrushArchives.read(bytes) { null }
                assertEquals(entry.name, 1, imported.size)
                val primary = imported.single()
                assertNotNull(entry.name, primary.settings)
                primary.secondary?.let { secondary ->
                    compound++
                    assertNotNull(entry.name, secondary.settings)
                }
                val params = primary.parameters { error("This check does not load images") }
                val all = listOfNotNull(params, params.dual?.params)
                components += all.size
                signed += all.count { it.pressureToSize < 0 || it.pressureToOpacity < 0 || it.velocityToSize < 0 || it.velocityToOpacity < 0 }
                curves += all.sumOf { listOfNotNull(it.importedPressure?.overall, it.importedPressure?.size, it.importedPressure?.opacity).size }
                val saved = listOf(SavedBrush("reference-$brushes", "Reference brush $brushes", params))
                assertEquals(entry.name, saved, BrushLibraryCodec.decode(BrushLibraryCodec.encode(saved)))
                assertFiniteDynamics(entry.name, all)
                brushes++
            }
        }
        assertEquals(394, brushes)
        assertEquals(140, compound)
        assertEquals(534, components)
        assertTrue(signed > 0)
        assertTrue(curves > 1_000)
        val summary = """
            reference_sha256=$sha
            brushes=$brushes
            compound_brushes=$compound
            components=$components
            components_with_signed_dynamics=$signed
            parsed_channel_curves=$curves
            metadata_import_and_persistence=PASS
            image_decode_tested=false
            reference_runtime_executed=false
            pixel_parity_verified=false
        """.trimIndent()
        File("build/reference-brush-summary.txt").also { it.parentFile?.mkdirs() }.writeText(summary + "\n")
        println(summary)
    }

    private fun assertFiniteDynamics(name: String, brushes: List<BrushParams>) {
        brushes.forEach { brush ->
            for (pressure in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                assertTrue(name, brush.calculateEffectiveSize(pressure, 5f).isFinite())
                assertTrue(name, brush.calculateEffectiveOpacity(pressure, 5f) in 0f..1f)
            }
        }
    }

    companion object {
        private const val REFERENCE_SHA256 = "7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e"
    }
}
