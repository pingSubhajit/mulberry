package com.subhajit.mulberry.network

import com.subhajit.mulberry.core.config.AppConfig
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Tells Mulberry's server which canvas content this app can handle, on every request and on the
 * canvas sync socket. The server translates newer content, such as photos, for apps released
 * before it existed, so they keep syncing. Other hosts, such as sticker storage, don't see it.
 */
@Singleton
class CanvasFeaturesHeaderInterceptor internal constructor(
    private val apiHost: String?
) : Interceptor {
    @Inject
    constructor(appConfig: AppConfig) : this(apiHost = appConfig.apiBaseUrl.toHttpUrlOrNull()?.host)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.url.host != apiHost) return chain.proceed(original)
        return chain.proceed(original.newBuilder().header(HEADER, SUPPORTED).build())
    }

    internal companion object {
        const val HEADER = "X-Canvas-Features"
        /** Photos are skipped safely until Android draws them. */
        const val SUPPORTED = "photos"
    }
}
