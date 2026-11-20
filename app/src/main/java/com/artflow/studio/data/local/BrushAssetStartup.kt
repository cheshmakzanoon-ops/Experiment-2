package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.core.lifecycle.AssetInitialization
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Shares the production asset startup boundary across the Application and every Activity visit. */
@Singleton
class BrushAssetStartup
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val initialization =
            AssetInitialization(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                initialize = {
                    FontLibrary.configure(context.filesDir)
                    GrainStorage.loadAll(GrainStorage.directory(context.filesDir), strict = true)
                },
                onFailure = { Timber.e(it, "Saved brush assets could not be loaded") },
            )

        val state = initialization.state

        fun start() = initialization.start()

        fun retry() = initialization.retry()

        fun continueWithAvailableAssets() = initialization.continueWithAvailableAssets()
    }
