package com.artflow.studio.data.renderer.gpu

/**
 * Factory for creating the graphics rendering backend.
 * Uses the production-validated OpenGL ES 3.x renderer as the primary shipping backend,
 * with software fallback for tests and an experimental preview option for Vulkan development.
 */
object RenderBackendFactory {
    fun createBackend(
        preferredType: RenderBackendType = RenderBackendType.GLES3,
        isEmulator: Boolean = false,
        supportsVulkan: Boolean = false,
    ): IRenderBackend =
        when {
            preferredType == RenderBackendType.VULKAN && !isEmulator && supportsVulkan -> {
                VulkanRenderBackend()
            }
            preferredType == RenderBackendType.SOFTWARE -> {
                GlesRenderBackend()
            }
            else -> {
                GlesRenderBackend()
            }
        }
}
