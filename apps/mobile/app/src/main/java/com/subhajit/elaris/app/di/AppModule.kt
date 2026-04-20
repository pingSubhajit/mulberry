package com.subhajit.elaris.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.subhajit.elaris.core.config.AppConfig
import com.subhajit.elaris.core.config.AppConfigFactory
import com.subhajit.elaris.core.data.APP_PREFERENCES_FILE
import com.subhajit.elaris.core.flags.DataStoreFeatureFlagProvider
import com.subhajit.elaris.core.flags.FeatureFlagProvider
import com.subhajit.elaris.data.bootstrap.DataStoreSessionBootstrapRepository
import com.subhajit.elaris.data.bootstrap.SessionBootstrapRepository
import com.subhajit.elaris.drawing.DrawingRepository
import com.subhajit.elaris.drawing.data.RoomDrawingRepository
import com.subhajit.elaris.drawing.data.local.CanvasMetadataDao
import com.subhajit.elaris.drawing.data.local.DrawingDatabase
import com.subhajit.elaris.drawing.data.local.DrawingDao
import com.subhajit.elaris.drawing.data.local.DrawingOperationsDao
import com.subhajit.elaris.wallpaper.BackgroundImageRepository
import com.subhajit.elaris.wallpaper.CanvasSnapshotRenderer
import com.subhajit.elaris.wallpaper.DataStoreBackgroundImageRepository
import com.subhajit.elaris.wallpaper.DefaultCanvasSnapshotRenderer
import com.subhajit.elaris.wallpaper.DefaultWallpaperCoordinator
import com.subhajit.elaris.wallpaper.WallpaperCoordinator
import com.subhajit.elaris.wallpaper.WallpaperStatusCalculator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {
    @Binds
    @Singleton
    abstract fun bindSessionBootstrapRepository(
        implementation: DataStoreSessionBootstrapRepository
    ): SessionBootstrapRepository

    @Binds
    @Singleton
    abstract fun bindFeatureFlagProvider(
        implementation: DataStoreFeatureFlagProvider
    ): FeatureFlagProvider

    @Binds
    @Singleton
    abstract fun bindDrawingRepository(
        implementation: RoomDrawingRepository
    ): DrawingRepository

    @Binds
    @Singleton
    abstract fun bindBackgroundImageRepository(
        implementation: DataStoreBackgroundImageRepository
    ): BackgroundImageRepository

    @Binds
    @Singleton
    abstract fun bindCanvasSnapshotRenderer(
        implementation: DefaultCanvasSnapshotRenderer
    ): CanvasSnapshotRenderer

    @Binds
    @Singleton
    abstract fun bindWallpaperCoordinator(
        implementation: DefaultWallpaperCoordinator
    ): WallpaperCoordinator
}

@Module
@InstallIn(SingletonComponent::class)
object AppProvidesModule {
    @Provides
    @Singleton
    fun provideAppConfig(): AppConfig = AppConfigFactory.fromBuildConfig()

    @Provides
    @Singleton
    fun providePreferenceDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile(APP_PREFERENCES_FILE) }
    )

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Provides
    @Singleton
    fun provideDrawingDatabase(
        @ApplicationContext context: Context
    ): DrawingDatabase = Room.databaseBuilder(
        context,
        DrawingDatabase::class.java,
        "drawing.db"
    ).fallbackToDestructiveMigration().build()

    @Provides
    fun provideDrawingDao(database: DrawingDatabase): DrawingDao = database.drawingDao()

    @Provides
    fun provideDrawingOperationsDao(database: DrawingDatabase): DrawingOperationsDao =
        database.drawingOperationsDao()

    @Provides
    fun provideCanvasMetadataDao(database: DrawingDatabase): CanvasMetadataDao =
        database.canvasMetadataDao()

    @Provides
    @Singleton
    fun provideWallpaperStatusCalculator(): WallpaperStatusCalculator = WallpaperStatusCalculator()
}
