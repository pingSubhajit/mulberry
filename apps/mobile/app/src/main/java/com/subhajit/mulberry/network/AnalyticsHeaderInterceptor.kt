package com.subhajit.mulberry.network

import android.content.Context
import com.subhajit.mulberry.analytics.AnalyticsInstallId
import com.subhajit.mulberry.analytics.DeviceFamily
import com.subhajit.mulberry.core.config.AppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Names the device family and the install's anonymous analytics ID on requests to Mulberry's
 * server, on every build type. Other hosts, such as sticker storage, see neither.
 */
@Singleton
class AnalyticsHeaderInterceptor internal constructor(
    private val apiHost: String?,
    private val deviceFamily: () -> DeviceFamily,
    private val anonymousId: () -> String
) : Interceptor {
    @Inject
    constructor(
        appConfig: AppConfig,
        @ApplicationContext context: Context,
        installId: AnalyticsInstallId
    ) : this(
        apiHost = appConfig.apiBaseUrl.toHttpUrlOrNull()?.host,
        // Read per request: a foldable's smallest width changes as it opens.
        deviceFamily = {
            DeviceFamily.fromSmallestWidthDp(context.resources.configuration.smallestScreenWidthDp)
        },
        anonymousId = { runBlocking { installId.get() } }
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.url.host != apiHost) return chain.proceed(original)
        return chain.proceed(
            original.newBuilder()
                .header(DEVICE_FAMILY_HEADER, deviceFamily().wireValue)
                .header(ANONYMOUS_ID_HEADER, anonymousId())
                .build()
        )
    }

    internal companion object {
        const val DEVICE_FAMILY_HEADER = "X-Device-Family"
        const val ANONYMOUS_ID_HEADER = "X-Analytics-Anonymous-Id"
    }
}
