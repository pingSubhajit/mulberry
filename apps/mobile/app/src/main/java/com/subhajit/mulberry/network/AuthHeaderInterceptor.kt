package com.subhajit.mulberry.network

import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

@Singleton
class AuthHeaderInterceptor @Inject constructor(
    private val sessionBootstrapRepository: SessionBootstrapRepository
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val session = runBlocking { sessionBootstrapRepository.getCurrentSession() }
        val request = if (session?.accessToken != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer ${session.accessToken}")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
