package com.subhajit.elaris.bootstrap

import com.subhajit.elaris.data.bootstrap.SessionBootstrapState
import kotlinx.coroutines.flow.Flow

interface BootstrapRepository {
    val cachedState: Flow<SessionBootstrapState>

    suspend fun refreshBootstrap(): Result<SessionBootstrapState>
}
