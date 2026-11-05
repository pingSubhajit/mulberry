package com.subhajit.mulberry.network

import com.subhajit.mulberry.core.config.AppConfig
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Signs requests to Mulberry's server with the session's access token. Other hosts, such as the
 * storage behind sticker and photo links, never see it: a signed link needs no token, and the
 * session must not leave Mulberry's server.
 */
@Singleton
class AuthHeaderInterceptor internal constructor(
    private val apiHost: String?,
    private val accessToken: () -> String?
) : Interceptor {
    @Inject
    constructor(appConfig: AppConfig, sessionBootstrapRepository: SessionBootstrapRepository) : this(
        apiHost = appConfig.apiBaseUrl.toHttpUrlOrNull()?.host,
        accessToken = { runBlocking { sessionBootstrapRepository.getCurrentSession() }?.accessToken }
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.url.host != apiHost) return chain.proceed(original)
        val token = accessToken() ?: return chain.proceed(original)
        return chain.proceed(
            original.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        )
    }
}
