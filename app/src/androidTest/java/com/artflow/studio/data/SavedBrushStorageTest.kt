package com.artflow.studio.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.local.entity.SettingsEntity
import com.artflow.studio.data.repository.BrushLibraryStore
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.PressureResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedBrushStorageTest {
    @Test
    fun completeBrushesSurviveClosingAndReopeningTheActualRoomDatabase() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "brush-library-${System.nanoTime()}.db"
            var database = Room.databaseBuilder(context, ArtFlowDatabase::class.java, name).build()
            try {
                database.settingsDao().insertSetting(SettingsEntity("theme.mode", "DARK"))
                val store = BrushLibraryStore(database.settingsDao())
                val parameters =
                    BrushParams(
                        size = 91f,
                        wetMix = 0.34f,
                        tiltToRotation = true,
                        velocityToHue = 0.3f,
                        pressureCurve = BrushParams.PressureCurve.CUSTOM,
                        customPressure = PressureResponse(0.1f, 0.4f, 0.8f),
                        textureId = "canvas",
                        blendTexture = true,
                        textureScale = 3f,
                    )
                store.saveCopy("My ink", parameters)
                val saved = store.brushes.first().single()
                val id = saved.id
                database.close()
                database = Room.databaseBuilder(context, ArtFlowDatabase::class.java, name).build()
                val reopened = BrushLibraryStore(database.settingsDao())
                val reloaded = reopened.brushes.first().single()
                assertEquals(parameters, reloaded.parameters)
                reopened.rename(id, "New name")
                val renamed = BrushLibraryStore(database.settingsDao()).brushes.first()
                assertEquals("New name", renamed.single().name)
                reopened.delete(id)
                assertTrue(BrushLibraryStore(database.settingsDao()).brushes.first().isEmpty())
                assertEquals("DARK", database.settingsDao().getSettingByKey("theme.mode")!!.value)
            } finally {
                database.close()
                context.deleteDatabase(name)
            }
        }
}
