package com.subhajit.mulberry.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.subhajit.mulberry.core.config.AppConfig
import com.subhajit.mulberry.core.data.PreferenceStorage
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.RegisterFcmTokenRequest
import com.subhajit.mulberry.network.UnregisterFcmTokenRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine

interface FcmTokenRepository {
    val currentToken: Flow<String?>

    suspend fun syncTokenWithBackend(): Result<Unit>

    suspend fun registerToken(token: String): Result<Unit>

    suspend fun unregisterRegisteredToken(): Result<Unit>
}

@Singleton
class FirebaseFcmTokenRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val apiService: MulberryApiService,
    private val appConfig: AppConfig
) : FcmTokenRepository {
    override val currentToken: Flow<String?> = dataStore.data.map { preferences ->
        preferences[PreferenceStorage.fcmToken]
    }

    override suspend fun syncTokenWithBackend(): Result<Unit> = runCatching {
        val token = currentToken.first()
            ?: runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
            ?: return@runCatching
        registerToken(token).getOrThrow()
    }

    override suspend fun registerToken(token: String): Result<Unit> = runCatching {
        val normalized = token.trim()
        if (normalized.isBlank()) return@runCatching
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.fcmToken] = normalized
        }

        val session = sessionBootstrapRepository.getCurrentSession() ?: return@runCatching
        val registeredToken = dataStore.data.first()[PreferenceStorage.fcmRegisteredToken]
        val registeredUserId = dataStore.data.first()[PreferenceStorage.fcmRegisteredUserId]
        if (registeredToken == normalized && registeredUserId == session.userId) {
            return@runCatching
        }

        apiService.registerFcmToken(
            RegisterFcmTokenRequest(
                token = normalized,
                appEnvironment = appConfig.environment.wireValue
            )
        )
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.fcmRegisteredToken] = normalized
            preferences[PreferenceStorage.fcmRegisteredUserId] = session.userId
        }
    }

    override suspend fun unregisterRegisteredToken(): Result<Unit> = runCatching {
        val preferences = dataStore.data.first()
        val token = preferences[PreferenceStorage.fcmRegisteredToken]
            ?: preferences[PreferenceStorage.fcmToken]
            ?: return@runCatching
        runCatching {
            apiService.unregisterFcmToken(UnregisterFcmTokenRequest(token = token))
        }
        dataStore.edit { mutablePreferences ->
            mutablePreferences.remove(PreferenceStorage.fcmRegisteredToken)
            mutablePreferences.remove(PreferenceStorage.fcmRegisteredUserId)
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        continuation.resume(value)
    }
    addOnFailureListener { error ->
        continuation.resumeWithException(error)
    }
    addOnCanceledListener {
        continuation.resumeWithException(CancellationException("Firebase token task was canceled"))
    }
}
