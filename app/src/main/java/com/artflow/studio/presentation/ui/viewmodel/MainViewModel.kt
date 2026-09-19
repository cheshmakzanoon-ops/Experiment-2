package com.artflow.studio.presentation.ui.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AccentChoice
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.model.settings.GallerySort
import com.artflow.studio.domain.model.settings.ThemeMode
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/** Gallery state. */
sealed class MainUiState {
    object Loading : MainUiState()

    data class Success(
        val projects: List<Project>,
        val storageBytes: Long,
    ) : MainUiState()

    data class Error(
        val message: String,
    ) : MainUiState()
}

/**
 * Gallery / project list.
 *
 * Sorting, searching, favourites and deletion all go through `ProjectRepository`, and deleting a
 * project also removes its files so a deleted artwork cannot reappear on the next scan.
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val projectRepository: ProjectRepository,
        private val settingsRepository: SettingsRepository,
        private val storage: ProjectStorage,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
        val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

        private val _settings = MutableStateFlow(AppSettings())
        val settings: StateFlow<AppSettings> = _settings.asStateFlow()

        private val _settingsLoaded = MutableStateFlow(false)
        val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

        private val _paletteRecoveryRunning = MutableStateFlow(false)
        val paletteRecoveryRunning: StateFlow<Boolean> = _paletteRecoveryRunning.asStateFlow()

        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val messages = Channel<String>(Channel.BUFFERED)
        val messageFlow: Flow<String> = messages.receiveAsFlow()

        private var allProjects: List<Project> = emptyList()
        private var storageBytes = 0L
        private var projectsLoaded = false
        private val galleryErrors =
            CoroutineExceptionHandler { _, error ->
                Timber.e(error, "Gallery operation failed")
                messages.trySend(error.message ?: "The operation could not be completed")
                if (_uiState.value is MainUiState.Loading) _uiState.value = MainUiState.Error("Could not load the gallery")
            }

        init {
            viewModelScope.launch(galleryErrors) {
                settingsRepository.settings.collect { stored ->
                    if (stored.paletteRecoveryRequired && !_settings.value.paletteRecoveryRequired) {
                        messages.trySend(
                            "Saved palettes need recovery. Your artwork is available; open Settings to preserve the palette data.",
                        )
                    }
                    _settings.value = stored
                    _settingsLoaded.value = true
                    publish()
                }
            }
            viewModelScope.launch(galleryErrors) {
                projectRepository.getAllProjects().collect { projects ->
                    allProjects = projects
                    storageBytes = withContext(Dispatchers.IO) { storage.totalStorageBytes() }
                    projectsLoaded = true
                    publish()
                }
            }
        }

        private fun publish() {
            if (!projectsLoaded || !_settingsLoaded.value) return
            val settings = _settings.value
            val query = _query.value.trim()
            val filtered =
                if (query.isEmpty()) {
                    allProjects
                } else {
                    allProjects.filter { it.name.contains(query, ignoreCase = true) }
                }
            val sorted =
                when (settings.gallerySort) {
                    GallerySort.RECENT -> filtered.sortedByDescending { it.modifiedAt }
                    GallerySort.CREATED -> filtered.sortedByDescending { it.createdAt }
                    GallerySort.NAME -> filtered.sortedBy { it.name.lowercase() }
                    GallerySort.SIZE -> filtered.sortedByDescending { it.width.toLong() * it.height }
                }
            _uiState.value = MainUiState.Success(sorted, storageBytes)
        }

        fun setQuery(query: String) {
            _query.value = query
            publish()
        }

        fun createProject(
            name: String,
            preset: CanvasOperations.Preset?,
            width: Int,
            height: Int,
            dpi: Int,
            onCreated: (Long) -> Unit,
        ) {
            viewModelScope.launch(galleryErrors) {
                try {
                    val now = System.currentTimeMillis()
                    val project =
                        Project(
                            name = name.ifBlank { "Untitled artwork" },
                            filePath = "",
                            thumbnailPath = null,
                            width = preset?.width ?: width,
                            height = preset?.height ?: height,
                            dpi = preset?.dpi ?: dpi,
                            createdAt = now,
                            modifiedAt = now,
                        )
                    require(CanvasOperations.isSizeSafe(project.width, project.height)) { "This canvas is too large" }
                    require(project.dpi in CanvasOperations.MIN_DPI..CanvasOperations.MAX_DPI) { "Invalid canvas DPI" }
                    val id = projectRepository.saveProject(project)
                    preset?.let { settingsRepository.setDefaultPreset(it.name) }
                    onCreated(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Saving touches Room, file storage and serialization; let programming errors
                    // (Error subclasses) crash loudly instead of being swallowed into a snackbar.
                    Timber.e(error, "Could not create project")
                    messages.send(error.message ?: "Could not create the project")
                }
            }
        }

        fun rename(
            project: Project,
            name: String,
        ) {
            viewModelScope.launch(galleryErrors) {
                projectRepository.updateProject(project.copy(name = name, modifiedAt = System.currentTimeMillis()))
            }
        }

        fun toggleFavorite(project: Project) {
            viewModelScope.launch(galleryErrors) { projectRepository.toggleFavorite(project.id, !project.isFavorite) }
        }

        fun duplicate(project: Project) {
            viewModelScope.launch(galleryErrors) {
                projectRepository.duplicateProject(project.id)
                messages.send("Duplicated ${project.name}")
            }
        }

        fun delete(project: Project) {
            viewModelScope.launch(galleryErrors) {
                projectRepository.deleteProjectById(project.id)
                withContext(Dispatchers.IO) { storage.deleteProjectFiles(project.id) }
                storageBytes = withContext(Dispatchers.IO) { storage.totalStorageBytes() }
                publish()
                messages.send("Deleted ${project.name}")
            }
        }

        fun setSort(sort: GallerySort) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setGallerySort(sort) }
        }

        fun setThemeMode(mode: ThemeMode) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setThemeMode(mode) }
        }

        fun setAccent(accent: AccentChoice) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setAccent(accent) }
        }

        fun setHighContrast(enabled: Boolean) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setHighContrast(enabled) }
        }

        fun setReduceMotion(enabled: Boolean) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setReduceMotion(enabled) }
        }

        fun setUiScale(scale: Float) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setUiScale(scale) }
        }

        fun setLargeTouchTargets(enabled: Boolean) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setLargeTouchTargets(enabled) }
        }

        fun setCheckerboard(enabled: Boolean) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setCheckerboard(enabled) }
        }

        fun setOnionSkin(enabled: Boolean) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setOnionSkin(enabled) }
        }

        fun setSymmetryGuides(enabled: Boolean) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setSymmetryGuides(enabled) }
        }

        fun setPerspectiveGuides(enabled: Boolean) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setPerspectiveGuides(enabled) }
        }

        fun setSnapToGuides(enabled: Boolean) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setSnapToGuides(enabled) }
        }

        fun setStylusOnly(enabled: Boolean) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setStylusOnly(enabled) }
        }

        fun setHaptics(enabled: Boolean) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setHaptics(enabled) }
        }

        fun setAutosave(
            enabled: Boolean,
            intervalMs: Long,
        ) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setAutosave(enabled, intervalMs) }
        }

        fun setDefaultPreset(name: String) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setDefaultPreset(name) }
        }

        fun setOnboardingSeen(seen: Boolean) {
            viewModelScope.launch(galleryErrors) { settingsRepository.setOnboardingSeen(seen) }
        }

        fun dismissTip(id: String) {
            viewModelScope.launch(galleryErrors) { settingsRepository.dismissTip(id) }
        }

        /** Brings back every tip the user has hidden, so Help can always be re-read. */
        fun restoreTips() {
            viewModelScope.launch(galleryErrors) {
                settingsRepository.update { it.copy(dismissedTips = emptySet()) }
            }
        }

        fun clearRecentColors() {
            viewModelScope.launch(galleryErrors) { settingsRepository.clearRecentColors() }
        }

        fun backUpAndResetPalettes(
            resolver: ContentResolver,
            destination: Uri,
        ) {
            if (_paletteRecoveryRunning.value) return
            _paletteRecoveryRunning.value = true
            viewModelScope.launch(galleryErrors) {
                try {
                    val reset =
                        settingsRepository.backupAndResetUnreadablePalettes { original ->
                            writePaletteBackup(resolver, destination, original)
                        }
                    messages.send(if (reset) "Palette data backed up. You can now save new palettes." else "No palette recovery is needed.")
                } finally {
                    _paletteRecoveryRunning.value = false
                }
            }
        }

        private suspend fun writePaletteBackup(
            resolver: ContentResolver,
            destination: Uri,
            original: String,
        ) = withContext(Dispatchers.IO) {
            val stream =
                resolver.openOutputStream(destination, "wt")
                    ?: throw IOException("The backup destination could not be opened")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(original) }
        }

        fun storageSummary(): String {
            val bytes = storageBytes
            // Double division: Float mantissa precision loses visible accuracy once the value
            // exceeds 16 MB of bytes.
            return when {
                bytes < 1024 * 1024 -> "%.0f KB on device".format(bytes / 1024f)
                bytes < 1024L * 1024 * 1024 -> "%.1f MB on device".format(bytes / (1024f * 1024f))
                else ->
                    "%.2f GB on device".format(
                        bytes.toDouble() / (1024.0 * 1024.0 * 1024.0),
                    )
            }
        }
    }
