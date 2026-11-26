package com.artflow.studio.data.local

import com.artflow.studio.core.lifecycle.withCleanup
import com.artflow.studio.core.render.CustomGrains
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Owns a photo's provisional texture until its still-active UI accepts the brush change. */
internal object GrainPhotoImport {
    suspend fun importInto(
        directory: File,
        decode: () -> CustomGrains.Tile?,
        accept: (String) -> Unit,
    ): Boolean = importInto(directory, GrainStorage.ImportTransaction(), decode, accept)

    internal suspend fun importInto(
        directory: File,
        grains: GrainStorage.ImportTransaction,
        decode: () -> CustomGrains.Tile?,
        accept: (String) -> Unit,
    ): Boolean {
        var accepted = false
        return withCleanup({
            withContext(NonCancellable + Dispatchers.IO) {
                if (accepted) grains.commit() else grains.close()
            }
        }) {
            // Keep the owner outside withContext: cancellation can discard a completed IO result
            // before Main receives it. In that case only our newly installed assets roll back.
            val id =
                withContext(Dispatchers.IO) {
                    val tile = decode() ?: return@withContext null
                    currentCoroutineContext().ensureActive()
                    val id = CustomGrains.idFor(tile)
                    grains.install(directory, mapOf(id to tile))
                    id
                } ?: return@withCleanup false
            withContext(Dispatchers.Main.immediate) {
                currentCoroutineContext().ensureActive()
                accept(id)
                // Record acceptance before leaving Main, even if the callback cancelled its owner.
                // The still-open claim protects the texture until IO retains it; Main never waits
                // for the import lock while another import is writing its texture files.
                accepted = true
            }
            true
        }
    }
}
