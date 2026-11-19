package com.artflow.studio.data.renderer.gpu

/**
 * Rendering backend identifiers for ArtFlow's viewport and tile compositing architecture.
 *
 * Production viewport presentation runs on OpenGL ES 2.0 (`OpenGLCanvasRenderer`,
 * `ArtFlowCanvasView`, `ModelView`, and manifest `glEsVersion="0x00020000"`), while raster layer
 * compositing executes on the CPU via [VirtualTileCanvas]. The experimental C++20 sources under
 * `app/src/main/jni` are not wired into the Gradle build.
 */
enum class RenderBackendType {
    /**
     * OpenGL ES 2.0 presentation backend (`GLES20` + EGL context version 2) paired with CPU
     * [VirtualTileCanvas] compositing across physical Android devices and CI emulators.
     */
    GLES2,

    /**
     * Experimental Vulkan preview backend identifier.
     * Non-shipping preview implementation backed by CPU [VirtualTileCanvas] compositing.
     */
    VULKAN,

    /**
     * Software CPU-only pipeline for headless tests and environments without GPU surface
     * presentation.
     */
    SOFTWARE,
}
