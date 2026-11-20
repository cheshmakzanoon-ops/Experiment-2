package com.artflow.studio.core.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AssetInitializationTest {
    @Test
    fun firstDependentOperationWaitsForAssetRegistrationWithoutBlockingOtherWork() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var availableTexture = "fallback"
            var firstStrokeTexture: String? = null
            var mainHeartbeat = false
            val initialization =
                AssetInitialization(backgroundScope, initialize = {
                    release.await()
                    availableTexture = "saved texture"
                })

            initialization.start()
            backgroundScope.launch {
                initialization.state.first { it.allowsEditor }
                firstStrokeTexture = availableTexture
            }
            backgroundScope.launch { mainHeartbeat = true }
            runCurrent()
            assertTrue(mainHeartbeat)
            assertEquals(AssetStartupState.LOADING, initialization.state.value)
            assertEquals(null, firstStrokeTexture)
            initialization.continueWithAvailableAssets()
            assertFalse(initialization.state.value.allowsEditor)

            release.complete(Unit)
            runCurrent()
            assertEquals(AssetStartupState.READY, initialization.state.value)
            assertEquals("saved texture", firstStrokeTexture)
        }

    @Test
    fun activityRecreationDoesNotRestartOrCancelTheProcessOwnedInitialization() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var loads = 0
            val initialization =
                AssetInitialization(backgroundScope, initialize = {
                    loads++
                    release.await()
                })
            initialization.start()
            val firstActivity = backgroundScope.launch { initialization.state.first { it.allowsEditor } }
            runCurrent()
            firstActivity.cancel()
            var nextActivityReady = false
            initialization.start()
            backgroundScope.launch {
                initialization.state.first { it.allowsEditor }
                nextActivityReady = true
            }
            repeat(20) { initialization.start() }
            runCurrent()
            assertEquals(1, loads)
            assertFalse(nextActivityReady)

            release.complete(Unit)
            runCurrent()
            assertTrue(nextActivityReady)
            initialization.start()
            initialization.retry()
            runCurrent()
            assertEquals(1, loads)
            assertEquals(AssetStartupState.READY, initialization.state.value)
        }

    @Test
    fun failedInitializationStaysBlockedAcrossRecreationUntilRetryActuallySucceeds() =
        runTest {
            val releaseRetry = CompletableDeferred<Unit>()
            var attempts = 0
            val failures = mutableListOf<Exception>()
            val initialization =
                AssetInitialization(
                    backgroundScope,
                    initialize = {
                        attempts++
                        if (attempts == 1) throw IOException("Saved texture unavailable")
                        releaseRetry.await()
                    },
                    onFailure = failures::add,
                )
            initialization.start()
            runCurrent()
            assertEquals(AssetStartupState.FAILED, initialization.state.value)
            assertFalse(initialization.state.value.allowsEditor)
            assertEquals("Saved texture unavailable", failures.single().message)
            initialization.start()
            runCurrent()
            assertEquals(1, attempts)
            assertEquals(AssetStartupState.FAILED, initialization.state.value)

            initialization.retry()
            initialization.retry()
            runCurrent()
            assertEquals(2, attempts)
            assertEquals(AssetStartupState.LOADING, initialization.state.value)
            assertFalse(initialization.state.value.allowsEditor)
            releaseRetry.complete(Unit)
            runCurrent()
            assertEquals(AssetStartupState.READY, initialization.state.value)
        }

    @Test
    fun failedStartupNeedsAnExplicitChoiceToContinueWithAvailableAssets() =
        runTest {
            var attempts = 0
            val initialization =
                AssetInitialization(backgroundScope, initialize = {
                    attempts++
                    throw IOException("Missing asset")
                })
            initialization.start()
            runCurrent()
            assertFalse(initialization.state.value.allowsEditor)
            initialization.continueWithAvailableAssets()
            assertEquals(AssetStartupState.CONTINUING_WITH_AVAILABLE, initialization.state.value)
            assertTrue(initialization.state.value.allowsEditor)
            initialization.start()
            runCurrent()
            assertEquals(1, attempts)
            assertEquals(AssetStartupState.CONTINUING_WITH_AVAILABLE, initialization.state.value)
        }

    @Test
    fun cancellationCannotPublishReadyEvenWhenTheLoaderFinishesItsBlockingWork() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            var registered = false
            val initialization =
                AssetInitialization(scope, initialize = {
                    withContext(NonCancellable) { release.await() }
                    registered = true
                })
            initialization.start()
            runCurrent()
            scope.cancel()
            release.complete(Unit)
            runCurrent()
            assertTrue(registered)
            assertEquals(AssetStartupState.FAILED, initialization.state.value)
            assertFalse(initialization.state.value.allowsEditor)
        }

    @Test
    fun anAlreadyCancelledOwnerCannotLeaveStartupLoadingForeverOrMarkItReady() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            scope.cancel()
            var invoked = false
            val initialization = AssetInitialization(scope, initialize = { invoked = true })
            initialization.start()
            runCurrent()
            assertFalse(invoked)
            assertEquals(AssetStartupState.FAILED, initialization.state.value)
            assertFalse(initialization.state.value.allowsEditor)
        }
}
