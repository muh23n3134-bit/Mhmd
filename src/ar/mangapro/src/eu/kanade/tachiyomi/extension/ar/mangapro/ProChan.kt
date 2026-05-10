package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
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

class ProChan :
    HttpSource(),
    ConfigurableSource {

    override val name = "ProChan"
    override val lang = "ar"
    override val supportsLatest = true
    override val baseUrl by lazy { getPrefBaseUrl() }
    private val preferences by getPreferencesLazy()
    override val versionId = 14

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(1)
        .addInterceptor(::scrambledImageInterceptor)
        .build()

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json, text/plain, */*") // ضروري لطلبات الـ API

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/series?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        val mangas = document.select("a[href*=/series/]").mapNotNull { element ->
            val titleText = element.select("h3, h2, p").firstOrNull { it.text().isNotBlank() }?.text()?.trim()
            if (titleText == null) return@mapNotNull null
            SManga.create().apply {
                url = element.attr("href").removePrefix(baseUrl)
                title = titleText
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }.distinctBy { it.url }

        return MangasPage(mangas, mangas.size >= 15)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updates?page=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("search", query)
            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> addQueryParameter("sort", filter.selected)
                    is TypeFilter -> filter.selected?.let { addQueryParameter("type", it) }
                    is StatusFilter -> filter.selected?.let { addQueryParameter("status", it) }
                    is YearFilter -> filter.selected?.let { addQueryParameter("year", it) }
                    else -> {} 
                }
            }
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())
        return SManga.create().apply {
            title = document.select("h1").text().trim()
            description = document.select("p.text-sm.line-clamp-6, div.description").text().trim()
            thumbnail_url = document.select("img[alt=poster], img.object-cover").firstOrNull()?.attr("abs:src") ?: ""
            author = document.select("div:contains(المؤلف) + div, span:contains(المؤلف) + span").text().trim()
            genre = document.select("div.flex.wrap a[href*=genres], a[href*=category]").joinToString { it.text() }
            status = when {
                document.text().contains("مستمر") -> SManga.ONGOING
                document.text().contains("مكتمل") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())
        
        // جلب الفصول من الروابط المباشرة في الصفحة
        val chapters = document.select("a[href*=/chapter/]").map { element ->
            SChapter.create().apply {
                url = element.attr("href").removePrefix(baseUrl)
                // تنظيف اسم الفصل (مثلاً "الفصل 80" بدلاً من "مانهوا Breakers - الفصل 80")
                name = element.text().replace(document.select("h1").text(), "").trim()
                    .ifEmpty { element.text().trim() }
                date_upload = System.currentTimeMillis()
            }
        }
        
        return chapters.sortedByDescending { it.url.filter { char -> char.isDigit() }.toIntOrNull() ?: 0 }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body.string())
        // جلب الصور مع التأكد من جلب الروابط الحقيقية من الـ CDN
        return document.select("img[src*=cdn], img.chapter-img").mapIndexed { i, img ->
            val url = img.attr("abs:src").ifEmpty { img.attr("abs:data-src") }
            Page(i, imageUrl = url)
        }.filter { it.imageUrl?.isNotBlank() == true }
    }

    private fun scrambledImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != "scrambled") return chain.proceed(request)
        [span_0](start_span)val scrambledImage = request.url.fragment!!.parseAs<ScrambledImage>()[span_0](end_span)
        [span_1](start_span)val resultBitmap = Bitmap.createBitmap(scrambledImage.dim[0], scrambledImage.dim[1], Bitmap.Config.ARGB_8888)[span_1](end_span)
        val stream = Buffer()
        resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream.outputStream())
        return Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(stream.readByteString().toResponseBody("image/png".toMediaType()))
            .build()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList(
        [span_2](start_span)SortFilter(),[span_2](end_span)
        [span_3](start_span)TypeFilter(),[span_3](end_span)
        [span_4](start_span)StatusFilter(),[span_4](end_span)
        [span_5](start_span)YearFilter(),[span_5](end_span)
        [span_6](start_span)GenreFilter(),[span_6](end_span)
    )

    private fun getPrefBaseUrl(): String = preferences.getString("overrideBaseUrl", "https://procomic.pro")!!

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = "overrideBaseUrl"
            title = "Base URL"
            setDefaultValue("https://procomic.pro")
        }
        screen.addPreference(baseUrlPref)
    }
    }
