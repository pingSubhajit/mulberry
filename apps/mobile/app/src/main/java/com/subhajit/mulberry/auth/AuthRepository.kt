package com.subhajit.mulberry.auth

import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val authState: Flow<AuthState>

    suspend fun signInWithGoogle(activity: ComponentActivity): Result<Unit>

    suspend fun refreshSession(): Result<Unit>

    suspend fun logout(): Result<Unit>
}
