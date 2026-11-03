package com.subhajit.mulberry.update

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import com.google.firebase.remoteconfig.remoteConfigSettings
import com.subhajit.mulberry.BuildConfig
import com.subhajit.mulberry.app.di.ApplicationScope
import com.subhajit.mulberry.review.await
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class AppUpdateStatus { CURRENT, RECOMMENDED, REQUIRED }

/**
 * Decides whether this build must update, should, or is fine, from the build numbers the admin
 * site publishes to Remote Config. The server can also turn the build away (HTTP 426).
 *
 * Remote Config's last values apply at once, so launch never waits; a fetch and Remote Config's
 * real-time updates bring newer ones. Without Firebase, or without a reply, nothing is blocked.
 * Debug builds skip all of it; [forceForDebug] shows each state.
 */
@Singleton
class AppUpdateGate @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    private val _status = MutableStateFlow(AppUpdateStatus.CURRENT)
    val status: StateFlow<AppUpdateStatus> = _status

    @Volatile private var remoteConfig: FirebaseRemoteConfig? = null
    @Volatile private var serverRequired = false
    @Volatile private var debugStatus: AppUpdateStatus? = null
    private var started = false

    /** Starts once, as the app opens. */
    fun start() {
        if (started) return
        started = true
        if (BuildConfig.DEBUG || FirebaseApp.getApps(context).isEmpty()) return
        val config = FirebaseRemoteConfig.getInstance()
        remoteConfig = config
        applicationScope.launch {
            runCatching {
                config.setConfigSettingsAsync(
                    remoteConfigSettings { minimumFetchIntervalInSeconds = MINIMUM_FETCH_INTERVAL_SECONDS }
                ).await()
                config.setDefaultsAsync(mapOf(MINIMUM_KEY to 0L, RECOMMENDED_KEY to 0L)).await()
            }
            evaluate()
            refresh()
        }
        config.addOnConfigUpdateListener(object : ConfigUpdateListener {
            override fun onUpdate(configUpdate: ConfigUpdate) {
                applicationScope.launch {
                    runCatching { config.activate().await() }
                    evaluate()
                }
            }

            override fun onError(error: FirebaseRemoteConfigException) {
                Log.w(TAG, "Remote Config updates stopped code=${error.code}", error)
            }
        })
    }

    /** Fetches newer values, as the app comes to the foreground. */
    fun refresh() {
        val config = remoteConfig ?: return
        applicationScope.launch {
            runCatching { config.fetchAndActivate().await() }
                .onFailure { error -> Log.w(TAG, "Remote Config fetch failed message=${error.message}") }
            evaluate()
        }
    }

    /** The server turned this build away. */
    fun markServerRequired() {
        serverRequired = true
        evaluate()
    }

    fun forceForDebug(status: AppUpdateStatus) {
        if (!BuildConfig.DEBUG) return
        debugStatus = status
        evaluate()
    }

    private fun evaluate() {
        _status.value = if (serverRequired) AppUpdateStatus.REQUIRED else debugStatus ?: remoteStatus()
    }

    private fun remoteStatus(): AppUpdateStatus {
        val config = remoteConfig ?: return AppUpdateStatus.CURRENT
        return when {
            BUILD < config.getLong(MINIMUM_KEY) -> AppUpdateStatus.REQUIRED
            BUILD < config.getLong(RECOMMENDED_KEY) -> AppUpdateStatus.RECOMMENDED
            else -> AppUpdateStatus.CURRENT
        }
    }

    companion object {
        /** The `versionCode` the thresholds compare with, and the server sees. */
        const val BUILD: Long = BuildConfig.VERSION_CODE.toLong()
        const val PLATFORM = "android"
        private const val MINIMUM_KEY = "min_build_android"
        private const val RECOMMENDED_KEY = "recommended_build_android"
        private const val MINIMUM_FETCH_INTERVAL_SECONDS = 15L * 60L
        private const val TAG = "MulberryAppUpdateGate"
    }
}
