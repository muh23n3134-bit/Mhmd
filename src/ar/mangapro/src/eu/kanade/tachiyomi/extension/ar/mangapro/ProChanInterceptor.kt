package eu.kanade.tachiyomi.extension.ar.mangapro

import okhttp3.Interceptor
import okhttp3.Response

class ProChanInterceptor(private val baseUrl: String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        requestBuilder.header("Accept", "application/json, text/plain, */*")
        requestBuilder.header("Accept-Language", "ar,en-US;q=0.7,en;q=0.3")
        requestBuilder.header("Origin", baseUrl)
        requestBuilder.header("Referer", "$baseUrl/")
        requestBuilder.header("Sec-Fetch-Dest", "empty")
        requestBuilder.header("Sec-Fetch-Mode", "cors")
        requestBuilder.header("Sec-Fetch-Site", "same-origin")

        if (originalRequest.url.toString().contains("rsc=1")) {
            requestBuilder.header("rsc", "1")
        }

        return chain.proceed(requestBuilder.build())
    }
}
