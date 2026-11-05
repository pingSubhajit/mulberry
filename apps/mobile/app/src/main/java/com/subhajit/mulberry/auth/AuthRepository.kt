package com.subhajit.mulberry.auth

import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val authState: Flow<AuthState>

    suspend fun tryAutomaticGoogleSignIn(activity: ComponentActivity): Result<Boolean>

    suspend fun signInWithGoogle(activity: ComponentActivity): Result<Unit>

    suspend fun signInWithApple(activity: ComponentActivity): Result<Unit>

    /** Lets the signed-in account sign in with [method] too. */
    suspend fun linkSignInMethod(activity: ComponentActivity, method: SignInMethod): Result<Unit>

    /** Deletes the account on the server at once, then signs this device out. */
    suspend fun deleteAccount(): Result<Unit>

    suspend fun refreshSession(): Result<Unit>

    suspend fun logout(): Result<Unit>
}
