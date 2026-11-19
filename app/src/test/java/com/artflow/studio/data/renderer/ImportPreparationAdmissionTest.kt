package com.artflow.studio.data.renderer

import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.ImportMemoryBudget
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.ModelPackage
import com.artflow.studio.core.three.PreparedModelImport
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises the real source and preparation entry points without allocating large images. */
class ImportPreparationAdmissionTest {
    @Test
    fun aStalledProviderRejectsOtherSourcesBeforeOpeningOrInspectingThem() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        val worker = Executors.newSingleThreadExecutor()
        val preparing =
            worker.submit<PixelBuffer?> {
                BitmapPixelBridge.decodeImportedSource(
                    {
                        object : ByteArrayInputStream(byteArrayOf()) {
                            override fun read(
                                buffer: ByteArray,
                                offset: Int,
                                length: Int,
                            ): Int {
                                reading.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                                return -1
                            }

                            override fun close() {
                                closed.set(true)
                                super.close()
                            }
                        }
                    },
                    64,
                    8 * MIB,
                )
            }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertFalse(preparing.isDone)
            var opened = false
            val rejected =
                assertThrows(IllegalStateException::class.java) {
                    BitmapPixelBridge.decodeImportedSource({
                        opened = true
                        null
                    }, 64, 8 * MIB)
                }
            assertFalse(opened)
            assertTrue(rejected.message.orEmpty().contains("try again"))
            var inspected = false
            assertThrows(IllegalStateException::class.java) {
                PreparedModelImport.prepare(source(), { error("Rejected model must not decode") }, {
                    inspected = true
                    2 to 2
                })
            }
            assertFalse(inspected)

            // Saved artwork loading remains exact-sized and independent of the import admission.
            val native = PixelBuffer(2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()))
            val restored = requireNotNull(BitmapPixelBridge.fromEncodedBytes(PngCodec.encode(native)))
            assertEquals(2, restored.width)
            assertEquals(1, restored.height)
            assertArrayEquals(native.pixels, restored.pixels)
        } finally {
            release.countDown()
            try {
                assertNull(preparing.get(5, TimeUnit.SECONDS))
                assertTrue(closed.get())
            } finally {
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun individuallyAffordableModelsCannotConcurrentlySpendTheSameHeadroom() {
        val budget = ImportMemoryBudget.availableBytes(96 * MIB, 64 * MIB, 0)
        val individualPeak = 960L * 960 * 16 + MIB + 960 * 16 + 3 + OBJ.length * 2
        assertTrue(individualPeak < budget)
        assertTrue(individualPeak * 3 > 32 * MIB)
        val decoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CancellationException("Stop the admitted decoder before any pixel allocation")
        val worker = Executors.newSingleThreadExecutor()
        val preparing =
            worker.submit<PreparedModelImport> {
                PreparedModelImport.prepare(
                    source(),
                    {
                        decoding.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        throw stopped
                    },
                    { 960 to 960 },
                    maxSize = 1,
                    memoryBudgetBytes = budget,
                )
            }
        try {
            assertTrue(decoding.await(5, TimeUnit.SECONDS))
            repeat(2) {
                var inspected = false
                assertThrows(IllegalStateException::class.java) {
                    PreparedModelImport.prepare(
                        source(),
                        { error("An overlapping model must not allocate pixels") },
                        {
                            inspected = true
                            960 to 960
                        },
                        maxSize = 1,
                        memoryBudgetBytes = budget,
                    )
                }
                assertFalse(inspected)
            }
        } finally {
            release.countDown()
            try {
                val failure = assertThrows(ExecutionException::class.java) { preparing.get(5, TimeUnit.SECONDS) }
                assertSame(stopped, failure.cause)
            } finally {
                worker.shutdownNow()
            }
        }
        assertNextWorkerCanOpenSource()
    }

    @Test
    fun modelPreparationCanNestThePlatformSourceDecoderAndReleaseBothAdmissions() {
        var opened = false
        val prepared =
            PreparedModelImport.prepare(
                source(),
                {
                    assertNull(
                        BitmapPixelBridge.decodeImportedSource({
                            opened = true
                            null
                        }, 64, 8 * MIB),
                    )
                    PixelBuffer.filled(2, 2, -1)
                },
                { 2 to 2 },
            )
        assertTrue(opened)
        assertEquals(2, prepared.width)
        assertArrayEquals(IntArray(4) { -1 }, requireNotNull(prepared.color).pixels)
        assertNextWorkerCanOpenSource()
    }

    @Test
    fun failedOrCancelledProviderReadsCloseAndReleaseAdmissionForAnotherWorker() {
        val failures =
            listOf(
                IOException("Provider failed"),
                CancellationException("Provider cancelled"),
                AssertionError("Provider failed unexpectedly"),
                OutOfMemoryError("Injected allocation boundary; no large allocation occurs"),
            )
        for (failure in failures) {
            var closed = false
            val actual =
                runCatching {
                    BitmapPixelBridge.decodeImportedSource(
                        {
                            object : ByteArrayInputStream(byteArrayOf(1)) {
                                override fun read(
                                    buffer: ByteArray,
                                    offset: Int,
                                    length: Int,
                                ): Int = throw failure

                                override fun close() {
                                    closed = true
                                    super.close()
                                }
                            }
                        },
                        64,
                        8 * MIB,
                    )
                }.exceptionOrNull()
            if (failure is OutOfMemoryError) {
                assertTrue(actual is IllegalArgumentException)
                assertSame(failure, actual?.cause)
            } else {
                assertSame(failure, actual)
            }
            assertTrue(closed)
            assertNextWorkerCanOpenSource()
        }
    }

    @Test
    fun anUnexpectedModelDecoderErrorReleasesAdmissionWithoutSwallowingIt() {
        val failure = AssertionError("Injected texture decoder failure")
        val actual = runCatching { PreparedModelImport.prepare(source(), { throw failure }, { 2 to 2 }) }.exceptionOrNull()
        assertSame(failure, actual)
        assertNextWorkerCanOpenSource()
    }

    private fun assertNextWorkerCanOpenSource() {
        val opened = AtomicBoolean()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result =
                worker.submit<PixelBuffer?> {
                    BitmapPixelBridge.decodeImportedSource({
                        opened.set(true)
                        null
                    }, 64, 8 * MIB)
                }
            assertNull(result.get(5, TimeUnit.SECONDS))
            assertTrue(opened.get())
        } finally {
            worker.shutdownNow()
        }
    }

    private fun source(): ModelPackage.Contents = ModelPackage.Contents(OBJ, byteArrayOf(1, 2, 3))

    private companion object {
        const val MIB = 1024L * 1024
        const val OBJ = "v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0 1\nf 1/1 2/2 3/3\n"
    }
}
