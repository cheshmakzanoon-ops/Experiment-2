package com.artflow.studio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.text.TextLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the platform grapheme matcher on every supported emulator, including API 26. */
@RunWith(AndroidJUnit4::class)
class TextLayoutDeviceTest {
    @Test
    fun wrappedAndVerticalTextKeepUnicodeClustersOnTheDeviceRuntime() {
        val clusters = listOf("e\u0301", "\u2764\uFE0F", "👍🏽", "👩‍💻", "🇨🇦")
        val text = clusters.joinToString("")
        assertEquals(clusters, TextLayout.glyphs(text))
        assertEquals(clusters, TextLayout.wrap(text, 1f, TextLayout.TextStyle()) { TextLayout.glyphs(it).size.toFloat() })
        assertEquals(listOf("  one  two\t"), TextLayout.wrap("  one  two\t", 50f, TextLayout.TextStyle()) { it.length.toFloat() })
    }
}
