package com.artflow.studio.data.export

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.PreparedModelImport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bounds retained photo/model payloads without evicting an editor's unsaved model. Only unclaimed
 * handoffs expire with their gallery owner; a claimed model and its receipt survive retryable saves.
 */
internal class PendingRasterImports(
    private val maxBytes: Long = minOf(Runtime.getRuntime().maxMemory() / 3, 256L * 1024 * 1024),
    private val maxEntries: Int = 8,
    private val claimTimeoutMs: Long = 10 * 60 * 1000L,
    private val onExpired: (Long) -> Unit = {},
) {
    private sealed interface Payload {
        val bytes: Long

        class Image(
            val value: PendingImports.PendingImage,
        ) : Payload {
            override val bytes =
                value.image.pixels.size
                    .toLong() * Int.SIZE_BYTES
        }

        class Model(
            val value: PreparedModelImport,
        ) : Payload {
            override val bytes =
                value.objText.length.toLong() * Char.SIZE_BYTES +
                    listOfNotNull(value.color, value.metallic, value.roughness).sumOf { it.pixels.size.toLong() * Int.SIZE_BYTES }
            var receipt: ModelImports.Receipt? = null
        }
    }

    private class Handoff(
        val payload: Payload,
    ) {
        lateinit var expiry: Job
        var claimed = false
    }

    private val lock = Any()
    private val entries = mutableMapOf<Long, Handoff>()
    private var retainedBytes = 0L
    private var reservations = 0

    init {
        require(maxBytes > 0 && maxEntries > 0 && claimTimeoutMs > 0)
    }

    fun putImage(
        projectId: Long,
        image: PixelBuffer,
        isPrivate: Boolean,
        owner: CoroutineScope,
    ) = put(projectId, Payload.Image(PendingImports.PendingImage(image, isPrivate)), owner)

    fun putModel(
        projectId: Long,
        prepared: PreparedModelImport,
        owner: CoroutineScope,
    ) = put(projectId, Payload.Model(prepared), owner)

    /** Lexically owned admission before creating a project; closing an unpublished reservation releases it. */
    class Reservation internal constructor(
        private val publish: (Long) -> Unit,
        private val release: () -> Unit,
    ) : AutoCloseable {
        fun publish(projectId: Long) = publish.invoke(projectId)

        override fun close() = release()
    }

    fun reserveImage(
        image: PixelBuffer,
        isPrivate: Boolean,
        owner: CoroutineScope,
    ): Reservation = reserve(Payload.Image(PendingImports.PendingImage(image, isPrivate)), owner)

    fun reserveModel(
        prepared: PreparedModelImport,
        owner: CoroutineScope,
    ): Reservation = reserve(Payload.Model(prepared), owner)

    private fun reserve(
        payload: Payload,
        owner: CoroutineScope,
    ): Reservation {
        synchronized(lock) {
            requireCapacity(payload.bytes, retainedBytes, entries.size + reservations)
            retainedBytes += payload.bytes
            reservations++
        }
        var pending = true

        fun release(): Boolean =
            synchronized(lock) {
                if (!pending) return@synchronized false
                pending = false
                retainedBytes -= payload.bytes
                reservations--
                true
            }
        return Reservation(
            publish = { projectId ->
                synchronized(lock) {
                    check(release()) { "This import reservation has already been used or cancelled" }
                    put(projectId, payload, owner)
                }
            },
            release = { release() },
        )
    }

    private fun put(
        projectId: Long,
        payload: Payload,
        owner: CoroutineScope,
    ) {
        val pending = Handoff(payload)
        val replaced =
            synchronized(lock) {
                val previous = entries[projectId]
                check(
                    previous?.claimed != true,
                ) { "This artwork has an unsaved model import. Reopen it to finish saving before importing again." }
                val remaining = retainedBytes - (previous?.payload?.bytes ?: 0)
                requireCapacity(payload.bytes, remaining, entries.size + reservations - if (previous != null) 1 else 0)
                pending.expiry =
                    owner.launch(start = CoroutineStart.LAZY) {
                        delay(claimTimeoutMs)
                        expire(projectId, pending)
                    }
                entries[projectId] = pending
                retainedBytes = remaining + payload.bytes
                previous
            }
        replaced?.expiry?.cancel()
        pending.expiry.invokeOnCompletion { cause -> if (cause != null) expire(projectId, pending) }
        pending.expiry.start()
    }

    private fun requireCapacity(
        bytes: Long,
        retained: Long,
        count: Int,
    ) {
        check(bytes <= maxBytes) { "This import needs too much memory. Reduce its image or material dimensions, then try again." }
        check(bytes <= maxBytes - retained && count < maxEntries) {
            "Too many imports are waiting to be saved. Reopen or delete unfinished imports, then try again."
        }
    }

    fun takeImage(projectId: Long): PendingImports.PendingImage? =
        synchronized(lock) {
            val pending = entries[projectId] ?: return@synchronized null
            val image = pending.payload as? Payload.Image ?: return@synchronized null
            remove(projectId, pending)
            image.value
        }

    fun discardImage(
        projectId: Long,
        image: PixelBuffer,
    ) {
        synchronized(lock) {
            val pending = entries[projectId] ?: return
            if ((pending.payload as? Payload.Image)?.value?.image === image) remove(projectId, pending)
        }
    }

    fun model(projectId: Long): PreparedModelImport? = synchronized(lock) { (entries[projectId]?.payload as? Payload.Model)?.value }

    fun claimModel(projectId: Long): PreparedModelImport? = claimModel(projectId, null)

    fun claimModel(
        projectId: Long,
        expected: PreparedModelImport?,
    ): PreparedModelImport? =
        synchronized(lock) {
            val pending = entries[projectId] ?: return@synchronized null
            val model = pending.payload as? Payload.Model ?: return@synchronized null
            if (expected != null && model.value !== expected) return@synchronized null
            pending.claimed = true
            pending.expiry.cancel()
            model.value
        }

    fun completeModel(
        projectId: Long,
        prepared: PreparedModelImport,
    ): Boolean =
        synchronized(lock) {
            val pending = matchingModel(projectId, prepared) ?: return@synchronized false
            remove(projectId, pending)
            true
        }

    /** A failed navigation may release its handoff, but never an already claimed editor retry. */
    fun discardModel(
        projectId: Long,
        prepared: PreparedModelImport,
    ) {
        synchronized(lock) {
            val pending = matchingModel(projectId, prepared) ?: return
            if (!pending.claimed) remove(projectId, pending)
        }
    }

    fun receipt(
        projectId: Long,
        prepared: PreparedModelImport,
    ): ModelImports.Receipt? = synchronized(lock) { (matchingModel(projectId, prepared)?.payload as? Payload.Model)?.receipt }

    fun recordReceipt(
        projectId: Long,
        prepared: PreparedModelImport,
        receipt: ModelImports.Receipt,
    ) {
        synchronized(lock) {
            val pending = matchingModel(projectId, prepared) ?: return
            (pending.payload as Payload.Model).receipt = receipt
            // Publication itself also proves editor ownership when installation is invoked directly.
            pending.claimed = true
            pending.expiry.cancel()
        }
    }

    /** Called only after the user has deleted the destination project, including an abandoned retry. */
    fun discardProject(projectId: Long) {
        synchronized(lock) { entries[projectId]?.let { remove(projectId, it) } }
    }

    private fun matchingModel(
        projectId: Long,
        prepared: PreparedModelImport,
    ): Handoff? = entries[projectId]?.takeIf { (it.payload as? Payload.Model)?.value === prepared }

    private fun remove(
        projectId: Long,
        pending: Handoff,
    ) {
        entries.remove(projectId)
        retainedBytes -= pending.payload.bytes
        pending.expiry.cancel()
    }

    private fun expire(
        projectId: Long,
        pending: Handoff,
    ) {
        synchronized(lock) {
            if (entries[projectId] !== pending || pending.claimed) return
            remove(projectId, pending)
            onExpired(projectId)
        }
    }
}
