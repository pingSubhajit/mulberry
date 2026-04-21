package com.subhajit.mulberry.settings

import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.toDomainBootstrap
import javax.inject.Inject
import javax.inject.Singleton

interface PairingSettingsRepository {
    suspend fun disconnectPartner(): Result<Unit>
}

@Singleton
class BackendPairingSettingsRepository @Inject constructor(
    private val apiService: MulberryApiService,
    private val sessionBootstrapRepository: SessionBootstrapRepository
) : PairingSettingsRepository {
    override suspend fun disconnectPartner(): Result<Unit> = runCatching {
        val bootstrap = apiService.disconnectPairing().toDomainBootstrap()
        sessionBootstrapRepository.cacheBootstrap(bootstrap)
    }
}
