package com.subhajit.elaris.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.subhajit.elaris.core.config.AppConfig
import com.subhajit.elaris.core.config.AppConfigFactory
import com.subhajit.elaris.core.data.APP_PREFERENCES_FILE
import com.subhajit.elaris.core.flags.DataStoreFeatureFlagProvider
import com.subhajit.elaris.core.flags.FeatureFlagProvider
import com.subhajit.elaris.data.bootstrap.DataStoreSessionBootstrapRepository
import com.subhajit.elaris.data.bootstrap.SessionBootstrapRepository
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
}
