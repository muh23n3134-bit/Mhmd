package eu.kanade.tachiyomi.extension.ar.mangapro

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object ProChanHttp {
    fun getHeaders(baseUrl: String): Headers {
        return Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "ar,en-US;q=0.7,en;q=0.3")
            .build()
    }

    fun configureClient(baseClient: OkHttpClient, baseUrl: String): OkHttpClient {
        return baseClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cookieJar(SimpleCookieJar())
            .build()
    }

    class SimpleCookieJar : CookieJar {
        private val storage = mutableMapOf<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { storage[url.host] = cookies }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = storage[url.host] ?: listOf()
    }
}
