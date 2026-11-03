package com.subhajit.mulberry.network

import com.subhajit.mulberry.core.config.AppConfig
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Names the device's IANA time zone, as "Asia/Kolkata", on requests to Mulberry's server, so it
 * can read each moment in the person's local time. The zone is read per request, so a change
 * reaches the server with the next one. Other hosts, such as sticker storage, don't see it.
 */
@Singleton
class TimeZoneHeaderInterceptor internal constructor(
    private val apiHost: String?,
    private val currentTimeZone: () -> String
) : Interceptor {
    @Inject
    constructor(appConfig: AppConfig) : this(
        apiHost = appConfig.apiBaseUrl.toHttpUrlOrNull()?.host,
        currentTimeZone = { TimeZone.getDefault().id }
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.url.host != apiHost) return chain.proceed(original)
        return chain.proceed(
            original.newBuilder()
                .header(HEADER, currentTimeZone())
                .build()
        )
    }

    internal companion object {
        const val HEADER = "X-Time-Zone"
    }
}
