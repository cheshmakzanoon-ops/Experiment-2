package com.artflow.studio

import android.graphics.Path
import androidx.graphics.path.PathIterator
import androidx.graphics.path.PathSegment
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The 16 KB lane must actually load the packaged library, not merely launch the activity. */
@RunWith(AndroidJUnit4::class)
class NativeLibraryCompatibilityTest {
    @Test
    fun packagedGraphicsLibraryLoadsAndIteratesPaths() {
        // PathIterator can select a platform implementation on newer APIs. Explicit loading
        // still exercises Bionic's real relocations and RELRO protection on every matrix lane.
        System.loadLibrary("androidx.graphics.path")
        repeat(128) {
            val path =
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(8f, 8f)
                    quadTo(10f, 12f, 14f, 16f)
                    cubicTo(18f, 20f, 22f, 24f, 26f, 28f)
                    close()
                }
            val types = PathIterator(path).asSequence().map { it.type }.toList()
            assertEquals(
                listOf(
                    PathSegment.Type.Move,
                    PathSegment.Type.Line,
                    PathSegment.Type.Quadratic,
                    PathSegment.Type.Cubic,
                    PathSegment.Type.Close,
                ),
                types,
            )
        }
    }
}
