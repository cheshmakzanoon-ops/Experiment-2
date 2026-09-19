package com.artflow.studio.core.canvas

import kotlin.math.abs
import kotlin.random.Random

/** Shared by JUnit and the dependency-free local runner; both execute the real production mapper. */
object ReferenceViewportChecks {
    fun letterboxingAndEdges() {
        val wide = ReferenceViewport(200, 100, 400f, 400f)
        check(wide.left == 0f && wide.top == 100f && wide.scale == 2f)
        check(wide.pixelAt(0f, 99f) == null)
        check(wide.pixelAt(0f, 100f) == (0 to 0))
        check(wide.pixelAt(399f, 299f) == (199 to 99))
        check(wide.pixelAt(400f, 200f) == null)
        check(wide.pixelAt(200f, 300f) == null)
        check(wide.pixelAt(-0.01f, 200f) == null)
        val tall = ReferenceViewport(100, 200, 400f, 400f)
        check(tall.left == 100f && tall.top == 0f)
        check(tall.pixelAt(99f, 0f) == null)
        check(tall.pixelAt(100f, 0f) == (0 to 0))
        check(tall.pixelAt(300f, 0f) == null)
    }

    fun boundedPanAndFit() {
        val fit = ReferenceViewport(200, 100, 400f, 400f)
        check(fit.panBy(1000f, -1000f) == fit)
        val enlarged = fit.zoomBy(4f).panBy(10000f, -10000f)
        check(enlarged.left == 0f && enlarged.top == -400f)
        check(enlarged.pixelAt(0f, 0f) == (0 to 50))
        check(enlarged.fit() == fit)
        check(fit.zoomBy(1000f).zoom == ReferenceViewport.MAX_ZOOM)
        check(enlarged.zoomBy(0.0001f) == fit)
    }

    fun zoomCentroidIsStable() {
        val before = ReferenceViewport(100, 100, 400f, 400f)
        val after = before.zoomBy(2f, 101f, 139f)
        check(before.pixelAt(101f, 139f) == after.pixelAt(101f, 139f))
        check(abs((101f - before.left) / before.scale - (101f - after.left) / after.scale) < 0.0001f)
        check(abs((139f - before.top) / before.scale - (139f - after.top) / after.scale) < 0.0001f)
    }

    fun invalidInputCannotCorruptTheMapping() {
        val value = ReferenceViewport(200, 100, 400f, 400f)
        check(value.pixelAt(Float.NaN, 20f) == null)
        check(value.pixelAt(20f, Float.POSITIVE_INFINITY) == null)
        check(value.zoomBy(Float.NaN) == value)
        check(value.zoomBy(-1f) == value)
        check(value.zoomBy(2f, Float.NaN) == value)
        check(value.panBy(Float.POSITIVE_INFINITY, 2f) == value)
        check(runCatching { value.copy(viewWidth = 0f) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { value.copy(imageWidth = 0) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { value.copy(zoom = Float.NaN) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { value.copy(panY = Float.NaN) }.exceptionOrNull() is IllegalArgumentException)
    }

    fun resizedViewportKeepsTheImageReachable() {
        val original = ReferenceViewport(1024, 1024, 500f, 500f).zoomBy(8f).panBy(900f, 900f)
        val resized = original.copy(viewWidth = 70f, viewHeight = 80f)
        check(resized.left <= 0f && resized.top <= 0f)
        check(resized.left + resized.displayWidth >= resized.viewWidth)
        check(resized.top + resized.displayHeight >= resized.viewHeight)
        check(resized.pixelAt(35f, 40f) != null)
    }

    fun renderedPixelCentresRoundTrip() {
        val random = Random(907)
        repeat(10_000) {
            val width = random.nextInt(1, 1025)
            val height = random.nextInt(1, 1025)
            val view =
                ReferenceViewport(width, height, random.nextInt(80, 700).toFloat(), random.nextInt(80, 700).toFloat())
                    .zoomBy(random.nextFloat() * 7f + 1f)
                    .panBy(random.nextFloat() * 1000f - 500f, random.nextFloat() * 1000f - 500f)
            val x = random.nextInt(width)
            val y = random.nextInt(height)
            val screenX = view.left + (x + 0.5f) * view.scale
            val screenY = view.top + (y + 0.5f) * view.scale
            if (screenX in 0f..<view.viewWidth && screenY in 0f..<view.viewHeight) {
                check(view.pixelAt(screenX, screenY) == (x to y))
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        letterboxingAndEdges()
        boundedPanAndFit()
        zoomCentroidIsStable()
        invalidInputCannotCorruptTheMapping()
        resizedViewportKeepsTheImageReachable()
        renderedPixelCentresRoundTrip()
        println("PASS reference-viewport: 6 groups, including 10000 deterministic generated mappings")
    }
}
