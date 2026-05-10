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
    override val versionId = 13

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(1)
        .addInterceptor(::scrambledImageInterceptor)
        .build()

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

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

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        // Selector معدل لصفحة التحديثات لضمان جلب العناصر حتى لو تغير التصميم
        val mangas = document.select("div.grid > div, a[href*=/series/]").mapNotNull { element ->
            val linkElement = if (element.tagName() == "a") element else element.selectFirst("a[href*=/series/]")
            if (linkElement == null) return@mapNotNull null
            
            SManga.create().apply {
                url = linkElement.attr("href").removePrefix(baseUrl)
                title = element.select("h3, h2, .font-bold").firstOrNull()?.text()?.trim() ?: ""
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }.filter { it.title.isNotBlank() }.distinctBy { it.url }

        return MangasPage(mangas, mangas.size >= 15)
    }

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
            author = document.select("div:contains(المؤلف) + div").text().trim()
            genre = document.select("div.flex.wrap a[href*=genres]").joinToString { it.text() }
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
                name = element.text().trim()
                date_upload = System.currentTimeMillis()
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body.string())
        return document.select("img[src*=cdn]").mapIndexed { i, img ->
            Page(i, imageUrl = img.attr("abs:src"))
        }
    }

    private fun scrambledImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != "scrambled") return chain.proceed(request)
        val scrambledImage = request.url.fragment!!.parseAs<ScrambledImage>()
        val resultBitmap = Bitmap.createBitmap(scrambledImage.dim[0], scrambledImage.dim[1], Bitmap.Config.ARGB_8888)
        val stream = Buffer()
        resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream.outputStream())
        return Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(stream.readByteString().toResponseBody("image/png".toMediaType()))
            .build()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        YearFilter(),
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
