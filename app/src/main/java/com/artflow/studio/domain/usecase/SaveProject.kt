package com.artflow.studio.domain.usecase

import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.repository.ProjectRepository
import javax.inject.Inject

/**
 * Use case for saving a project
 */
class SaveProject @Inject constructor(
    private val projectRepository: ProjectRepository
) {
    suspend operator fun invoke(project: Project): Long {
        return projectRepository.saveProject(project)
    }
}
