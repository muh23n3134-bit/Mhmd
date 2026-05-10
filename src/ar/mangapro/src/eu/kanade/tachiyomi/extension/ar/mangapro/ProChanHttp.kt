package eu.kanade.tachiyomi.extension.ar.mangapro

import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object ProChanHttp {

    fun getHeaders(baseUrl: String): Headers {
        return Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,application/json,*/*;q=0.8")
            .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .add("Sec-Ch-Ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"")
            .add("Sec-Ch-Ua-Mobile", "?0")
            .add("Sec-Ch-Ua-Platform", "\"Windows\"")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "none")
            .add("Sec-Fetch-User", "?1")
            .add("Upgrade-Insecure-Requests", "1")
            .build()
    }

    fun configureClient(baseClient: OkHttpClient, baseUrl: String): OkHttpClient {
        return baseClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(ProChanInterceptor(baseUrl))
            .build()
    }
}
