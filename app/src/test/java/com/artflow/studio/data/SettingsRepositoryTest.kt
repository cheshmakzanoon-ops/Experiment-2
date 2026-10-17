package com.artflow.studio.data

import com.artflow.studio.core.canvas.StylusAction
import com.artflow.studio.core.canvas.StylusEraserEnd
import com.artflow.studio.core.color.Palette
import com.artflow.studio.core.color.PaletteCodec
import com.artflow.studio.data.local.dao.SettingsDao
import com.artflow.studio.data.local.entity.SettingsEntity
import com.artflow.studio.data.repository.settings.SettingsRepositoryImpl
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.model.settings.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    @Test
    fun firstCollectionLoadsPreferencesWithoutAWrite() =
        runTest {
            val dao = MemorySettingsDao(mapOf("theme.mode" to "DARK", "general.onboardingSeen" to "true"))
            val repository = SettingsRepositoryImpl(dao)
            val stored = repository.settings.first()
            assertEquals(ThemeMode.DARK, stored.themeMode)
            assertTrue(stored.seenOnboarding)
            assertEquals(1, dao.reads)
            assertEquals(0, dao.writes)
        }

    @Test
    fun updateWaitsForTheInitialReadWithoutOverwritingOtherPreferences() =
        runTest {
            val dao = MemorySettingsDao(mapOf("theme.mode" to "DARK"))
            dao.readGate = CompletableDeferred()
            val repository = SettingsRepositoryImpl(dao)
            val first = async { repository.settings.first() }
            runCurrent()
            val edit = launch { repository.setHighContrast(true) }
            runCurrent()
            assertFalse(first.isCompleted)
            assertFalse(edit.isCompleted)
            assertEquals(0, dao.writes)
            dao.readGate!!.complete(Unit)
            first.await()
            edit.join()
            assertEquals(ThemeMode.DARK, repository.current().themeMode)
            assertTrue(repository.current().highContrast)
            assertEquals(1, dao.reads)
        }

    @Test
    fun timelapseQualityPersistsAndIgnoresUnknownSizes() =
        runTest {
            val dao = MemorySettingsDao(mapOf("timelapse.maxSide" to "999"))
            val repository = SettingsRepositoryImpl(dao)
            assertEquals(1280, repository.settings.first().timelapseMaxSide)
            repository.update { it.copy(timelapseMaxSide = 1920) }
            assertEquals(1920, SettingsRepositoryImpl(dao).settings.first().timelapseMaxSide)
        }

    @Test
    fun quickMenuSlotsPersistAndIgnoreDamagedValues() =
        runTest {
            val dao = MemorySettingsDao(mapOf("studio.quickMenu" to "Copy|Paste"))
            val repository = SettingsRepositoryImpl(dao)
            val defaults = repository.settings.first().quickMenu
            assertEquals(6, defaults.size)
            val chosen = listOf("Undo") + defaults.drop(1)
            repository.update { it.copy(quickMenu = chosen) }
            assertEquals(chosen, SettingsRepositoryImpl(dao).settings.first().quickMenu)
        }

    @Test
    fun overlappingEditsAreSerializedAndPublishOnlyAfterPersistence() =
        runTest {
            val dao = MemorySettingsDao()
            dao.writeGate = CompletableDeferred()
            val repository = SettingsRepositoryImpl(dao)
            repository.settings.first()
            val first = launch { repository.setThemeMode(ThemeMode.DARK) }
            runCurrent()
            val second = launch { repository.setHighContrast(true) }
            runCurrent()
            assertEquals(1, dao.writes)
            assertEquals(ThemeMode.SYSTEM, repository.current().themeMode)
            dao.writeGate!!.complete(Unit)
            first.join()
            second.join()
            assertEquals(ThemeMode.DARK, repository.current().themeMode)
            assertTrue(repository.current().highContrast)
            val reopened = SettingsRepositoryImpl(dao).settings.first()
            assertEquals(repository.current(), reopened)
        }

    @Test
    fun failedWriteDoesNotPublishAndCanBeRetried() =
        runTest {
            val dao = MemorySettingsDao(mapOf("theme.mode" to "DARK"))
            val repository = SettingsRepositoryImpl(dao)
            val before = repository.settings.first()
            dao.writeFailure = IOException("Disk full")
            assertTrue(runCatching { repository.setHighContrast(true) }.exceptionOrNull() is IOException)
            assertEquals(before, repository.current())
            assertEquals("DARK", dao.rows["theme.mode"]!!.value)
            assertNull(dao.rows["access.highContrast"])
            dao.writeFailure = null
            repository.setHighContrast(true)
            assertTrue(repository.current().highContrast)
        }

    @Test
    fun failedReadDoesNotCacheDefaultsOrAllowDestructiveWrites() =
        runTest {
            val dao = MemorySettingsDao(mapOf("theme.mode" to "DARK"))
            dao.readFailure = IOException("Temporarily unavailable")
            val repository = SettingsRepositoryImpl(dao)
            assertTrue(runCatching { repository.setHighContrast(true) }.isFailure)
            assertEquals(0, dao.writes)
            dao.readFailure = null
            assertEquals(ThemeMode.DARK, repository.settings.first().themeMode)
            assertEquals(2, dao.reads)
        }

    @Test
    fun cancelledReadIsRetriedInsteadOfRememberedAsLoaded() =
        runTest {
            val dao = MemorySettingsDao(mapOf("theme.mode" to "DARK"))
            dao.readGate = CompletableDeferred()
            val repository = SettingsRepositoryImpl(dao)
            val first = launch { repository.settings.first() }
            runCurrent()
            first.cancel()
            first.join()
            dao.readGate!!.complete(Unit)
            assertEquals(ThemeMode.DARK, repository.settings.first().themeMode)
            assertEquals(2, dao.reads)
            assertEquals(0, dao.writes)
        }

    @Test
    fun cancellationDuringCommitStillPublishesTheCommittedValue() =
        runTest {
            val dao = MemorySettingsDao()
            dao.writeGate = CompletableDeferred()
            val repository = SettingsRepositoryImpl(dao)
            val edit = launch { repository.setHighContrast(true) }
            runCurrent()
            assertEquals(1, dao.writes)
            edit.cancel()
            dao.writeGate!!.complete(Unit)
            edit.join()
            assertTrue(edit.isCancelled)
            assertTrue(repository.current().highContrast)
            assertTrue(SettingsRepositoryImpl(dao).settings.first().highContrast)
        }

    @Test
    fun noOpDoesNotRewriteStorage() =
        runTest {
            val dao = MemorySettingsDao(mapOf("theme.mode" to "DARK"))
            val repository = SettingsRepositoryImpl(dao)
            repository.setThemeMode(ThemeMode.DARK)
            repository.update { it }
            assertEquals(0, dao.writes)
        }

    @Test
    fun paletteNamesWithSeparatorsQuotesAndUnicodeSurviveRestart() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val palette = Palette(id = 4, name = "Ink;; \"blue\\green\" 🎨", colors = listOf(-1, -16777216))
            repository.addPalette(palette)
            assertTrue(dao.rows["color.palettes"]!!.value.startsWith("["))
            assertEquals(listOf(palette), SettingsRepositoryImpl(dao).settings.first().customPalettes)
        }

    @Test
    fun legacyPaletteDelimitersAreSplitOnlyBetweenCompleteObjects() =
        runTest {
            val first = Palette(id = 1, name = "A;;B\\\";;C", colors = listOf(1, 2))
            val second = Palette(id = 2, name = "};[;;]", colors = listOf(3, 4))
            val legacy = PaletteCodec.exportJson(first) + ";;" + PaletteCodec.exportJson(second)
            val dao = MemorySettingsDao(mapOf("color.palettes" to legacy))
            val repository = SettingsRepositoryImpl(dao)
            assertEquals(listOf(first, second), repository.settings.first().customPalettes)
            repository.setHighContrast(true)
            assertTrue(dao.rows["color.palettes"]!!.value.startsWith("["))
            assertEquals(listOf(first, second), SettingsRepositoryImpl(dao).settings.first().customPalettes)
        }

    @Test
    fun damagedPalettesDoNotBlockOtherPreferencesOrGetOverwritten() =
        runTest {
            val damaged = "{\"name\":\"unclosed"
            val dao = MemorySettingsDao(mapOf("color.palettes" to damaged, "theme.mode" to "DARK"))
            val repository = SettingsRepositoryImpl(dao)
            val loaded = repository.settings.first()
            assertEquals(ThemeMode.DARK, loaded.themeMode)
            assertTrue(loaded.paletteRecoveryRequired)
            repository.setHighContrast(true)
            assertTrue(repository.current().highContrast)
            assertEquals(damaged, dao.rows["color.palettes"]!!.value)
            assertEquals(1, dao.writes)
            assertTrue(SettingsRepositoryImpl(dao).settings.first().paletteRecoveryRequired)
        }

    @Test
    fun paletteEditsCannotBypassTheRecoveryGuard() =
        runTest {
            val dao = MemorySettingsDao(mapOf("color.palettes" to "invalid"))
            val repository = SettingsRepositoryImpl(dao)
            assertTrue(runCatching { repository.addPalette(Palette(name = "New", colors = listOf(1))) }.isFailure)
            repository.update { it.copy(paletteRecoveryRequired = false, highContrast = true) }
            assertTrue(repository.current().paletteRecoveryRequired)
            assertTrue(repository.current().highContrast)
            assertEquals("invalid", dao.rows["color.palettes"]!!.value)
        }

    @Test
    fun successfulBackupPreservesExactOriginalBeforeResetAndAllowsNewPalettes() =
        runTest {
            val damaged = "  {broken;; \"ink\" 🎨\n"
            val dao = MemorySettingsDao(mapOf("color.palettes" to damaged, "theme.mode" to "DARK"))
            val repository = SettingsRepositoryImpl(dao)
            var backup: String? = null
            val reset =
                repository.backupAndResetUnreadablePalettes {
                    assertEquals(damaged, dao.rows["color.palettes"]!!.value)
                    backup = it
                }
            assertTrue(reset)
            assertEquals(damaged, backup)
            assertFalse(repository.current().paletteRecoveryRequired)
            assertTrue(repository.current().customPalettes.isEmpty())
            assertEquals(ThemeMode.DARK, repository.current().themeMode)
            val palette = Palette(name = "New", colors = listOf(1))
            repository.addPalette(palette)
            assertEquals(listOf(palette), SettingsRepositoryImpl(dao).settings.first().customPalettes)
        }

    @Test
    fun failedBackupDoesNotWriteResetOrClearTheWarning() =
        runTest {
            val dao = MemorySettingsDao(mapOf("color.palettes" to "invalid"))
            val repository = SettingsRepositoryImpl(dao)
            assertTrue(runCatching { repository.backupAndResetUnreadablePalettes { throw IOException("Provider failed") } }.isFailure)
            assertTrue(repository.current().paletteRecoveryRequired)
            assertEquals("invalid", dao.rows["color.palettes"]!!.value)
            assertEquals(0, dao.writes)
        }

    @Test
    fun cancelledBackupNeverReachesTheResetTransaction() =
        runTest {
            val dao = MemorySettingsDao(mapOf("color.palettes" to "invalid"))
            val repository = SettingsRepositoryImpl(dao)
            val gate = CompletableDeferred<Unit>()
            val operation = launch { repository.backupAndResetUnreadablePalettes { gate.await() } }
            runCurrent()
            operation.cancel()
            operation.join()
            assertEquals(0, dao.writes)
            assertTrue(repository.current().paletteRecoveryRequired)
            assertEquals("invalid", dao.rows["color.palettes"]!!.value)
        }

    @Test
    fun unrelatedEditsDuringBackupAreNotLostOrBlocked() =
        runTest {
            val dao = MemorySettingsDao(mapOf("color.palettes" to "invalid"))
            val repository = SettingsRepositoryImpl(dao)
            val gate = CompletableDeferred<Unit>()
            val backup = launch { repository.backupAndResetUnreadablePalettes { gate.await() } }
            runCurrent()
            repository.setThemeMode(ThemeMode.DARK)
            assertTrue(backup.isActive)
            gate.complete(Unit)
            backup.join()
            assertEquals(ThemeMode.DARK, repository.current().themeMode)
            assertFalse(repository.current().paletteRecoveryRequired)
            assertEquals(repository.current(), SettingsRepositoryImpl(dao).settings.first())
        }

    @Test
    fun failedResetRetainsTheOriginalEvenAfterBackupSucceeds() =
        runTest {
            val dao = MemorySettingsDao(mapOf("color.palettes" to "invalid"))
            dao.writeFailure = IOException("Disk full")
            val repository = SettingsRepositoryImpl(dao)
            var backedUp = false
            assertTrue(runCatching { repository.backupAndResetUnreadablePalettes { backedUp = true } }.isFailure)
            assertTrue(backedUp)
            assertTrue(repository.current().paletteRecoveryRequired)
            assertEquals("invalid", dao.rows["color.palettes"]!!.value)
            dao.writeFailure = null
            assertTrue(repository.backupAndResetUnreadablePalettes {})
            assertFalse(repository.current().paletteRecoveryRequired)
        }

    @Test
    fun aStaleBackupCannotResetNewlySavedPalettes() =
        runTest {
            val dao = MemorySettingsDao(mapOf("color.palettes" to "invalid"))
            val repository = SettingsRepositoryImpl(dao)
            val gate = CompletableDeferred<Unit>()
            val stale =
                async {
                    runCatching { repository.backupAndResetUnreadablePalettes { gate.await() } }
                }
            runCurrent()
            assertTrue(repository.backupAndResetUnreadablePalettes {})
            val palette = Palette(name = "Keep", colors = listOf(1))
            repository.addPalette(palette)
            gate.complete(Unit)
            assertTrue(stale.await().isFailure)
            assertEquals(listOf(palette), SettingsRepositoryImpl(dao).settings.first().customPalettes)
        }

    @Test
    fun recoveryWithoutCorruptionIsANoOp() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            assertFalse(repository.backupAndResetUnreadablePalettes { error("Unexpected backup") })
            assertEquals(0, dao.writes)
        }

    @Test
    fun invalidScaleIsRejectedAndCorruptStoredScaleUsesSafeDefault() =
        runTest {
            val dao = MemorySettingsDao(mapOf("access.uiScale" to "NaN"))
            val repository = SettingsRepositoryImpl(dao)
            assertEquals(1f, repository.settings.first().uiScale, 0f)
            for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertTrue(runCatching { repository.setUiScale(value) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertEquals(0, dao.writes)
            repository.setUiScale(99f)
            assertEquals(1.6f, repository.current().uiScale, 0f)
        }

    @Test
    fun callerOwnedCollectionsAndPublishedSnapshotsCannotMutateTheCache() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val colors = mutableListOf(1, 2)
            val recent = mutableListOf(3, 4)
            repository.update { it.copy(customPalettes = listOf(Palette(name = "Owned", colors = colors)), recentColors = recent) }
            colors.clear()
            recent.clear()
            val snapshot = repository.settings.first()
            (snapshot.customPalettes[0].colors as MutableList<Int>).clear()
            (snapshot.recentColors as MutableList<Int>).clear()
            assertEquals(listOf(1, 2), repository.current().customPalettes[0].colors)
            assertEquals(listOf(3, 4), repository.current().recentColors)
            assertEquals(repository.current(), SettingsRepositoryImpl(dao).settings.first())
        }

    @Test
    fun aThrowingTransformCannotMutateCachedCollections() =
        runTest {
            val repository = SettingsRepositoryImpl(MemorySettingsDao())
            repository.update { it.copy(recentColors = listOf(1, 2)) }
            val before = repository.current()
            assertTrue(
                runCatching {
                    repository.update {
                        (it.recentColors as MutableList<Int>).clear()
                        error("Aborted transform")
                    }
                }.isFailure,
            )
            assertEquals(before, repository.current())
        }

    /** One boolean switch: its storage key, and how it reads and writes [AppSettings]. */
    private class Switch(
        val key: String,
        val read: (AppSettings) -> Boolean,
        val write: (AppSettings, Boolean) -> AppSettings,
    )

    private val switches =
        listOf(
            Switch("gesture.scrubToClear", { it.scrubToClear }, { s, v -> s.copy(scrubToClear = v) }),
            Switch("gesture.swipeCopyPaste", { it.swipeCopyPaste }, { s, v -> s.copy(swipeCopyPaste = v) }),
            Switch("gesture.fourFingerFullScreen", { it.fourFingerFullScreen }, { s, v -> s.copy(fourFingerFullScreen = v) }),
            Switch("gesture.twoFingerTapUndo", { it.twoFingerTapUndo }, { s, v -> s.copy(twoFingerTapUndo = v) }),
            Switch("gesture.threeFingerTapRedo", { it.threeFingerTapRedo }, { s, v -> s.copy(threeFingerTapRedo = v) }),
            Switch("gesture.holdRapidUndoRedo", { it.holdRapidUndoRedo }, { s, v -> s.copy(holdRapidUndoRedo = v) }),
            Switch("gesture.pinchNavigation", { it.pinchNavigation }, { s, v -> s.copy(pinchNavigation = v) }),
            Switch("gesture.quickPinchFit", { it.quickPinchFit }, { s, v -> s.copy(quickPinchFit = v) }),
            Switch("gesture.touchHoldEyedropper", { it.touchHoldEyedropper }, { s, v -> s.copy(touchHoldEyedropper = v) }),
            Switch("gesture.layerTwoFingerOpacity", { it.layerTwoFingerOpacity }, { s, v -> s.copy(layerTwoFingerOpacity = v) }),
            Switch(
                "gesture.layerTwoFingerAlphaLock",
                { it.layerTwoFingerAlphaLock },
                { s, v -> s.copy(layerTwoFingerAlphaLock = v) },
            ),
            Switch("gesture.layerTwoFingerSelect", { it.layerTwoFingerSelect }, { s, v -> s.copy(layerTwoFingerSelect = v) }),
            Switch("gesture.modifyHoldQuickMenu", { it.modifyHoldQuickMenu }, { s, v -> s.copy(modifyHoldQuickMenu = v) }),
            Switch("input.quickShape", { it.quickShape }, { s, v -> s.copy(quickShape = v) }),
        )

    @Test
    fun everyGestureAndQuickShapeSwitchDefaultsToOnWhenNothingIsStored() =
        runTest {
            val settings = SettingsRepositoryImpl(MemorySettingsDao()).settings.first()
            for (switch in switches) assertTrue(switch.key, switch.read(settings))
        }

    @Test
    fun everyGestureAndQuickShapeSwitchSavesItsOwnKeyAndSurvivesARestart() =
        runTest {
            for (toggle in switches) {
                val dao = MemorySettingsDao()
                val repository = SettingsRepositoryImpl(dao)
                repository.update { toggle.write(it, false) }
                assertEquals(toggle.key, "false", dao.rows[toggle.key]!!.value)
                val reopened = SettingsRepositoryImpl(dao).settings.first()
                assertFalse(toggle.key, toggle.read(reopened))
                for (other in switches.filter { it !== toggle }) {
                    assertTrue("${other.key} must not change with ${toggle.key}", other.read(reopened))
                }
                repository.update { toggle.write(it, true) }
                assertEquals(toggle.key, "true", dao.rows[toggle.key]!!.value)
                assertTrue(toggle.key, toggle.read(SettingsRepositoryImpl(dao).settings.first()))
            }
        }

    @Test
    fun aDamagedSwitchValueFallsBackToItsDefault() =
        runTest {
            val dao = MemorySettingsDao(mapOf("gesture.pinchNavigation" to "sometimes", "input.quickShape" to "2"))
            val settings = SettingsRepositoryImpl(dao).settings.first()
            assertTrue(settings.pinchNavigation)
            assertTrue(settings.quickShape)
        }

    @Test
    fun stylusButtonsDefaultToTheBehaviourTheSideButtonAlwaysHad() =
        runTest {
            val settings = SettingsRepositoryImpl(MemorySettingsDao()).settings.first()
            assertEquals(StylusAction.EYEDROPPER, settings.stylusPrimaryButton)
            assertEquals(StylusAction.NOTHING, settings.stylusSecondaryButton)
            assertEquals(StylusEraserEnd.ERASER, settings.stylusEraserEnd)
        }

    @Test
    fun everyStylusActionSurvivesARestartOnEachButtonAndIsStoredByName() =
        runTest {
            // A no-op edit is never written, so first move each button off its default; then every
            // action is really stored, including the defaults themselves.
            for (action in StylusAction.entries) {
                val dao = MemorySettingsDao()
                val repository = SettingsRepositoryImpl(dao)
                repository.update { it.copy(stylusPrimaryButton = StylusAction.SMUDGE, stylusSecondaryButton = StylusAction.SMUDGE) }
                repository.update { it.copy(stylusPrimaryButton = action, stylusSecondaryButton = action) }
                assertEquals(action.name, dao.rows["input.stylusPrimaryButton"]!!.value)
                assertEquals(action.name, dao.rows["input.stylusSecondaryButton"]!!.value)
                val reopened = SettingsRepositoryImpl(dao).settings.first()
                assertEquals(action, reopened.stylusPrimaryButton)
                assertEquals(action, reopened.stylusSecondaryButton)
            }
            for (end in StylusEraserEnd.entries) {
                val dao = MemorySettingsDao()
                val repository = SettingsRepositoryImpl(dao)
                repository.update { it.copy(stylusEraserEnd = StylusEraserEnd.NOTHING) }
                repository.update { it.copy(stylusEraserEnd = end) }
                assertEquals(end.name, dao.rows["input.stylusEraserEnd"]!!.value)
                assertEquals(end, SettingsRepositoryImpl(dao).settings.first().stylusEraserEnd)
            }
        }

    @Test
    fun aStylusNameThatIsNoLongerKnownFallsBackToItsDefault() =
        runTest {
            val dao = MemorySettingsDao(mapOf("input.stylusPrimaryButton" to "LASER", "input.stylusEraserEnd" to "BOTH"))
            val settings = SettingsRepositoryImpl(dao).settings.first()
            assertEquals(StylusAction.EYEDROPPER, settings.stylusPrimaryButton)
            assertEquals(StylusEraserEnd.ERASER, settings.stylusEraserEnd)
        }

    private class MemorySettingsDao(
        initial: Map<String, String> = emptyMap(),
    ) : SettingsDao {
        val rows = initial.mapValues { (key, value) -> SettingsEntity(key = key, value = value) }.toMutableMap()
        var reads = 0
        var writes = 0
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var readFailure: IOException? = null
        var writeFailure: IOException? = null

        override fun getAllSettings(): Flow<List<SettingsEntity>> =
            flow {
                reads++
                readGate?.await()
                readFailure?.let { throw it }
                emit(rows.values.toList())
            }

        override suspend fun insertSettings(settings: List<SettingsEntity>) {
            writes++
            writeGate?.await()
            writeFailure?.let { throw it }
            settings.forEach { rows[it.key] = it }
        }

        override suspend fun getSettingByKey(key: String): SettingsEntity? = rows[key]

        override fun getSettingsByCategory(category: String): Flow<List<SettingsEntity>> =
            flow { emit(rows.values.filter { it.category == category }) }

        override suspend fun insertSetting(setting: SettingsEntity): Unit = error("Single-row writes are not atomic")

        override suspend fun updateSetting(setting: SettingsEntity): Unit = error("Unused")

        override suspend fun deleteSetting(setting: SettingsEntity): Unit = error("Unused")

        override suspend fun deleteSettingByKey(key: String): Unit = error("Unused")

        override suspend fun updateSettingValue(
            key: String,
            value: String,
            timestamp: Long,
        ): Unit = error("Unused")
    }
}
