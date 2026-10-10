package com.artflow.studio.core.three

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsdaQuaternionTest {
    private fun scene(value: String) = "#usda 1.0\ndef Xform \"p\" {\n    quatf xformOp:orient = ($value)\n}\n"

    @Test
    fun aPartialQuaternionIsRefusedInsteadOfReadingPastItsValues() {
        val error = assertThrows(IllegalArgumentException::class.java) { UsdaParser.parse(scene("1, 2, 3, 4, 5")) }
        assertEquals("The USD text is damaged", error.message)
    }

    @Test
    fun aWholeQuaternionStillMovesItsRealPartToTheFront() {
        val stored = UsdaParser.parse(scene("1, 2, 3, 4")).values.mapNotNull { it["default"] as? UsdaParser.Numbers }
        assertEquals(listOf(listOf(2.0, 3.0, 4.0, 1.0)), stored.map { it.values.toList() })
    }
}
