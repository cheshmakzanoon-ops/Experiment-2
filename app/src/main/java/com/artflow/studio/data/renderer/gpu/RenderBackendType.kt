package com.artflow.studio.data.renderer.gpu

/**
 * Supported rendering backends for ArtFlow's GPU acceleration pipeline.
 */
enum class RenderBackendType {
    /**
     * Vulkan 1.2+ compute & graphics pipeline.
     * Primary backend for physical devices, delivering 120 FPS sub-15ms low-latency drawing.
     */
    VULKAN,

    /**
     * OpenGL ES 3.1 compute / 3.0 FBO instanced quad pipeline.
     * High-compatibility backend ensuring all headless CI emulator matrix lanes execute reliably.
     */
    GLES3,

    /**
     * Software-based CPU fallback pipeline for testing and environments without hardware acceleration.
     */
    SOFTWARE,
}
