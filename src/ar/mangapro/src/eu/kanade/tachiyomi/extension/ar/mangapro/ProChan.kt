package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ProChan :
    HttpSource(),
    ConfigurableSource {

    override val name = "ProChan"
    override val lang = "ar"
    override val supportsLatest = true
    override val baseUrl by lazy { getPrefBaseUrl() }
    private val preferences by getPreferencesLazy()
    
    override val versionId = 8

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(1)
        .addInterceptor(::scrambledImageInterceptor)
        .build()

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/series?page=$page&sort=latest", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        
        val mangas = document.select("div.grid div.relative.group").map { element ->
            SManga.create().apply {
                val linkElement = element.selectFirst("a[href*=/series/]")
                url = linkElement?.attr("href")?.removePrefix(baseUrl) ?: ""
                title = element.select("h3, h2").text().trim()
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }.filter { it.url.isNotBlank() }

        val hasNextPage = document.selectFirst("button:contains(التالي), a:contains(التالي)") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/series?page=$page&sort=latest_chapter", headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("search", query)
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())
        return SManga.create().apply {
            title = document.select("h1").text().trim()
            description = document.select("p.text-sm.line-clamp-6, div.description").text().trim()
            genre = document.select("div.flex.wrap a[href*=genres]").joinToString { it.text() }
            author = document.select("div:contains(المؤلف) + div").text().trim()
            thumbnail_url = document.select("img[alt=poster]").attr("abs:src")
            status = when {
                document.text().contains("مستمر") -> SManga.ONGOING
                document.text().contains("مكتمل") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())
        return document.select("a[href*=/chapter/]").map { element ->
            SChapter.create().apply {
                url = element.attr("href").removePrefix(baseUrl)
                name = element.select("span, p").firstOrNull()?.text()?.trim() ?: "فصل غير معروف"
                date_upload = System.currentTimeMillis()
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body.string())
        return document.select("img[src*=cdn], img.chapter-image").mapIndexed { i, img ->
            Page(i, imageUrl = img.attr("abs:src"))
        }
    }

    private fun scrambledImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != "scrambled") return chain.proceed(request)
        return chain.proceed(request)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val DEFAULT_BASE_URL = "https://procomic.pro"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Override BaseUrl"
            setDefaultValue(DEFAULT_BASE_URL)
        }
        screen.addPreference(baseUrlPref)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, DEFAULT_BASE_URL)!!
    }
