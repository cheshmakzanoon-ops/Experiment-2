package com.artflow.studio.presentation.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.usecase.GetAllProjects
import com.artflow.studio.domain.usecase.SaveProject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the main screen (Gallery)
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val getAllProjects: GetAllProjects,
    private val saveProject: SaveProject
) : ViewModel() {

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        loadProjects()
    }

    fun loadProjects() {
        viewModelScope.launch {
            _uiState.value = MainUiState.Loading
            getAllProjects().collect { projects ->
                _uiState.value = MainUiState.Success(projects)
            }
        }
    }

    fun createNewProject(project: Project) {
        viewModelScope.launch {
            try {
                saveProject(project)
                loadProjects() // Refresh the list
            } catch (e: Exception) {
                _uiState.value = MainUiState.Error(e.message ?: "Failed to create project")
            }
        }
    }
}

/**
 * Sealed class representing UI state
 */
sealed class MainUiState {
    object Loading : MainUiState()
    data class Success(val projects: List<Project>) : MainUiState()
    data class Error(val message: String) : MainUiState()
}
