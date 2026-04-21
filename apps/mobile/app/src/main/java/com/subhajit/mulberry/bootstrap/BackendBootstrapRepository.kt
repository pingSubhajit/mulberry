package com.subhajit.mulberry.bootstrap

import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.toDomainBootstrap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class BackendBootstrapRepository @Inject constructor(
    private val apiService: MulberryApiService,
    private val sessionBootstrapRepository: SessionBootstrapRepository
) : BootstrapRepository {
    override val cachedState: Flow<SessionBootstrapState> = sessionBootstrapRepository.state

    override suspend fun refreshBootstrap(): Result<SessionBootstrapState> = runCatching {
        val response = apiService.getBootstrap().toDomainBootstrap()
        sessionBootstrapRepository.cacheBootstrap(response)
        response
    }
}
