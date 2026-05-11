package eu.kanade.tachiyomi.extension.ar.mangapro

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object ProChanHttp {
    fun getHeaders(baseUrl: String): Headers {
        return Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
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
