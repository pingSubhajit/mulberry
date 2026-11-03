package com.subhajit.mulberry.network

import com.subhajit.mulberry.BuildConfig
import com.subhajit.mulberry.core.config.AppConfig
import com.subhajit.mulberry.update.AppUpdateGate
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Names the platform and build on requests to Mulberry's server, so it can turn away builds it no
 * longer supports, and opens the update screen when it does. Other hosts, such as sticker storage,
 * see neither header. Debug builds don't name their build, so the server never turns them away.
 */
@Singleton
class AppBuildHeaderInterceptor @Inject constructor(
    appConfig: AppConfig,
    private val appUpdateGate: AppUpdateGate
) : Interceptor {
    private val apiHost = appConfig.apiBaseUrl.toHttpUrlOrNull()?.host

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (BuildConfig.DEBUG || original.url.host != apiHost) return chain.proceed(original)

        val response = chain.proceed(
            original.newBuilder()
                .header("X-App-Platform", AppUpdateGate.PLATFORM)
                .header("X-App-Build", AppUpdateGate.BUILD.toString())
                .build()
        )
        if (response.code == HTTP_UPGRADE_REQUIRED) appUpdateGate.markServerRequired()
        return response
    }

    private companion object {
        const val HTTP_UPGRADE_REQUIRED = 426
    }
}
