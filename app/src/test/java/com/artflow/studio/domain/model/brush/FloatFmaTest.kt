package com.artflow.studio.domain.model.brush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Random

class FloatFmaTest {
    @Test fun cancellationAndDoubleRoundingMatchTheIndependentJdkOracle() {
        val aboveOne = Float.fromBits(0x3f800001)
        val belowOne = Float.fromBits(0x3f7ffffe)
        assertResult(aboveOne, belowOne, -1f)
        assertNotEquals(0f, FloatFma.compute(aboveOne, belowOne, -1f))

        val a = Math.scalb(aboveOne, -12)
        val b = Math.scalb(belowOne, -12)
        val roundedTwice = (a.toDouble() * b + aboveOne).toFloat()
        assertNotEquals(Math.fma(a, b, aboveOne).toRawBits(), roundedTwice.toRawBits())
        assertResult(a, b, aboveOne)
        assertResult(-a, b, -aboveOne)
    }

    @Test fun overflowMidpointsRemainFiniteWhenTheExactResidualRequiresIt() {
        val a = Math.scalb(Float.fromBits(0x3f800001), 51)
        val b = Math.scalb(Float.fromBits(0x3f7ffffe), 52)
        assertEquals(Float.POSITIVE_INFINITY, (a.toDouble() * b + Float.MAX_VALUE).toFloat(), 0f)
        assertEquals(Float.MAX_VALUE, FloatFma.compute(a, b, Float.MAX_VALUE), 0f)
        assertResult(a, b, Float.MAX_VALUE)
        assertResult(-a, b, -Float.MAX_VALUE)
    }

    @Test fun signsSubnormalsTiesAndNonfiniteValuesMatchTheJdkOracle() {
        val values =
            listOf(
                0f,
                -0f,
                Float.MIN_VALUE,
                -Float.MIN_VALUE,
                Float.fromBits(2),
                -Float.fromBits(2),
                Float.fromBits(0x007fffff),
                Float.fromBits(0x00800000),
                -Float.fromBits(0x00800000),
                Math.scalb(1f, -75),
                Math.scalb(1f, -74),
                Math.scalb(1f, -24),
                0.5f,
                -0.5f,
                1f,
                -1f,
                Float.fromBits(0x3f800001),
                Float.fromBits(0x3f7ffffe),
                Math.scalb(1f, 103),
                Float.MAX_VALUE,
                -Float.MAX_VALUE,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
                Float.NaN,
            )
        for (a in values) for (b in values) for (c in values) assertResult(a, b, c)
        // Half the minimum subnormal with exact residuals on either side and both signs.
        val half = Math.scalb(1f, -75)
        for (neighbor in listOf(Float.fromBits(half.toRawBits() - 1), half, Float.fromBits(half.toRawBits() + 1))) {
            for (sign in listOf(1f, -1f)) assertResult(half * sign, neighbor, 0f)
        }
    }

    @Test fun reproducibleExponentSpreadMatchesTheJdkOracle() {
        val random = Random(0x5f11e1024)
        repeat(100_000) {
            assertResult(Float.fromBits(random.nextInt()), Float.fromBits(random.nextInt()), Float.fromBits(random.nextInt()))
        }
    }

    private fun assertResult(
        a: Float,
        b: Float,
        c: Float,
    ) {
        // Math.fma is deliberately confined to JVM tests: Android exposes it only at API 33.
        val expected = Math.fma(a, b, c)
        val actual = FloatFma.compute(a, b, c)
        assertEquals("fma($a, $b, $c)", expected.toBits(), actual.toBits())
    }
}
