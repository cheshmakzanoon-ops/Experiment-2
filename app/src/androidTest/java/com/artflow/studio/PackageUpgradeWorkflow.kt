package com.artflow.studio

import android.database.Cursor
import android.graphics.BitmapFactory
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.repository.BrushLibraryStore
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.MainActivity
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.min

/**
 * An explicit installed-package campaign mode of the existing artwork workflow test.
 * It declares no new/skipped tests and leaves the ordinary full-inventory workflow unchanged.
 * The host retains the old app's real stopped data; this probe never seeds or deletes artwork.
 */
internal class PackageUpgradeWorkflow(
    private val compose: ComposeTestRule,
    private val projects: ProjectRepository,
    private val canvas: CanvasRepository,
    private val settings: SettingsRepository,
    private val storage: ProjectStorage,
    private val database: ArtFlowDatabase,
) {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var baseline: JSONObject
    private lateinit var campaignDirectory: File
    private lateinit var baselineHash: String
    private lateinit var token: String
    private var projectId = 0L

    fun runIfRequested(): Boolean {
        val requested = arguments.getString("artflowPackageUpgradeToken") ?: return false
        check(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(requested)) { "Invalid package campaign token" }
        token = requested
        baselineHash = requireNotNull(arguments.getString("artflowPackageUpgradeBaselineSha256"))
        check(Regex("[0-9a-f]{64}").matches(baselineHash)) { "Missing pinned campaign baseline hash" }
        val phase = requireNotNull(arguments.getString("artflowPackageUpgradePhase"))
        check(phase in setOf("edit", "reopen")) { "Unknown package campaign phase" }
        campaignDirectory = File(context.filesDir, "package-upgrade/$token")
        val file = File(campaignDirectory, "baseline.json")
        check(file.isFile && file.length() in 1..MAX_BASELINE_BYTES) { "Missing or oversized old-app baseline" }
        assertEquals("The host's exact old-app baseline is required", baselineHash, digest(file.readBytes()))
        baseline = JSONObject(file.readText())
        assertEquals(1, baseline.getInt("schemaVersion"))
        assertEquals(token, baseline.getString("campaignToken"))
        assertEquals(context.packageName, baseline.getString("packageName"))
        projectId = baseline.getLong("projectId")
        check(projectId > 0)
        assertEquals(
            "The executing APK must be the host's pinned candidate",
            baseline.getString("candidateApkSha256"),
            digest(File(context.applicationInfo.sourceDir).readBytes()),
        )
        check(File(campaignDirectory, "$phase-started").createNewFile()) { "This campaign phase already ran; retain its first outcome" }
        if (phase == "edit") edit() else reopen()
        return true
    }

    private fun edit() {
        assertStoredRows()
        assertOldFilesUnchanged()
        val originalPreferences = runBlocking { settings.settings.first() }
        assertTrue(originalPreferences.seenOnboarding)
        assertFalse(originalPreferences.stylusOnly)
        assertFalse(originalPreferences.paletteRecoveryRequired)
        assertTrue("The old-app custom palette must decode", originalPreferences.customPalettes.isNotEmpty())
        runBlocking {
            val library = BrushLibraryStore(database.settingsDao())
            assertTrue("The old-app saved brush library must decode", library.brushes.first().isNotEmpty())
            library.favourites.first()
            library.recent.first()
            library.sets.first()
        }
        val before = runBlocking { requireNotNull(projects.getProjectById(projectId)) }
        assertEquals(baseline.getString("projectName"), before.name)
        assertEquals(storage.documentFile(projectId).canonicalPath, File(before.filePath).canonicalPath)
        assertEquals(storage.thumbnailFile(projectId).canonicalPath, File(requireNotNull(before.thumbnailPath)).canonicalPath)
        assertTrue(runBlocking { storage.hasUnsavedRecovery(projectId) })
        val expectedRecovery = expectedOldPixels()
        assertOldFilesUnchanged()
        lateinit var editedPixels: IntArray
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitGallery()
            compose.onNodeWithText(before.name).performClick()
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Recover").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Recover").performClick()
            awaitCanvas(scenario)
            compose.waitUntil(TIMEOUT_MS) { pixels().contentEquals(expectedRecovery) }
            assertArrayEquals("Recovery UI must restore the old app's newer artwork", expectedRecovery, pixels())
            TestEvidence.screenshot("package-upgrade-recovered.png")
            paint(scenario)
            compose.waitUntil(TIMEOUT_MS) { !pixels().contentEquals(expectedRecovery) }
            editedPixels = pixels()
            assertTrue(runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() })
            compose.onNodeWithText("Gallery").performClick()
            compose.onNodeWithText("Save changes?").assertIsDisplayed()
            compose.onNodeWithText("Save and leave").performClick()
            awaitGallery()
        }
        assertFalse("UI save must resolve recovery", runBlocking { storage.hasUnsavedRecovery(projectId) })
        assertArrayEquals("The saved flattened PNG must contain the edit", editedPixels, bitmapPixels(storage.flattenedFile(projectId)))
        val after = runBlocking { requireNotNull(projects.getProjectById(projectId)) }
        assertTrue(after.modifiedAt >= before.modifiedAt)
        assertEquals("Only the edited project's modification time should change", before.copy(modifiedAt = after.modifiedAt), after)
        assertEquals(originalPreferences, runBlocking { settings.settings.first() })
        assertStoredRows(skipEditedProject = true)
        val checkpoint =
            report("edit")
                .put("editedPixelsSha256", pixelDigest(editedPixels))
                .put("savedDocumentSha256", digest(storage.documentFile(projectId).readBytes()))
                .put("assertions", assertions(EDIT_ASSERTIONS))
        File(campaignDirectory, "checkpoint.json").writeText(checkpoint.toString(2))
        writeReport("edit", checkpoint)
    }

    private fun reopen() {
        val checkpoint = JSONObject(File(campaignDirectory, "checkpoint.json").readText())
        assertEquals(token, checkpoint.getString("campaignToken"))
        assertEquals(baselineHash, checkpoint.getString("baselineSha256"))
        assertEquals(projectId, checkpoint.getLong("projectId"))
        assertTrue("The host must stop the edit process before reopen", checkpoint.getInt("pid") != Process.myPid())
        assertStoredRows(skipEditedProject = true)
        assertFalse(runBlocking { storage.hasUnsavedRecovery(projectId) })
        assertEquals(checkpoint.getString("savedDocumentSha256"), digest(storage.documentFile(projectId).readBytes()))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitGallery()
            compose.onNodeWithText(baseline.getString("projectName")).performClick()
            awaitCanvas(scenario)
            assertEquals(
                "A fresh application process must load the edited pixels",
                checkpoint.getString("editedPixelsSha256"),
                pixelDigest(pixels()),
            )
            assertArrayEquals(bitmapPixels(storage.flattenedFile(projectId)), pixels())
            assertFalse(runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() })
            compose.onNodeWithText("Recover").assertDoesNotExist()
            TestEvidence.screenshot("package-upgrade-reopened.png")
            // Return through real navigation with a clean document before ActivityScenario closes.
            compose.onNodeWithText("Gallery").performClick()
            awaitGallery()
        }
        assertStoredRows(skipEditedProject = true)
        writeReport(
            "reopen",
            report("reopen")
                .put("editPid", checkpoint.getInt("pid"))
                .put("editedPixelsSha256", checkpoint.getString("editedPixelsSha256"))
                .put("assertions", assertions(REOPEN_ASSERTIONS)),
        )
    }

    /** An independent repository instance reads both retained old manifests without saving them. */
    private fun expectedOldPixels(): IntArray =
        runBlocking(Dispatchers.Main) {
            val reader = CanvasRepositoryImpl(storage)
            try {
                requireNotNull(reader.loadCanvas(projectId))
                val saved = requireNotNull(reader.compositeBuffer()).pixels.copyOf()
                assertArrayEquals(
                    "Decoded saved artwork must match the old app's flattened PNG",
                    bitmapPixels(storage.flattenedFile(projectId)),
                    saved,
                )
                requireNotNull(reader.recoverAutosave(projectId))
                val recovered = requireNotNull(reader.compositeBuffer()).pixels.copyOf()
                assertFalse("The real old-app recovery must contain a different edit", saved.contentEquals(recovered))
                recovered
            } finally {
                reader.dispose()
            }
        }

    private fun assertOldFilesUnchanged() {
        val files = baseline.getJSONObject("files")
        check(files.length() > 0)
        for (relative in files.keys()) {
            check(relative.startsWith("files/") && relative.split('/').none { it == ".." })
            // Android may alias /data/user/0 to /data/data. Canonicalize the app root before
            // rejecting links below it, so legitimate OS data-directory aliases remain valid.
            val file = File(context.dataDir.canonicalFile, relative)
            check(file.canonicalPath == file.absolutePath && file.canonicalPath.startsWith(context.filesDir.canonicalPath + "/"))
            val expected = files.getJSONObject(relative)
            assertTrue("Old-app file missing: $relative", file.isFile)
            assertEquals(relative, expected.getLong("sizeBytes"), file.length())
            assertEquals(relative, expected.getLong("modifiedSeconds"), file.lastModified() / 1000)
            assertEquals(relative, expected.getString("sha256"), digest(file.readBytes()))
        }
    }

    /** Preserve SQLite storage classes as well as values; no JSON-number rounding of 64-bit IDs. */
    private fun assertStoredRows(skipEditedProject: Boolean = false) =
        runBlocking(Dispatchers.IO) {
            val expectedDatabase = baseline.getJSONObject("database")
            val sqlite = database.openHelper.writableDatabase
            assertEquals("This package campaign covers v2 continuity, not a v1 migration", 2, expectedDatabase.getInt("version"))
            assertEquals(2, sqlite.version)
            sqlite.query("PRAGMA quick_check").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
            val tables = expectedDatabase.getJSONObject("tables")
            TABLES.forEach { (table, order) ->
                val expected = tables.getJSONObject(table)
                val columns = expected.getJSONArray("columns")
                val rows = expected.getJSONArray("rows")
                val where = if (skipEditedProject && table == "projects") " WHERE id <> $projectId" else ""
                sqlite.query("SELECT * FROM $table$where ORDER BY `$order`").use { cursor ->
                    assertEquals(columns.length(), cursor.columnCount)
                    repeat(columns.length()) { index -> assertEquals(columns.getString(index), cursor.getColumnName(index)) }
                    for (rowIndex in 0 until rows.length()) {
                        val row = rows.getJSONArray(rowIndex)
                        if (skipEditedProject &&
                            table == "projects" &&
                            row.getJSONObject(0).getString("value") == projectId.toString()
                        ) {
                            continue
                        }
                        assertTrue("Missing $table row $rowIndex", cursor.moveToNext())
                        repeat(columns.length()) { index ->
                            val expectedCell = row.getJSONObject(index)
                            val (type, value) = cell(cursor, index)
                            assertEquals("$table row $rowIndex ${columns.getString(index)} type", expectedCell.getString("type"), type)
                            if (type !=
                                "null"
                            ) {
                                assertEquals(
                                    "$table row $rowIndex ${columns.getString(index)} value",
                                    expectedCell.getString("value"),
                                    value,
                                )
                            }
                        }
                    }
                    assertFalse("Unexpected $table row", cursor.moveToNext())
                }
            }
        }

    private fun cell(
        cursor: Cursor,
        index: Int,
    ): Pair<String, String> =
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> "null" to ""
            Cursor.FIELD_TYPE_INTEGER -> "integer" to cursor.getLong(index).toString()
            Cursor.FIELD_TYPE_FLOAT ->
                "real" to
                    java.lang.Long
                        .toHexString(java.lang.Double.doubleToRawLongBits(cursor.getDouble(index)))
                        .padStart(16, '0')
            Cursor.FIELD_TYPE_BLOB -> "blob" to hex(cursor.getBlob(index))
            else -> "text" to cursor.getString(index)
        }

    private fun awaitGallery() {
        compose.waitUntil(
            TIMEOUT_MS,
        ) { compose.onAllNodesWithText("New artwork", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun awaitCanvas(scenario: ActivityScenario<MainActivity>) {
        compose.waitUntil(TIMEOUT_MS) {
            var ready = false
            scenario.onActivity { ready = findCanvas(it.window.decorView)?.let { view -> view.width > 0 && view.height > 0 } == true }
            ready
        }
        compose.waitForIdle()
    }

    private fun pixels(): IntArray = runBlocking(Dispatchers.Main) { requireNotNull(canvas.compositeBuffer()).pixels.copyOf() }

    private fun paint(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            val view = requireNotNull(findCanvas(it.window.decorView))
            val down = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val side = min(view.width, view.height)
                val event =
                    MotionEvent.obtain(
                        down,
                        down + index * 20,
                        action,
                        view.width * 0.5f + side * (index - 1) * 0.1f,
                        view.height * 0.5f + side * 0.2f,
                        0,
                    )
                try {
                    assertTrue(view.dispatchTouchEvent(event))
                } finally {
                    event.recycle()
                }
            }
        }
        compose.waitForIdle()
    }

    private fun findCanvas(view: View): ArtFlowCanvasView? {
        if (view is ArtFlowCanvasView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun bitmapPixels(file: File): IntArray {
        val image = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try {
            assertEquals(64, image.width)
            assertEquals(64, image.height)
            return IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
        } finally {
            image.recycle()
        }
    }

    private fun report(phase: String): JSONObject =
        JSONObject()
            .put("schemaVersion", 1)
            .put("campaignToken", token)
            .put("phase", phase)
            .put("baselineSha256", baselineHash)
            .put("packageName", context.packageName)
            .put("oldApkSha256", baseline.getString("oldApkSha256"))
            .put("candidateApkSha256", baseline.getString("candidateApkSha256"))
            .put("projectId", projectId)
            .put("pid", Process.myPid())
            .put("success", true)

    private fun writeReport(
        phase: String,
        report: JSONObject,
    ) {
        val output = File(TestEvidence.directory(), "package-upgrade-$phase.json")
        check(!output.exists()) { "Never overwrite the first campaign report" }
        output.writeText(report.toString(2))
    }

    private fun assertions(names: List<String>): JSONObject = JSONObject().apply { names.forEach { put(it, true) } }

    private fun pixelDigest(pixels: IntArray): String =
        digest(
            ByteBuffer
                .allocate(pixels.size * Int.SIZE_BYTES)
                .apply {
                    pixels.forEach { putInt(it) }
                }.array(),
        )

    private fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    companion object {
        private const val TIMEOUT_MS = 15_000L
        private const val MAX_BASELINE_BYTES = 8L * 1024 * 1024
        private val TABLES = mapOf("projects" to "id", "brushes" to "id", "settings" to "key", "sqlite_sequence" to "name")
        private val EDIT_ASSERTIONS =
            listOf(
                "allRowsPreservedBeforeEdit",
                "allArtworkBytesPreservedBeforeEdit",
                "settingsAndLibraryDecoded",
                "savedPixelsMatchOldFlattened",
                "newerRecoveryLoadedThroughUi",
                "realPointerChangedArtwork",
                "savedThroughUi",
            )
        private val REOPEN_ASSERTIONS =
            listOf("freshProcess", "editedPixelsReopened", "settingsPreserved", "otherProjectsPreserved", "recoveryResolved")
    }
}
