package eu.kanade.tachiyomi.extension.ar.mangapro

import okhttp3.Interceptor
import okhttp3.Response

class ProChanInterceptor(private val baseUrl: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val request = original.newBuilder()
            .removeHeader("Origin")
            .removeHeader("Referer")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "ar,en-US;q=0.7,en;q=0.3")
            .build()
        return chain.proceed(request)
    }
}
