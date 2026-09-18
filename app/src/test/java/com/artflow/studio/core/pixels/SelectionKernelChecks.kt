package com.artflow.studio.core.pixels

import java.math.BigDecimal
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Shared by JUnit and the offline Kotlin probe; every check calls the production selection kernels. */
object SelectionKernelChecks {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    fun solidAndBranchingRegions(): Int {
        val sizes = listOf(1 to 1, 4 to 4, 1 to 8192, 8192 to 1, 1024 to 1024)
        for ((width, height) in sizes) {
            check(SelectionMask.magicWand(PixelBuffer.filled(width, height, red), 0, 0, 0).isFull())
        }
        val branching = PixelBuffer(511, 31, IntArray(511 * 31) { if (it / 511 == 15 || it % 2 == 0) red else blue })
        equal(hardOracle(branching, 0, 15, 0, true), SelectionMask.magicWand(branching, 0, 15, 0, true, false).coverage)
        val diagonal = PixelBuffer(2, 2, intArrayOf(red, blue, blue, red))
        check(SelectionMask.magicWand(diagonal, 0, 0, 0).selectedPixelCount() == 1)
        check(SelectionMask.magicWand(diagonal, 0, 0, 0, false).selectedPixelCount() == 2)
        return sizes.size + 3
    }

    fun randomizedColourConnectivity(): Int {
        val random = Random(317891)
        repeat(4_000) { fixture ->
            val width = random.nextInt(1, 24)
            val height = random.nextInt(1, 24)
            val palette = IntArray(6) { random.nextInt() }
            palette[0] = 0x00FF00FF
            palette[1] = 0x000000FF
            val source = PixelBuffer(width, height, IntArray(width * height) { palette[random.nextInt(palette.size)] })
            val x = random.nextInt(width)
            val y = random.nextInt(height)
            val tolerance = listOf(-25, 0, 1, 10, 32, 64, 127, 255, 999)[fixture % 9]
            for (contiguous in listOf(true, false)) {
                val expected = hardOracle(source, x, y, tolerance, contiguous)
                val actual = SelectionMask.magicWand(source, x, y, tolerance, contiguous, false)
                equal(expected, actual.coverage, "colour fixture $fixture, contiguous=$contiguous")
            }
        }
        return 8_000
    }

    fun colourCoverageAndLimits(): Int {
        val black = 0xFF000000.toInt()
        val source = PixelBuffer(3, 1, intArrayOf(black, 0xFF280000.toInt(), black))
        equal(byteArrayOf(-1, 170.toByte(), 0), SelectionMask.magicWand(source, 0, 0, 10, true, true).coverage)
        equal(byteArrayOf(-1, 0, 0), SelectionMask.magicWand(source, 0, 0, 10, true, false).coverage)
        equal(byteArrayOf(-1, 170.toByte(), -1), SelectionMask.magicWand(source, 0, 0, 10, false, true).coverage)
        val hidden = PixelBuffer(4, 1, intArrayOf(0, 0x00FF0000, 0x000000FF, red))
        check(SelectionMask.magicWand(hidden, 0, 0, 0).selectedPixelCount() == 3)
        check(SelectionMask.colorRange(hidden, 0x00FFFFFF, 0).selectedPixelCount() == 3)
        val full = PixelBuffer.filled(4, 3, red)
        val limit = SelectionMask(2, 2, byteArrayOf(0, 128.toByte(), -1, 64))
        val limited = SelectionMask.magicWand(full, 0, 0, respectExistingSelection = limit)
        equal(byteArrayOf(0, 128.toByte(), 0, 0, -1, 64, 0, 0, 0, 0, 0, 0), limited.coverage)
        equal(byteArrayOf(0, 128.toByte(), -1, 64), limit.coverage)
        check(!SelectionMask.magicWand(full, 0, 0, respectExistingSelection = SelectionMask(1, 1)).isActive())
        for ((x, y) in listOf(-1 to 0, 4 to 0, 0 to -1, 0 to 3)) check(!SelectionMask.magicWand(full, x, y).isActive())
        val near = PixelBuffer(2, 1, intArrayOf(red, 0xFFFE0000.toInt()))
        check(SelectionMask.magicWand(near, 0, 0, 0).selectedPixelCount() == 1)
        check(SelectionMask.colorRange(near, red, 0).selectedPixelCount() == 1)
        return 14
    }

    fun randomizedMorphology(): Int {
        val random = Random(46891)
        repeat(2_000) { fixture ->
            val width = random.nextInt(1, 13)
            val height = random.nextInt(1, 13)
            val source = SelectionMask(width, height, random.nextBytes(width * height))
            val original = source.coverage.copyOf()
            val radius = random.nextInt(1, 5)
            for (amount in listOf(-radius, 0, radius)) {
                equal(morphologyOracle(source, amount), source.expanded(amount).coverage, "morphology fixture $fixture: $amount")
            }
            equal(original, source.coverage)
        }
        val full = SelectionMask(5, 4).apply { selectAll() }
        check(!full.expanded(Int.MIN_VALUE).isActive())
        check(full.expanded(Int.MAX_VALUE).isFull())
        check(!SelectionMask(5, 4).expanded(Int.MAX_VALUE).isActive())
        val one = SelectionMask(5, 4).apply { coverage[7] = 128.toByte() }
        check(one.expanded(Int.MAX_VALUE).isFull())
        val below = SelectionMask(5, 4).apply { coverage[7] = 127 }
        check(!below.expanded(Int.MAX_VALUE).isActive())
        return 8_005
    }

    fun randomizedFeathering(): Int {
        val random = Random(79651)
        repeat(2_000) { fixture ->
            val width = random.nextInt(1, 13)
            val height = random.nextInt(1, 13)
            val radius = random.nextInt(1, 9)
            val source = SelectionMask(width, height, random.nextBytes(width * height))
            val before = source.coverage.copyOf()
            val expected = featherOracle(source, radius)
            val actual = source.feathered(radius)
            for (index in expected.indices) {
                check(abs(expected[index] - actual.coverageAt(index % width, index / width)) <= 1) {
                    "feather fixture $fixture, index=$index"
                }
            }
            equal(before, source.coverage)
        }
        for (coverage in listOf(0, 1, 63, 127, 128, 254, 255)) {
            val single = SelectionMask(1, 1, byteArrayOf(coverage.toByte()))
            check(single.feathered(Int.MAX_VALUE).coverageAt(0, 0) == coverage)
            val full = SelectionMask(3, 5, ByteArray(15) { coverage.toByte() })
            equal(full.coverage, full.feathered(Int.MAX_VALUE).coverage)
        }
        val pair = SelectionMask(2, 1, byteArrayOf(0, -1)).feathered(Int.MAX_VALUE)
        check(pair.coverageAt(0, 0) in 127..128 && pair.coverageAt(1, 0) in 127..128)
        return 4_015
    }

    fun extremeAndDegenerateGeometry(): Int {
        val huge = Float.MAX_VALUE
        check(!SelectionMask.rectangle(8, 8, -huge, -huge, -1e20f, -1e20f).isActive())
        check(SelectionMask.rectangle(8, 8, -huge, -huge, huge, huge).isFull())
        check(!SelectionMask.rectangle(8, 8, 0.25f, 0f, 0.25f, 8f).isActive())
        check(!SelectionMask.rectangle(8, 8, 0f, 0.25f, 8f, 0.25f).isActive())
        check(SelectionMask.ellipse(8, 8, -huge, -huge, huge, huge).isFull())
        check(!SelectionMask.ellipse(8, 8, 1e20f, 1e20f, huge, huge).isActive())
        check(!SelectionMask.ellipse(8, 8, 0f, 0f, 0f, 8f).isActive())
        val enclosing = listOf(-huge to -huge, huge to -huge, huge to huge, -huge to huge)
        check(SelectionMask.polygon(8, 8, enclosing).isFull())
        val line = SelectionMask.fromStroke(8, 8, listOf(-huge to 4f, huge to 4f), 2f)
        val localLine = SelectionMask.fromStroke(8, 8, listOf(-100f to 4f, 100f to 4f), 2f)
        equal(localLine.coverage, line.coverage)
        val diagonal = SelectionMask.fromStroke(8, 8, listOf(-huge to -huge, huge to huge), 2f)
        val localDiagonal = SelectionMask.fromStroke(8, 8, listOf(-100f to -100f, 100f to 100f), 2f)
        equal(localDiagonal.coverage, diagonal.coverage)
        check(!SelectionMask.fromStroke(8, 8, listOf(1e20f to 1e20f, huge to huge), 2f).isActive())
        check(SelectionMask.fromCircle(8, 8, 0f, 0f, huge).isFull())
        return 12
    }

    fun randomizedEllipseCoverage(): Int {
        val random = Random(298461)
        repeat(2_000) { fixture ->
            val width = random.nextInt(1, 18)
            val height = random.nextInt(1, 18)
            val box = FloatArray(4) { random.nextInt(-20, 40).toFloat() }
            val mask = SelectionMask.ellipse(width, height, box[0], box[1], box[2], box[3])
            for (index in mask.coverage.indices) {
                val x = index % width
                val y = index / width
                val expected = ellipseOracle(box, x, y)
                check(mask.coverageAt(x, y) == expected) { "ellipse fixture $fixture at $x,$y" }
            }
            equal(mask.coverage, SelectionMask.ellipse(width, height, box[2], box[3], box[0], box[1]).coverage)
            val transpose = SelectionMask.ellipse(height, width, box[1], box[0], box[3], box[2])
            for (index in mask.coverage.indices) {
                check(mask.coverageAt(index % width, index / width) == transpose.coverageAt(index / width, index % width))
            }
        }
        val huge = Float.MAX_VALUE
        val extremes =
            listOf(
                floatArrayOf(1e20f, -huge, huge, huge),
                floatArrayOf(-huge, -huge, -1e20f, huge),
                floatArrayOf(-huge, 1e20f, huge, huge),
                floatArrayOf(-huge, -huge, huge, -1e20f),
                floatArrayOf(0f, -huge, huge, huge),
                floatArrayOf(-huge, 0f, huge, huge),
                floatArrayOf(0f, -1e20f, huge, 1e20f),
                floatArrayOf(-1e20f, 0f, 1e20f, huge),
                floatArrayOf(-huge, -huge, huge, huge),
                floatArrayOf(0f, 0f, Float.MIN_VALUE, Float.MIN_VALUE),
                floatArrayOf(0.25f, 0.25f, 0.26f, 0.26f),
                floatArrayOf(0.12f, 0.12f, 0.13f, 0.13f),
                floatArrayOf(0f, 0f, 0f, 8f),
                floatArrayOf(8f, 8f, -8f, -8f),
            )
        for ((index, box) in extremes.withIndex()) {
            equal(
                exactEllipseOracle(8, 8, box),
                SelectionMask.ellipse(8, 8, box[0], box[1], box[2], box[3]).coverage,
                "extreme ellipse $index",
            )
        }
        return 6_000 + extremes.size
    }

    fun randomizedStrokeGeometry(): Int {
        val random = Random(96831)
        repeat(2_000) { fixture ->
            val width = random.nextInt(1, 18)
            val height = random.nextInt(1, 18)
            val points = List(random.nextInt(1, 6)) { (random.nextFloat() * 50 - 20) to (random.nextFloat() * 50 - 20) }
            val radius = random.nextFloat() * 8 + 0.125f
            val mask = SelectionMask.fromStroke(width, height, points, radius)
            for (index in mask.coverage.indices) {
                val x = index % width
                val y = index / width
                val expected = strokeOracle(points, radius, x + 0.5, y + 0.5)
                check(abs(expected - mask.coverageAt(x, y)) <= 1) { "stroke fixture $fixture at $x,$y" }
            }
            equal(mask.coverage, SelectionMask.fromStroke(width, height, points.reversed(), radius).coverage, "reversed stroke $fixture")
        }
        return 4_000
    }

    fun randomizedPolygonCoverage(): Int {
        val random = Random(97171)
        repeat(1_000) { fixture ->
            val width = random.nextInt(1, 16)
            val height = random.nextInt(1, 16)
            val points = List(random.nextInt(3, 13)) { random.nextInt(-10, 26).toFloat() to random.nextInt(-10, 26).toFloat() }
            val mask = SelectionMask.polygon(width, height, points)
            for (index in mask.coverage.indices) {
                val x = index % width
                val y = index / width
                val expected = polygonOracle(points, x, y)
                check(mask.coverageAt(x, y) == expected) { "polygon fixture $fixture at $x,$y" }
            }
            equal(mask.coverage, SelectionMask.polygon(width, height, points.reversed()).coverage)
        }
        val triangle = SelectionMask.polygon(4, 4, listOf(0f to 0f, 4f to 0f, 0f to 4f))
        check(triangle.coverage.any { (it.toInt() and 255) in 1..254 })
        return 2_001
    }

    fun cancellationAndInvalidInput(): Int {
        val source = PixelBuffer.filled(64, 64, red)
        val mask = SelectionMask(64, 64).apply { selectAll() }
        val before = source.pixels.copyOf()
        val coverage = mask.coverage.copyOf()
        val operations =
            listOf<(() -> Unit) -> Any>(
                { SelectionMask.magicWand(source, 0, 0, checkActive = it) },
                { SelectionMask.colorRange(source, red, 32, checkActive = it) },
                { mask.feathered(Int.MAX_VALUE, it) },
                { mask.expanded(Int.MAX_VALUE, it) },
                { SelectionMask.rectangle(64, 64, 0f, 0f, 64f, 64f, it) },
                { SelectionMask.ellipse(64, 64, 0f, 0f, 64f, 64f, it) },
                { SelectionMask.polygon(64, 64, listOf(0f to 0f, 64f to 0f, 0f to 64f), it) },
                { SelectionMask.fromStroke(64, 64, listOf(0f to 0f, 64f to 64f), 12f, it) },
                { SelectionMask.fromAlphaOf(source, checkActive = it) },
                { SelectionMask.intersect(mask, mask, checkActive = it) },
            )
        for ((index, operation) in operations.withIndex()) {
            var polls = 0
            expect<CancellationException> {
                operation { if (++polls == (if (index == 8) 2 else 4)) throw CancellationException("controlled cancellation") }
            }
            check(source.pixels.contentEquals(before))
            equal(coverage, mask.coverage)
        }
        val invalid = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (value in invalid) {
            expect<IllegalArgumentException> { SelectionMask.rectangle(8, 8, value, 0f, 8f, 8f) }
            expect<IllegalArgumentException> { SelectionMask.ellipse(8, 8, 0f, 0f, value, 8f) }
            expect<IllegalArgumentException> { SelectionMask.polygon(8, 8, listOf(0f to 0f, value to 4f, 0f to 8f)) }
            expect<IllegalArgumentException> { SelectionMask.fromStroke(8, 8, listOf(0f to 0f, value to 4f), 2f) }
            expect<IllegalArgumentException> { SelectionMask.fromCircle(8, 8, 0f, 0f, value) }
        }
        expect<IllegalArgumentException> { SelectionMask.fromStroke(8, 8, emptyList(), 0f) }
        expect<IllegalArgumentException> { SelectionMask(65_536, 65_536) }
        return operations.size * 3 + invalid.size * 5 + 2
    }

    fun radiusIndependentWork(): Int {
        val source = SelectionMask(1024, 1024, ByteArray(1024 * 1024) { 128.toByte() })
        var polls = 0
        val feathered = source.feathered(Int.MAX_VALUE) { polls++ }
        check(feathered.coverage.all { (it.toInt() and 255) == 128 })
        check(polls in 2_048..2_055)
        polls = 0
        val expanded = source.expanded(Int.MAX_VALUE) { polls++ }
        check(expanded.isFull())
        check(polls in 2_048..2_055)
        return 4
    }

    private fun hardOracle(
        buffer: PixelBuffer,
        x: Int,
        y: Int,
        tolerance: Int,
        contiguous: Boolean,
    ): ByteArray {
        val seed = y * buffer.width + x
        val target = buffer.pixels[seed]
        val threshold = tolerance.coerceIn(0, 255).toDouble() * 3
        val eligible =
            BooleanArray(buffer.size) { index ->
                val pixel = buffer.pixels[index]
                val a = (pixel ushr 24).toDouble()
                val b = (target ushr 24).toDouble()
                var distance = (a - b) * (a - b)
                for (shift in listOf(0, 8, 16)) {
                    val difference = (((pixel ushr shift) and 255) * a - ((target ushr shift) and 255) * b) / 255.0
                    distance += difference * difference
                }
                distance <= threshold * threshold
            }
        if (!contiguous) return ByteArray(buffer.size) { if (eligible[it]) 255.toByte() else 0 }
        val selected = ByteArray(buffer.size)
        val frontier = java.util.ArrayDeque<Int>()
        frontier.addLast(seed)
        selected[seed] = 255.toByte()
        while (frontier.isNotEmpty()) {
            val index = frontier.removeFirst()
            val neighbours = ArrayList<Int>(4)
            if (index % buffer.width > 0) neighbours.add(index - 1)
            if (index % buffer.width + 1 < buffer.width) neighbours.add(index + 1)
            if (index >= buffer.width) neighbours.add(index - buffer.width)
            if (index + buffer.width < buffer.size) neighbours.add(index + buffer.width)
            for (next in neighbours) {
                if (eligible[next] && selected[next].toInt() == 0) {
                    selected[next] = 255.toByte()
                    frontier.addLast(next)
                }
            }
        }
        return selected
    }

    private fun morphologyOracle(
        source: SelectionMask,
        amount: Int,
    ): ByteArray {
        if (amount == 0) return source.coverage.copyOf()
        val radius = abs(amount)
        return ByteArray(source.coverage.size) { index ->
            var hits = 0
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (source.coverageAt(index % source.width + dx, index / source.width + dy) >= 128) hits++
                }
            }
            val selected = if (amount > 0) hits > 0 else hits == (2 * radius + 1) * (2 * radius + 1)
            if (selected) 255.toByte() else 0
        }
    }

    private fun featherOracle(
        source: SelectionMask,
        radius: Int,
    ): IntArray =
        IntArray(source.coverage.size) { index ->
            var total = 0.0
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val x = (index % source.width + dx).coerceIn(0, source.width - 1)
                    val y = (index / source.width + dy).coerceIn(0, source.height - 1)
                    total += source.coverageAt(x, y)
                }
            }
            (total / ((2 * radius + 1) * (2 * radius + 1))).roundToInt()
        }

    private fun ellipseOracle(
        box: FloatArray,
        x: Int,
        y: Int,
    ): Int {
        val rx = abs(box[2].toDouble() - box[0]) / 2.0
        val ry = abs(box[3].toDouble() - box[1]) / 2.0
        if (rx == 0.0 || ry == 0.0) return 0
        val cx = (box[0].toDouble() + box[2]) / 2.0
        val cy = (box[1].toDouble() + box[3]) / 2.0
        var count = 0
        repeat(4) { sy ->
            repeat(4) { sx ->
                val dx = (x + (sx + 0.5) / 4.0 - cx) / rx
                val dy = (y + (sy + 0.5) / 4.0 - cy) / ry
                if (dx * dx + dy * dy < 1.0) count++
            }
        }
        return (count * 255 + 8) / 16
    }

    /** Exact arithmetic, independent of the production row intervals and Double precision. */
    private fun exactEllipseOracle(
        width: Int,
        height: Int,
        box: FloatArray,
    ): ByteArray {
        val bounds = box.map { BigDecimal(it.toDouble()) }
        val xSum = bounds[0] + bounds[2]
        val ySum = bounds[1] + bounds[3]
        val widthSquared = (bounds[2] - bounds[0]).pow(2)
        val heightSquared = (bounds[3] - bounds[1]).pow(2)
        val limit = widthSquared * heightSquared
        if (limit.signum() == 0) return ByteArray(width * height)
        return ByteArray(width * height) { index ->
            var count = 0
            repeat(16) { sample ->
                val x = BigDecimal(2.0 * (index % width + (sample % 4 + 0.5) / 4.0)) - xSum
                val y = BigDecimal(2.0 * (index / width + (sample / 4 + 0.5) / 4.0)) - ySum
                if (x.pow(2) * heightSquared + y.pow(2) * widthSquared < limit) count++
            }
            ((count * 255 + 8) / 16).toByte()
        }
    }

    private fun strokeOracle(
        points: List<Pair<Float, Float>>,
        radius: Float,
        x: Double,
        y: Double,
    ): Int {
        var distance = Double.POSITIVE_INFINITY
        for (index in 0 until max(1, points.size - 1)) {
            val a = points[index]
            val b = points[min(index + 1, points.lastIndex)]
            val dx = b.first.toDouble() - a.first
            val dy = b.second.toDouble() - a.second
            val squared = dx * dx + dy * dy
            val t =
                if (squared == 0.0) {
                    0.0
                } else {
                    (((x - a.first) * dx + (y - a.second) * dy) / squared).coerceIn(0.0, 1.0)
                }
            distance = min(distance, hypot(x - (a.first + dx * t), y - (a.second + dy * t)))
        }
        return ((radius - distance).coerceIn(0.0, 1.0) * 255).toInt()
    }

    private fun polygonOracle(
        points: List<Pair<Float, Float>>,
        x: Int,
        y: Int,
    ): Int {
        var count = 0
        repeat(4) { sy ->
            repeat(4) { sx ->
                if (inside(points, x + (sx + 0.5) / 4.0, y + (sy + 0.5) / 4.0)) count++
            }
        }
        return (count * 255 + 8) / 16
    }

    private fun inside(
        points: List<Pair<Float, Float>>,
        x: Double,
        y: Double,
    ): Boolean {
        var inside = false
        var previous = points.last()
        for (point in points) {
            if ((point.second > y) != (previous.second > y)) {
                val crossing =
                    point.first + (y - point.second) * (previous.first.toDouble() - point.first) /
                        (previous.second.toDouble() - point.second)
                if (x < crossing) inside = !inside
            }
            previous = point
        }
        return inside
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        check(failure is T) { "Expected ${T::class.java.simpleName}; observed ${failure ?: "successful completion"}" }
    }

    private fun equal(
        expected: ByteArray,
        actual: ByteArray,
        description: String = "coverage comparison",
    ) {
        check(expected.contentEquals(actual)) { description }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val checks =
            linkedMapOf(
                "solid-and-branching-regions" to ::solidAndBranchingRegions,
                "randomized-colour-connectivity" to ::randomizedColourConnectivity,
                "colour-coverage-and-limits" to ::colourCoverageAndLimits,
                "randomized-morphology" to ::randomizedMorphology,
                "randomized-feathering" to ::randomizedFeathering,
                "extreme-and-degenerate-geometry" to ::extremeAndDegenerateGeometry,
                "randomized-ellipse-coverage" to ::randomizedEllipseCoverage,
                "randomized-stroke-geometry" to ::randomizedStrokeGeometry,
                "randomized-polygon-coverage" to ::randomizedPolygonCoverage,
                "cancellation-and-invalid-input" to ::cancellationAndInvalidInput,
                "radius-independent-work" to ::radiusIndependentWork,
            )
        var total = 0
        for ((name, run) in checks) {
            val count = run()
            total += count
            println("PASS $name checks=$count")
        }
        println("PASS selection-kernel-groups=${checks.size} checks=$total")
    }
}
