package com.subhajit.mulberry.auth

import com.subhajit.mulberry.data.bootstrap.AppSession

sealed interface AuthState {
    data object SignedOut : AuthState
    data object Refreshing : AuthState
    data class SignedIn(val session: AppSession) : AuthState
}
