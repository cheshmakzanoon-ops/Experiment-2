package com.artflow.studio.presentation.ui.viewmodel

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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
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

        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val messages = Channel<String>(Channel.BUFFERED)
        val messageFlow: Flow<String> = messages.receiveAsFlow()

        private var allProjects: List<Project> = emptyList()

        init {
            viewModelScope.launch {
                settingsRepository.settings.collect { stored ->
                    _settings.value = stored
                    publish()
                }
            }
            viewModelScope.launch {
                projectRepository.getAllProjects().collect { projects ->
                    allProjects = projects
                    publish()
                }
            }
        }

        private fun publish() {
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
            _uiState.value = MainUiState.Success(sorted, storage.totalStorageBytes())
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
            viewModelScope.launch {
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
                    val id = projectRepository.saveProject(project)
                    preset?.let { settingsRepository.setDefaultPreset(it.name) }
                    onCreated(id)
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
            viewModelScope.launch {
                projectRepository.updateProject(project.copy(name = name, modifiedAt = System.currentTimeMillis()))
            }
        }

        fun toggleFavorite(project: Project) {
            viewModelScope.launch { projectRepository.toggleFavorite(project.id, !project.isFavorite) }
        }

        fun duplicate(project: Project) {
            viewModelScope.launch {
                val now = System.currentTimeMillis()
                projectRepository.saveProject(
                    project.copy(
                        id = 0,
                        name = "${project.name} copy",
                        createdAt = now,
                        modifiedAt = now,
                        thumbnailPath = project.thumbnailPath,
                    ),
                )
                messages.send("Duplicated ${project.name}")
            }
        }

        fun delete(project: Project) {
            viewModelScope.launch {
                projectRepository.deleteProjectById(project.id)
                storage.deleteProjectFiles(project.id)
                messages.send("Deleted ${project.name}")
            }
        }

        fun setSort(sort: GallerySort) {
            viewModelScope.launch { settingsRepository.setGallerySort(sort) }
        }

        fun setThemeMode(mode: ThemeMode) {
            viewModelScope.launch { settingsRepository.setThemeMode(mode) }
        }

        fun setAccent(accent: AccentChoice) {
            viewModelScope.launch { settingsRepository.setAccent(accent) }
        }

        fun setHighContrast(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setHighContrast(enabled) }
        }

        fun setReduceMotion(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setReduceMotion(enabled) }
        }

        fun setUiScale(scale: Float) {
            viewModelScope.launch { settingsRepository.setUiScale(scale) }
        }

        fun setLargeTouchTargets(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setLargeTouchTargets(enabled) }
        }

        fun setCheckerboard(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setCheckerboard(enabled) }
        }

        fun setOnionSkin(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setOnionSkin(enabled) }
        }

        fun setSymmetryGuides(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setSymmetryGuides(enabled) }
        }

        fun setPerspectiveGuides(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setPerspectiveGuides(enabled) }
        }

        fun setSnapToGuides(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setSnapToGuides(enabled) }
        }

        fun setStylusOnly(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setStylusOnly(enabled) }
        }

        fun setHaptics(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setHaptics(enabled) }
        }

        fun setAutosave(
            enabled: Boolean,
            intervalMs: Long,
        ) {
            viewModelScope.launch { settingsRepository.setAutosave(enabled, intervalMs) }
        }

        fun setDefaultPreset(name: String) {
            viewModelScope.launch { settingsRepository.setDefaultPreset(name) }
        }

        fun setOnboardingSeen(seen: Boolean) {
            viewModelScope.launch { settingsRepository.setOnboardingSeen(seen) }
        }

        fun dismissTip(id: String) {
            viewModelScope.launch { settingsRepository.dismissTip(id) }
        }

        /** Brings back every tip the user has hidden, so Help can always be re-read. */
        fun restoreTips() {
            viewModelScope.launch {
                settingsRepository.update { it.copy(dismissedTips = emptySet()) }
            }
        }

        fun clearRecentColors() {
            viewModelScope.launch { settingsRepository.clearRecentColors() }
        }

        fun storageSummary(): String {
            val bytes = storage.totalStorageBytes()
            return when {
                bytes < 1024 * 1024 -> "%.0f KB on device".format(bytes / 1024f)
                bytes < 1024L * 1024 * 1024 -> "%.1f MB on device".format(bytes / (1024f * 1024f))
                else -> "%.2f GB on device".format(bytes / (1024f * 1024f * 1024f))
            }
        }
    }
