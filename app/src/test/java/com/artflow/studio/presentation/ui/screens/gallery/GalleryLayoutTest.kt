package com.artflow.studio.presentation.ui.screens.gallery

import com.artflow.studio.domain.model.Project
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryLayoutTest {
    private fun project(
        id: Long,
        stack: String? = null,
    ) = Project(id, "Art $id", "", null, 8, 8, 72, 1L, 1L, stack = stack)

    private val projects = listOf(project(1), project(2, "Sketches"), project(3, "Sketches"), project(4, "Comics"))

    @Test fun mainGalleryShowsStacksThenLooseArtworks() {
        val layout = GalleryLayout.of(projects, openStack = null, searching = false)
        assertEquals(listOf("Sketches", "Comics"), layout.stacks.map { it.first })
        val sketches = layout.stacks.first().second
        assertEquals(listOf(2L, 3L), sketches.map { it.id })
        assertEquals(listOf(1L), layout.projects.map { it.id })
    }

    @Test fun openStackAndSearchShowArtworksOnly() {
        assertEquals(listOf(2L, 3L), GalleryLayout.of(projects, "Sketches", searching = false).projects.map { it.id })
        val search = GalleryLayout.of(projects, null, searching = true)
        assertEquals(4, search.projects.size)
        assertEquals(0, search.stacks.size)
    }

    @Test fun newStacksGetTheFirstFreeName() {
        assertEquals("Stack", newStackName(emptyList()))
        assertEquals("Stack 2", newStackName(listOf("Stack")))
        assertEquals("Stack 3", newStackName(listOf("Stack", "Stack 2", "Sketches")))
    }
}
