package com.artflow.studio.data.renderer.gpu

/**
 * Factory for creating the graphics rendering backend.
 * Uses the OpenGL ES 2.0 backend as the primary shipping configuration,
 * [SoftwareRenderBackend] for software-only fallback/tests, and an experimental preview option
 * for Vulkan development on non-emulator hardware.
 */
object RenderBackendFactory {
    fun createBackend(
        preferredType: RenderBackendType = RenderBackendType.GLES2,
        isEmulator: Boolean = false,
        supportsVulkan: Boolean = false,
    ): IRenderBackend =
        when {
            preferredType == RenderBackendType.VULKAN && !isEmulator && supportsVulkan -> {
                VulkanRenderBackend()
            }
            preferredType == RenderBackendType.SOFTWARE -> {
                SoftwareRenderBackend()
            }
            else -> {
                GlesRenderBackend()
            }
        }
}
