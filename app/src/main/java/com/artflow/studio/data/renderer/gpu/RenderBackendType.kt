package com.artflow.studio.data.renderer.gpu

/**
 * Supported rendering backends for ArtFlow's GPU acceleration pipeline.
 */
enum class RenderBackendType {
    /**
     * OpenGL ES 3.1 compute / 3.0 FBO instanced quad pipeline.
     * Production shipping hardware renderer providing hardware acceleration, low-latency drawing,
     * and reliable compatibility across physical devices and CI environments.
     */
    GLES3,

    /**
     * Experimental Vulkan 1.2+ pipeline.
     * Non-shipping preview architecture under active development for future releases.
     */
    VULKAN,

    /**
     * Software-based CPU fallback pipeline for testing and environments without hardware acceleration.
     */
    SOFTWARE,
}
