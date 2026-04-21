package com.subhajit.mulberry.bootstrap

import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import kotlinx.coroutines.flow.Flow

interface BootstrapRepository {
    val cachedState: Flow<SessionBootstrapState>

    suspend fun refreshBootstrap(): Result<SessionBootstrapState>
}
