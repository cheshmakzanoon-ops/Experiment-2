package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.export.ExportArea
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    private val onDocumentSaved: () -> Unit,
) {
    fun reset() {
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
        } + listOfNotNull(ExportFormat.MODEL_OBJ.takeIf { hasModel })

    fun export(options: ExportOptions) {
        val (projectId, name) = currentProject()
        run(projectId, "Export failed") {
            val format =
                if (options.area == ExportArea.ALL_FRAMES && options.format == ExportFormat.PNG) {
                    ExportFormat.FRAME_SEQUENCE
                } else {
                    options.format
                }
            val resolved = options.copy(format = format, colorProfile = repository.getColorProfile())
            if (resolved.format == ExportFormat.ARTFLOW) {
                // The package references saved document files; the save must finish first.
                checkNotNull(repository.saveCanvas(projectId)) { "The artwork could not be saved" }
                onDocumentSaved()
                exporter.exportPackage(projectId, name, resolved)
            } else {
                renderedExport(repository, exporter, projectId, name, resolved)
            }
        }
    }

    fun exportTimelapse(fullLength: Boolean) {
        val (projectId, name) = currentProject()
        run(projectId, "Time-lapse export failed") { recorder.export(projectId, name, fullLength) }
    }

    private fun run(
        projectId: Long,
        failureMessage: String,
        operation: suspend () -> Result<ExportResult>,
    ) {
        if (projectId == 0L || state.value is ExportUiState.Running) return
        state.value = ExportUiState.Running
        scope.launch {
            try {
                state.value =
                    operation().fold(
                        onSuccess = { ExportUiState.Done(it) },
                        onFailure = { ExportUiState.Failed(it.message ?: failureMessage) },
                    )
            } catch (cancelled: CancellationException) {
                state.value = ExportUiState.Idle
                throw cancelled
            } catch (error: IOException) {
                fail(error, failureMessage)
            } catch (error: IllegalArgumentException) {
                fail(error, failureMessage)
            } catch (error: IllegalStateException) {
                fail(error, failureMessage)
            } catch (error: SecurityException) {
                fail(error, failureMessage)
            }
        }
    }

    private fun fail(
        error: Exception,
        message: String,
    ) {
        Timber.e(error, message)
        state.value = ExportUiState.Failed(error.message ?: message)
    }
}
