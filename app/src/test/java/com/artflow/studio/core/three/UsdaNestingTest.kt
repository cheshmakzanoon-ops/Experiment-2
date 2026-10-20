package com.artflow.studio.core.three

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsdaNestingTest {
    @Test
    fun primsNestedFarTooDeepAreRefusedWithAMessageNotAStackOverflow() {
        // Each prim opens a block inside the one before it. Without a bound this recursion runs out of stack.
        val depth = 10_000
        val text = "#usda 1.0\n" + "def Xform \"p\" {\n".repeat(depth) + "}\n".repeat(depth)
        val error = assertThrows(IllegalArgumentException::class.java) { UsdaParser.parse(text) }
        assertEquals("The USD text is nested too deeply", error.message)
    }

    @Test
    fun primsWithinTheBoundStillParse() {
        val depth = 30
        val text = "#usda 1.0\n" + "def Xform \"p\" {\n".repeat(depth) + "}\n".repeat(depth)
        // The layer root plus one spec per prim.
        assertEquals(depth + 1, UsdaParser.parse(text).size)
    }
}
