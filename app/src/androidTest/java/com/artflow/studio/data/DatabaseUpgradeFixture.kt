package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.artflow.studio.core.color.Palette
import com.artflow.studio.core.color.PaletteCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.local.entity.BrushEntity
import com.artflow.studio.data.local.entity.ProjectEntity
import com.artflow.studio.data.local.entity.SettingsEntity
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.BrushLibraryStore
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import org.json.JSONObject
import org.junit.Assert.assertEquals
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Actual SQLite files from the committed schemas; no current Room entity creates the old tables. */
internal class DatabaseUpgradeFixture(
    application: Context,
) {
    private val prefix = "database-upgrade-${System.nanoTime()}-"
    private val directory = Files.createTempDirectory(application.cacheDir.toPath(), prefix).toFile()

    // Exercise the unchanged production database provider without sharing the app's real database.
    // SQLiteOpenHelper uses openOrCreateDatabase; Room also asks for the application context/path.
    val context: Context =
        object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = directory

            override fun getDatabasePath(name: String): File = application.getDatabasePath(prefix + name)

            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
            ): SQLiteDatabase = application.openOrCreateDatabase(prefix + name, mode, factory)

            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?,
            ): SQLiteDatabase = application.openOrCreateDatabase(prefix + name, mode, factory, errorHandler)

            override fun deleteDatabase(name: String): Boolean = application.deleteDatabase(prefix + name)
        }

    val storage = ProjectStorage(context)
    val projects =
        listOf(
            ProjectEntity(
                id = ARTWORK_ID,
                name = "Artist's \"Ocean\" 🎨",
                filePath = storage.documentFile(ARTWORK_ID).absolutePath,
                thumbnailPath = storage.thumbnailFile(ARTWORK_ID).absolutePath,
                width = WIDTH,
                height = HEIGHT,
                dpi = 240,
                createdAt = 1_600_000_000_001L,
                modifiedAt = 1_700_000_000_100L,
                layerCount = 1,
                isFavorite = true,
            ),
            ProjectEntity(
                id = DRAFT_ID,
                name = "Unpainted draft",
                filePath = "",
                thumbnailPath = null,
                width = 128,
                height = 96,
                dpi = 300,
                createdAt = 1_600_000_000_002L,
                modifiedAt = 1_700_000_000_200L,
                layerCount = 3,
                isFavorite = false,
            ),
        )
    val brushes =
        listOf(
            BrushEntity(
                id = 17,
                name = "Custom ink 🎨",
                category = "Artist's set",
                shape = "ROUND",
                texture = "paper",
                size = 37.5f,
                opacity = 0.625f,
                spacing = 0.125f,
                scatter = 0.25f,
                rotation = 45.5f,
                count = 3,
                pressureSizeCurve = "[0.125,0.5,0.875]",
                pressureOpacityCurve = "[0.25,0.625,1.0]",
                colorDynamics = "{\"hue\":0.25}",
                isDefault = false,
                thumbnailPath = storage.thumbnailFile(ARTWORK_ID).absolutePath,
                createdAt = 1_600_000_000_003L,
            ),
            BrushEntity(
                id = 29,
                name = "Original pencil",
                category = "Built in",
                shape = "SQUARE",
                texture = null,
                size = 8f,
                opacity = 1f,
                spacing = 0.5f,
                scatter = 0f,
                rotation = 0f,
                count = 1,
                pressureSizeCurve = null,
                pressureOpacityCurve = null,
                colorDynamics = null,
                isDefault = true,
                thumbnailPath = null,
                createdAt = 1_600_000_000_004L,
            ),
        )
    val palette = Palette(id = 23, name = "Ocean;; \"ink\" 🎨", colors = listOf(-1, -16777216, -15436358))
    val savedBrushes =
        listOf(
            SavedBrush(
                id = "preserved-ink",
                name = "Saved ink 🎨",
                parameters = BrushParams(size = 37.5f, opacity = 0.625f, textureId = "paper", tiltInfluence = 0.5f),
            ),
        )
    val favouriteBrushes = setOf("saved-preserved-ink", "pencil")
    val recentBrushes = listOf("saved-preserved-ink", "pencil")
    val brushSets = mapOf("Travel 🎨" to recentBrushes)
    val settings =
        listOf(
            setting("theme.mode", "DARK", "appearance"),
            setting("access.highContrast", "true", "appearance"),
            setting("input.stylusOnly", "true", "input"),
            setting("general.onboardingSeen", "true"),
            setting("general.autosaveInterval", "45000"),
            setting("gallery.sort", "MANUAL"),
            setting("gallery.order", "$DRAFT_ID,$ARTWORK_ID"),
            setting("color.defaultPalette", palette.name),
            // Delimiter-separated palette objects were accepted before JSON-array storage.
            setting("color.palettes", PaletteCodec.exportJson(palette)),
            setting(BrushLibraryStore.STORAGE_KEY, BrushLibraryCodec.encode(savedBrushes), "brush"),
            setting(BrushLibraryStore.FAVOURITES_KEY, favouriteBrushes.joinToString("\n"), "brush"),
            setting(BrushLibraryStore.RECENT_KEY, recentBrushes.joinToString("\n"), "brush"),
            setting(BrushLibraryStore.SETS_KEY, "{\"Travel 🎨\":[\"saved-preserved-ink\",\"pencil\"]}", "brush"),
            setting("future.unknown-setting", "  retain \"quotes\";;\n🎨\n", "future"),
        )

    /** Seed old on-disk tables, their Room identity, and deleted-id AUTOINCREMENT high-water marks. */
    fun create(
        schemaVersion: Int = 1,
        databaseVersion: Int = schemaVersion,
        change: (SupportSQLiteDatabase) -> Unit = {},
    ) {
        val schema = schema(schemaVersion)
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(ArtFlowDatabase.DATABASE_NAME)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(databaseVersion) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                val entities = schema.getJSONArray("entities")
                                repeat(entities.length()) { index ->
                                    val entity = entities.getJSONObject(index)
                                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                                }
                                val setup = schema.getJSONArray("setupQueries")
                                repeat(setup.length()) { db.execSQL(setup.getString(it)) }
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = error("Fixture creation must not upgrade $oldVersion to $newVersion")
                        },
                    ).build(),
            )
        helper.use {
            val db = it.writableDatabase
            db.beginTransaction()
            try {
                projects.forEach { project -> insertProject(db, project) }
                insertProject(db, projects.last().copy(id = PROJECT_HIGH_WATER))
                db.execSQL("DELETE FROM projects WHERE id = ?", arrayOf(PROJECT_HIGH_WATER))
                brushes.forEach { brush -> insertBrush(db, brush) }
                insertBrush(db, brushes.last().copy(id = BRUSH_HIGH_WATER))
                db.execSQL("DELETE FROM brushes WHERE id = ?", arrayOf(BRUSH_HIGH_WATER))
                settings.forEach { row ->
                    db.execSQL(
                        "INSERT INTO settings (`key`, value, category, lastModified) VALUES (?, ?, ?, ?)",
                        arrayOf(row.key, row.value, row.category, row.lastModified),
                    )
                }
                change(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            assertEquals(databaseVersion, db.version)
        }
        writeArtwork()
    }

    /** A logical disk snapshot also proves rejected migrations leave schema/identity/version intact. */
    fun databaseSnapshot(): DatabaseSnapshot =
        SQLiteDatabase
            .openDatabase(context.getDatabasePath(ArtFlowDatabase.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READONLY)
            .use { db ->
                DatabaseSnapshot(
                    version = db.version,
                    schema = rows(db, "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name"),
                    tables =
                        listOf("projects", "brushes", "settings", "room_master_table", "sqlite_sequence").associateWith { table ->
                            rows(db, "SELECT * FROM `$table` ORDER BY 1")
                        },
                )
            }

    fun filesSnapshot(): Map<String, Pair<String, Long>> =
        directory.walkTopDown().filter { it.isFile }.associate { file ->
            file.relativeTo(directory).path to (sha256(file.readBytes()) to file.lastModified())
        }

    fun close() {
        context.deleteDatabase(ArtFlowDatabase.DATABASE_NAME)
        StorageFileTree.delete(directory)
    }

    private fun schema(version: Int): JSONObject {
        val bytes = asset("com.artflow.studio.data.local.database.ArtFlowDatabase/$version.json")
        if (version == 1) {
            // Freeze the historical schema instead of silently adapting fixtures if it is edited.
            assertEquals("afbdb33dcf9e2e6f2610560ca1adf4cd876c5241a3f56b62a162361c5c493dde", sha256(bytes))
        }
        return JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("database").also {
            assertEquals(version, it.getInt("version"))
        }
    }

    private fun writeArtwork() {
        storage.documentFile(ARTWORK_ID).writeBytes(asset("migration/database-v1-canvas.artflow"))
        storage.autosaveFile(ARTWORK_ID).writeBytes(asset("migration/database-v1-autosave.artflow"))
        val saved = BitmapPixelBridge.toPngBytes(PixelBuffer(WIDTH, HEIGHT, savedPixels()))
        val recovered = BitmapPixelBridge.toPngBytes(PixelBuffer(WIDTH, HEIGHT, recoveredPixels()))
        listOf("layers/7/saved.png" to saved, "layers/7/recovered.png" to recovered).forEach { (path, bytes) ->
            val target = storage.resolve(ARTWORK_ID, path)
            val parent = requireNotNull(target.parentFile)
            check(parent.isDirectory || parent.mkdirs())
            target.writeBytes(bytes)
        }
        storage.thumbnailFile(ARTWORK_ID).writeBytes(saved)
        storage.flattenedFile(ARTWORK_ID).writeBytes(saved)
        check(storage.documentFile(ARTWORK_ID).setLastModified(1_700_000_000_100L))
        check(storage.autosaveFile(ARTWORK_ID).setLastModified(1_700_000_001_100L))
    }

    private fun insertProject(
        db: SupportSQLiteDatabase,
        row: ProjectEntity,
    ) {
        db.execSQL(
            "INSERT INTO projects (id, name, filePath, thumbnailPath, width, height, dpi, createdAt, modifiedAt, layerCount, isFavorite) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                row.id,
                row.name,
                row.filePath,
                row.thumbnailPath,
                row.width,
                row.height,
                row.dpi,
                row.createdAt,
                row.modifiedAt,
                row.layerCount,
                if (row.isFavorite) 1 else 0,
            ),
        )
    }

    private fun insertBrush(
        db: SupportSQLiteDatabase,
        row: BrushEntity,
    ) {
        db.execSQL(
            "INSERT INTO brushes (id, name, category, shape, texture, size, opacity, spacing, scatter, rotation, count, " +
                "pressureSizeCurve, pressureOpacityCurve, colorDynamics, isDefault, thumbnailPath, createdAt) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                row.id,
                row.name,
                row.category,
                row.shape,
                row.texture,
                row.size,
                row.opacity,
                row.spacing,
                row.scatter,
                row.rotation,
                row.count,
                row.pressureSizeCurve,
                row.pressureOpacityCurve,
                row.colorDynamics,
                if (row.isDefault) 1 else 0,
                row.thumbnailPath,
                row.createdAt,
            ),
        )
    }

    private fun setting(
        key: String,
        value: String,
        category: String = "general",
    ): SettingsEntity = SettingsEntity(key, value, category, 1_600_000_000_005L)

    private fun asset(path: String): ByteArray =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open(path)
            .use { it.readBytes() }

    private fun rows(
        db: SQLiteDatabase,
        sql: String,
    ): List<List<Any?>> =
        db.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        List(cursor.columnCount) { column ->
                            // Text coercion can hide changed storage classes or round a REAL value.
                            when (cursor.getType(column)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                                else -> error("Unknown SQLite value type in column $column")
                            }
                        },
                    )
                }
            }
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    data class DatabaseSnapshot(
        val version: Int,
        val schema: List<List<Any?>>,
        val tables: Map<String, List<List<Any?>>>,
    )

    companion object {
        const val ARTWORK_ID = 41L
        const val DRAFT_ID = 4_294_967_301L
        const val PROJECT_HIGH_WATER = 6_000_000_000L
        const val BRUSH_HIGH_WATER = 8_000_000_000L
        const val WIDTH = 32
        const val HEIGHT = 24

        fun savedPixels(): IntArray = IntArray(WIDTH * HEIGHT) { if (it % 7 == 0) 0xFF1475BA.toInt() else 0xFF243847.toInt() }

        fun recoveredPixels(): IntArray = IntArray(WIDTH * HEIGHT) { if (it % 5 == 0) 0xFF69D284.toInt() else 0xFF1C643F.toInt() }
    }
}
