package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.export.ExportArea
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.SavedCanvas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException

/** Owns export execution and terminal states while the ViewModel retains document navigation. */
internal class CanvasExportController(
    private val repository: CanvasRepository,
    private val exporter: ArtworkExporter,
    private val recorder: TimelapseRecorder,
    private val state: MutableStateFlow<ExportUiState>,
    private val scope: CoroutineScope,
    private val currentProject: () -> Pair<Long, String>,
    private val onDocumentSaved: suspend (SavedCanvas) -> Unit,
    private val onSaveFinished: suspend (SavedCanvas) -> Unit = {},
) {
    private var generation = 0L
    private var activeExport: Job? = null

    /** A result and any suspended work belong to the document that started them. */
    fun reset() {
        generation++
        activeExport?.cancel()
        activeExport = null
        state.value = ExportUiState.Idle
    }

    fun availableFormats(
        uiState: CanvasUiState,
        hasModel: Boolean,
    ): List<ExportFormat> =
        if ((uiState as? CanvasUiState.Ready)?.frameCount ?: 1 > 1) {
            ExportFormat.stillFormats() + ExportFormat.animationFormats()
        } else {
            ExportFormat.stillFormats()
        } + if (hasModel) listOf(ExportFormat.MODEL_OBJ, ExportFormat.MODEL_GLB) else emptyList()

    fun export(options: ExportOptions) {
        val (projectId, name) = currentProject()
        run(projectId, "Export failed") {
            check(!repository.hasUnresolvedRecovery(projectId)) {
                "Resolve recovery before exporting artwork"
            }
            val format =
                if (options.area == ExportArea.ALL_FRAMES && options.format == ExportFormat.PNG) {
                    ExportFormat.FRAME_SEQUENCE
                } else {
                    options.format
                }
            val resolved = options.copy(format = format, colorProfile = repository.getColorProfile())
            if (resolved.format == ExportFormat.ARTFLOW) {
                // The package references saved document files; the save must finish first.
                val saved = checkNotNull(repository.saveCanvasSnapshot(projectId, onDocumentSaved)) { "The artwork could not be saved" }
                onSaveFinished(saved)
                exporter.exportPackage(projectId, name, resolved)
            } else {
                renderedExport(repository, exporter, projectId, name, resolved)
            }
        }
    }

    fun exportTimelapse(fullLength: Boolean) {
        val (projectId, name) = currentProject()
        run(projectId, "Time-lapse export failed") {
            check(!repository.hasUnresolvedRecovery(projectId)) {
                "Resolve recovery before exporting time-lapse"
            }
            recorder.export(projectId, name, fullLength)
        }
    }

    private fun run(
        projectId: Long,
        failureMessage: String,
        operation: suspend () -> Result<ExportResult>,
    ) {
        if (projectId == 0L || state.value is ExportUiState.Running) return
        val request = ++generation
        state.value = ExportUiState.Running
        activeExport =
            scope.launch {
                try {
                    if (!ownsResult(projectId, request)) return@launch
                    val result =
                        operation().fold(
                            onSuccess = { ExportUiState.Done(it) },
                            onFailure = { ExportUiState.Failed(it.message ?: failureMessage) },
                        )
                    publish(projectId, request, result)
                } catch (cancelled: CancellationException) {
                    publish(projectId, request, ExportUiState.Idle)
                    throw cancelled
                } catch (error: IOException) {
                    fail(projectId, request, error, failureMessage)
                } catch (error: IllegalArgumentException) {
                    fail(projectId, request, error, failureMessage)
                } catch (error: IllegalStateException) {
                    fail(projectId, request, error, failureMessage)
                } catch (error: SecurityException) {
                    fail(projectId, request, error, failureMessage)
                }
            }
    }

    private fun ownsResult(
        projectId: Long,
        request: Long,
    ): Boolean = generation == request && currentProject().first == projectId

    private fun publish(
        projectId: Long,
        request: Long,
        result: ExportUiState,
    ) {
        if (ownsResult(projectId, request)) state.value = result
    }

    private fun fail(
        projectId: Long,
        request: Long,
        error: Exception,
        message: String,
    ) {
        Timber.e(error, message)
        publish(projectId, request, ExportUiState.Failed(error.message ?: message))
    }
}
