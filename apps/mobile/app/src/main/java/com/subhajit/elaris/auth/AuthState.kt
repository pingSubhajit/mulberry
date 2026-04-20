package com.subhajit.elaris.auth

import com.subhajit.elaris.data.bootstrap.AppSession

sealed interface AuthState {
    data object SignedOut : AuthState
    data object Refreshing : AuthState
    data class SignedIn(val session: AppSession) : AuthState
}
