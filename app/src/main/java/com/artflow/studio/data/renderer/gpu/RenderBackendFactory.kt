package com.artflow.studio.data.renderer.gpu

/**
 * Factory for creating the optimal GPU or fallback rendering backend.
 * Automatically falls back from Vulkan to OpenGL ES 3.x when running inside
 * headless CI emulators or on devices without Vulkan compute capability.
 */
object RenderBackendFactory {
    fun createBackend(
        preferredType: RenderBackendType = RenderBackendType.VULKAN,
        isEmulator: Boolean = false,
        supportsVulkan: Boolean = true,
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
