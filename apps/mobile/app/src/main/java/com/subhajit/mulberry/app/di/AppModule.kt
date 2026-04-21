package com.subhajit.mulberry.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.subhajit.mulberry.auth.AuthRepository
import com.subhajit.mulberry.auth.CredentialManagerAuthRepository
import com.subhajit.mulberry.bootstrap.BackendBootstrapRepository
import com.subhajit.mulberry.bootstrap.BootstrapRepository
import com.subhajit.mulberry.core.config.AppConfig
import com.subhajit.mulberry.core.config.AppConfigFactory
import com.subhajit.mulberry.core.data.APP_PREFERENCES_FILE
import com.subhajit.mulberry.core.flags.DataStoreFeatureFlagProvider
import com.subhajit.mulberry.core.flags.FeatureFlagProvider
import com.subhajit.mulberry.data.bootstrap.DataStoreSessionBootstrapRepository
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.data.RoomDrawingRepository
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataDao
import com.subhajit.mulberry.drawing.data.local.DrawingDatabase
import com.subhajit.mulberry.drawing.data.local.DrawingDao
import com.subhajit.mulberry.drawing.data.local.DrawingOperationsDao
import com.subhajit.mulberry.navigation.BootstrapRouteResolver
import com.subhajit.mulberry.network.AuthHeaderInterceptor
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.onboarding.BackendProfileRepository
import com.subhajit.mulberry.onboarding.DataStoreOnboardingDraftRepository
import com.subhajit.mulberry.onboarding.OnboardingDraftRepository
import com.subhajit.mulberry.onboarding.ProfileRepository
import com.subhajit.mulberry.pairing.BackendInviteRepository
import com.subhajit.mulberry.pairing.InviteRepository
import com.subhajit.mulberry.wallpaper.BackgroundImageRepository
import com.subhajit.mulberry.wallpaper.CanvasSnapshotRenderer
import com.subhajit.mulberry.wallpaper.DataStoreBackgroundImageRepository
import com.subhajit.mulberry.wallpaper.DefaultCanvasSnapshotRenderer
import com.subhajit.mulberry.wallpaper.DefaultWallpaperCoordinator
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import com.subhajit.mulberry.wallpaper.WallpaperStatusCalculator
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
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

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

    @Binds
    @Singleton
    abstract fun bindAuthRepository(
        implementation: CredentialManagerAuthRepository
    ): AuthRepository

    @Binds
    @Singleton
    abstract fun bindBootstrapRepository(
        implementation: BackendBootstrapRepository
    ): BootstrapRepository

    @Binds
    @Singleton
    abstract fun bindOnboardingDraftRepository(
        implementation: DataStoreOnboardingDraftRepository
    ): OnboardingDraftRepository

    @Binds
    @Singleton
    abstract fun bindProfileRepository(
        implementation: BackendProfileRepository
    ): ProfileRepository

    @Binds
    @Singleton
    abstract fun bindInviteRepository(
        implementation: BackendInviteRepository
    ): InviteRepository
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

    @Provides
    @Singleton
    fun provideHttpLoggingInterceptor(appConfig: AppConfig): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (appConfig.enableDebugMenu) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authHeaderInterceptor: AuthHeaderInterceptor,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authHeaderInterceptor)
        .addInterceptor(loggingInterceptor)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        appConfig: AppConfig,
        okHttpClient: OkHttpClient
    ): Retrofit = Retrofit.Builder()
        .baseUrl(
            if (appConfig.apiBaseUrl.endsWith("/")) {
                appConfig.apiBaseUrl
            } else {
                "${appConfig.apiBaseUrl}/"
            }
        )
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides
    @Singleton
    fun provideMulberryApiService(retrofit: Retrofit): MulberryApiService =
        retrofit.create(MulberryApiService::class.java)

    @Provides
    @Singleton
    fun provideBootstrapRouteResolver(): BootstrapRouteResolver = BootstrapRouteResolver()
}
