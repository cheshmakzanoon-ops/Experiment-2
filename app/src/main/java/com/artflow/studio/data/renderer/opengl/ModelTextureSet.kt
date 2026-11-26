package com.artflow.studio.data.renderer.opengl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import timber.log.Timber

/** Three candidate textures become visible together, after every channel has uploaded successfully. */
internal class ModelTextureSet private constructor(
    val handles: IntArray,
    val sizes: List<CanvasTextureSize>,
) {
    fun delete() = GLES20.glDeleteTextures(handles.size, handles, 0)

    companion object {
        fun upload(
            bitmaps: List<Bitmap>,
            maxEdge: () -> Int,
        ): ModelTextureSet? {
            require(bitmaps.size == 3)
            drainModelGlErrors()
            val handles = IntArray(3)
            GLES20.glGenTextures(handles.size, handles, 0)
            var completed = false
            try {
                if (handles.any { it == 0 } || drainModelGlErrors() != GLES20.GL_NO_ERROR) return null
                val sizes = mutableListOf<CanvasTextureSize>()
                bitmaps.forEachIndexed { index, bitmap ->
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handles[index])
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    if (drainModelGlErrors() != GLES20.GL_NO_ERROR) return null
                    val size = CanvasTextureUpload.upload(bitmap.width, bitmap.height, maxEdge()) { uploadAtSize(bitmap, it) }
                    sizes += size ?: return null
                }
                completed = true
                return ModelTextureSet(handles, sizes)
            } finally {
                if (!completed) GLES20.glDeleteTextures(handles.size, handles, 0)
            }
        }

        private fun uploadAtSize(
            source: Bitmap,
            size: CanvasTextureSize,
        ): Boolean {
            drainModelGlErrors()
            var upload: Bitmap? = null
            return try {
                upload =
                    if (source.width == size.width && source.height == size.height) {
                        source
                    } else {
                        Bitmap.createScaledBitmap(source, size.width, size.height, true)
                    }
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, upload, 0)
                drainModelGlErrors() == GLES20.GL_NO_ERROR
            } catch (failure: IllegalArgumentException) {
                Timber.w(failure, "Model texture upload rejected")
                false
            } catch (failure: OutOfMemoryError) {
                Timber.w(failure, "Model preview texture did not fit")
                false
            } finally {
                if (upload !== source) upload?.recycle()
            }
        }
    }
}

/** A bounded drain separates earlier optional GL errors from this frame's own operations. */
internal fun drainModelGlErrors(): Int {
    var first = GLES20.GL_NO_ERROR
    repeat(16) {
        val error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) return first
        if (first == GLES20.GL_NO_ERROR) first = error
    }
    return first
}
