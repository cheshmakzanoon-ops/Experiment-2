package com.artflow.studio.data.renderer.gpu

import com.artflow.studio.data.renderer.gles3.Gles3Capabilities
import com.artflow.studio.data.renderer.gles3.Gles3RenderBackend

/**
 * Factory for creating the graphics rendering backend.
 * Supports Hybrid OpenGL ES 3.0+ Float16 ([Gles3RenderBackend] with automatic GLES 2.0 fallback),
 * OpenGL ES 2.0 ([GlesRenderBackend]), [SoftwareRenderBackend] for software-only fallback/tests,
 * and an experimental preview option for Vulkan on non-emulator hardware.
 */
object RenderBackendFactory {
    fun createBackend(
        preferredType: RenderBackendType = RenderBackendType.GLES2,
        isEmulator: Boolean = false,
        supportsVulkan: Boolean = false,
        gles3Capabilities: Gles3Capabilities = Gles3Capabilities(),
    ): IRenderBackend =
        when {
            preferredType == RenderBackendType.VULKAN && !isEmulator && supportsVulkan -> {
                VulkanRenderBackend()
            }
            preferredType == RenderBackendType.GLES3 -> {
                Gles3RenderBackend(capabilities = gles3Capabilities)
            }
            preferredType == RenderBackendType.SOFTWARE -> {
                SoftwareRenderBackend()
            }
            else -> {
                GlesRenderBackend()
            }
        }
}
