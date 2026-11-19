package com.artflow.studio.data.export

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.PreparedModelImport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Hands an imported picture or document from the gallery to the editor that opens its new canvas. */
object PendingImports {
    data class PendingImage(
        val image: PixelBuffer,
        val isPrivate: Boolean,
    )

    private val images = ConcurrentHashMap<Long, PendingImage>()

    fun put(
        projectId: Long,
        image: PixelBuffer,
        isPrivate: Boolean = false,
    ) {
        images[projectId] = PendingImage(image, isPrivate)
    }

    fun take(projectId: Long): PendingImage? = images.remove(projectId)

    private class PsdHandoff(
        val bytes: ByteArray,
    ) {
        lateinit var expiry: Job
    }

    private val documents = ConcurrentHashMap<Long, PsdHandoff>()
    private const val IMPORT_CLAIM_TIMEOUT_MS = 10 * 60 * 1000L

    /** A layered Photoshop document whose layers fill the new canvas. */
    fun putPsd(
        projectId: Long,
        bytes: ByteArray,
        owner: CoroutineScope,
    ) {
        val pending = PsdHandoff(bytes)
        pending.expiry =
            owner.launch(start = CoroutineStart.LAZY) {
                delay(IMPORT_CLAIM_TIMEOUT_MS)
                expirePsd(projectId, pending)
            }
        documents.put(projectId, pending)?.expiry?.cancel()
        pending.expiry.invokeOnCompletion { cause -> if (cause != null) expirePsd(projectId, pending) }
        pending.expiry.start()
    }

    fun takePsd(projectId: Long): ByteArray? {
        val pending = documents.remove(projectId) ?: return null
        pending.expiry.cancel()
        return pending.bytes
    }

    fun discardPsd(
        projectId: Long,
        bytes: ByteArray,
    ) {
        val pending = documents[projectId]?.takeIf { it.bytes === bytes } ?: return
        if (documents.remove(projectId, pending)) pending.expiry.cancel()
    }

    private fun expirePsd(
        projectId: Long,
        pending: PsdHandoff,
    ) {
        if (!documents.remove(projectId, pending)) return
        putNotice(projectId, "The file import was cancelled before the editor opened. Import the original file again.")
    }

    private class ProcreateHandoff(
        val document: ProcreateImport,
    ) {
        lateinit var expiry: Job
    }

    private val procreate = ConcurrentHashMap<Long, ProcreateHandoff>()

    /**
     * The editor takes ownership on claim. An abandoned navigation is bounded by the gallery
     * ViewModel lifetime and a claim deadline; claimed imports are never closed by this owner.
     */
    fun putProcreate(
        projectId: Long,
        document: ProcreateImport,
        owner: CoroutineScope,
    ) {
        val pending = ProcreateHandoff(document)
        pending.expiry =
            owner.launch(start = CoroutineStart.LAZY) {
                delay(IMPORT_CLAIM_TIMEOUT_MS)
                expireProcreate(projectId, pending)
            }
        val replaced = procreate.put(projectId, pending)
        replaced?.let {
            it.expiry.cancel()
            closeProcreate(it.document)
        }
        pending.expiry.invokeOnCompletion { cause -> if (cause != null) expireProcreate(projectId, pending) }
        pending.expiry.start()
    }

    fun takeProcreate(projectId: Long): ProcreateImport? {
        val pending = procreate.remove(projectId) ?: return null
        pending.expiry.cancel()
        return pending.document
    }

    /** Releases only this navigation's unclaimed archive, without closing an editor-owned import. */
    fun discardProcreate(
        projectId: Long,
        document: ProcreateImport,
    ) {
        val pending = procreate[projectId]?.takeIf { it.document === document } ?: return
        if (procreate.remove(projectId, pending)) {
            pending.expiry.cancel()
            closeProcreate(document)
        }
    }

    private fun expireProcreate(
        projectId: Long,
        pending: ProcreateHandoff,
    ) {
        if (!procreate.remove(projectId, pending)) return
        closeProcreate(pending.document)
        putNotice(projectId, "The file import was cancelled before the editor opened. Import the original file again.")
    }

    private fun closeProcreate(document: ProcreateImport) {
        try {
            document.close()
        } catch (error: IOException) {
            Timber.e(error, "Could not clean up an abandoned Procreate import")
        } catch (error: SecurityException) {
            Timber.e(error, "Could not access an abandoned Procreate import")
        }
    }

    private val models = ConcurrentHashMap<Long, String>()

    /** A 3D model (OBJ text) whose texture the new canvas becomes. */
    fun putModel(
        projectId: Long,
        objText: String,
    ) {
        models[projectId] = objText
    }

    fun takeModel(projectId: Long): String? = models.remove(projectId)

    private class ModelBundle(
        val prepared: PreparedModelImport,
    ) {
        @Volatile var receipt: ModelImports.Receipt? = null
    }

    private val modelBundles = ConcurrentHashMap<Long, ModelBundle>()

    /** All model planes are prepared before the gallery creates their destination project. */
    fun putModelBundle(
        projectId: Long,
        prepared: PreparedModelImport,
    ) {
        modelBundles[projectId] = ModelBundle(prepared)
    }

    /** Keep the payload available until an awaited installation succeeds, including after a retryable I/O failure. */
    fun modelBundle(projectId: Long): PreparedModelImport? = modelBundles[projectId]?.prepared

    fun completeModelBundle(
        projectId: Long,
        prepared: PreparedModelImport,
    ): Boolean {
        val bundle = modelBundles[projectId]?.takeIf { it.prepared === prepared } ?: return false
        return modelBundles.remove(projectId, bundle)
    }

    internal fun modelReceipt(
        projectId: Long,
        prepared: PreparedModelImport,
    ): ModelImports.Receipt? = modelBundles[projectId]?.takeIf { it.prepared === prepared }?.receipt

    internal fun recordModelReceipt(
        projectId: Long,
        prepared: PreparedModelImport,
        receipt: ModelImports.Receipt,
    ) {
        modelBundles.computeIfPresent(projectId) { _, bundle ->
            bundle.also { if (it.prepared === prepared) it.receipt = receipt }
        }
    }

    private val profiles = ConcurrentHashMap<Long, ColorProfile>()

    /** The colour profile chosen for a new canvas, applied when it first opens. */
    fun putProfile(
        projectId: Long,
        profile: ColorProfile,
    ) {
        profiles[projectId] = profile
    }

    fun takeProfile(projectId: Long): ColorProfile? = profiles.remove(projectId)

    private val notices = ConcurrentHashMap<Long, String>()

    /** One-shot import warning (e.g., missing fonts) surfaced when the opened canvas becomes ready. */
    fun putNotice(
        projectId: Long,
        message: String,
    ) {
        notices[projectId] = message
    }

    fun putDependencyNotice(
        projectId: Long,
        missingFonts: List<String>,
        missingGrains: List<String>,
    ) {
        val warnings =
            listOfNotNull(
                missingFonts.distinct().takeIf { it.isNotEmpty() }?.let { names ->
                    val shown = names.take(3).joinToString { it.take(60) }
                    val remainder = if (names.size > 3) " and ${names.size - 3} more" else ""
                    "Missing fonts: $shown$remainder. Import the original fonts before editing those text layers."
                },
                missingGrains.distinct().takeIf { it.isNotEmpty() }?.let {
                    "${it.size} brush texture(s) are missing; restore them before continuing to paint."
                },
            )
        if (warnings.isNotEmpty()) putNotice(projectId, warnings.joinToString(" "))
    }

    fun takeNotice(projectId: Long): String? = notices.remove(projectId)
}
