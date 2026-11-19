package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.export.LayerImports
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Binds an external picker to the current artwork before it opens. Lifecycle calls run on Main. */
class CanvasImportController(
    private val repository: CanvasRepository,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
    private val onImported: suspend () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var pending: Request<*>? = null

    /** Only this request can supply its decoder; a late result cannot complete a newer import. */
    class Request<T> internal constructor() {
        internal val source = CompletableDeferred<suspend () -> T?>()
        internal var job: Job? = null

        fun complete(decode: suspend () -> T?) {
            source.complete(decode)
        }

        fun cancel() {
            job?.cancel()
            source.cancel()
        }
    }

    fun beginImage(isPrivate: Boolean = false): Request<PixelBuffer> =
        begin("That image could not be opened") { decode ->
            LayerImports.insertImage(repository, isPrivate, decode)
            if (isPrivate) notify("Private photo added: it is part of the artwork but stays out of the time-lapse")
        }

    fun beginPsd(): Request<ByteArray> =
        begin("That PSD could not be opened (maximum 256 MB)") { read ->
            val imported = LayerImports.importPsdWithReport(repository, read)
            val limitations =
                buildList {
                    if (imported.sourceInfo.isCmyk) add("CMYK colors were approximated in sRGB.")
                    if (imported.sourceInfo.hasEmbeddedIccProfile) add("The embedded ICC profile was not applied; colors may differ.")
                    if (imported.sourceInfo.ignoredAuxiliaryChannels > 0) add("Extra alpha/spot channels were not imported.")
                    if (imported.sourceInfo.usesFlattenedAlternateLayers) {
                        add(
                            "This PSD's alternate layer records were imported as one flattened image.",
                        )
                    }
                }
            val note = if (limitations.isEmpty()) "" else ". ${limitations.joinToString(" ")}"
            notify("Imported ${imported.layers} layer(s) from PSD$note")
        }

    fun insertImage(
        image: PixelBuffer,
        isPrivate: Boolean = false,
    ) {
        beginImage(isPrivate).complete { image }
    }

    fun importPsd(bytes: ByteArray) {
        beginPsd().complete { bytes }
    }

    /** Screen exit, project opening and replacement requests invalidate any pending picker or decode. */
    fun cancel() {
        pending?.cancel()
        pending = null
    }

    @Suppress("TooGenericExceptionCaught") // Provider/codec errors are reported here; cancellation and fatal errors still propagate.
    private fun <T> begin(
        failureMessage: String,
        operation: suspend (suspend () -> T) -> Unit,
    ): Request<T> {
        cancel()
        val request = Request<T>()
        pending = request
        // Main.immediate plus UNDISPATCHED enters the repository's snapshot boundary before this
        // returns to the picker launcher. Only prepare suspends while awaiting the provider result.
        request.job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    operation {
                        val decode = request.source.await()
                        withContext(ioDispatcher) { decode() } ?: error(failureMessage)
                    }
                    onImported()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.e(error, "Artwork import failed")
                    notify(error.message ?: failureMessage)
                } finally {
                    request.source.cancel()
                    if (pending === request) pending = null
                }
            }
        return request
    }
}
