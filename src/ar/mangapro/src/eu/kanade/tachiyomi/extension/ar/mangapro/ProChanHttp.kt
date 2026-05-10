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
            .add("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.179 Mobile Safari/537.36")
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "ar-IQ,ar;q=0.9,en-US;q=0.8,en;q=0.7")
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "same-origin")
            .build()
    }

    fun configureClient(baseClient: OkHttpClient, baseUrl: String): OkHttpClient {
        return baseClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .cookieJar(SimpleCookieJar())
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val url = originalRequest.url.toString()
                
                val newRequest = originalRequest.newBuilder()
                
                // إذا كان الطلب يتجه للـ API، نضيف ترويسة Next.js الخاصة
                if (url.contains("/api/")) {
                    newRequest.addHeader("x-nextjs-data", "1")
                }
                
                chain.proceed(newRequest.build())
            }
            .build()
    }

    class SimpleCookieJar : CookieJar {
        private val storage = mutableMapOf<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            storage[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return storage[url.host] ?: listOf()
        }
    }
}
