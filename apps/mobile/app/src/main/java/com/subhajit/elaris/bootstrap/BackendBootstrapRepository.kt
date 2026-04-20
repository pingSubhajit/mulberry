package com.subhajit.elaris.bootstrap

import com.subhajit.elaris.data.bootstrap.SessionBootstrapRepository
import com.subhajit.elaris.data.bootstrap.SessionBootstrapState
import com.subhajit.elaris.network.ElarisApiService
import com.subhajit.elaris.network.toDomainBootstrap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class BackendBootstrapRepository @Inject constructor(
    private val apiService: ElarisApiService,
    private val sessionBootstrapRepository: SessionBootstrapRepository
) : BootstrapRepository {
    override val cachedState: Flow<SessionBootstrapState> = sessionBootstrapRepository.state

    override suspend fun refreshBootstrap(): Result<SessionBootstrapState> = runCatching {
        val response = apiService.getBootstrap().toDomainBootstrap()
        sessionBootstrapRepository.cacheBootstrap(response)
        response
    }
}
