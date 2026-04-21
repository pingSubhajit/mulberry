package com.subhajit.mulberry.wallpaper

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.net.toUri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataStoreBackgroundImageRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repository: DataStoreBackgroundImageRepository

    @Before
    fun setup() {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.filesDir.resolve("background_test.preferences_pb") }
        )
        repository = DataStoreBackgroundImageRepository(
            context = context,
            dataStore = dataStore
        )
    }

    @After
    fun tearDown() {
        context.filesDir.resolve("background_test.preferences_pb").delete()
        WallpaperFiles.backgroundFile(context).delete()
    }

    @Test
    fun importAndClearBackgroundUpdatesState() = runBlocking {
        val source = context.filesDir.resolve("background_source.txt").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        repository.importBackground(source.toUri())
        val imported = repository.backgroundState.first { it.isConfigured }

        assertTrue(imported.isConfigured)
        assertTrue(imported.assetPath?.let { java.io.File(it).exists() } == true)

        repository.clearBackground()
        val cleared = repository.getCurrentBackgroundState()

        assertFalse(cleared.isConfigured)
        assertFalse(WallpaperFiles.backgroundFile(context).exists())
        source.delete()
    }
}
