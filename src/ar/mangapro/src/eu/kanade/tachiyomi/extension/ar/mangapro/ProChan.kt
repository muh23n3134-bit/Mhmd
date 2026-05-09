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
    private val apiBaseUrl = "https://procomic.pro/api/public"
    override val versionId = 6

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(2) 
        .addInterceptor(::scrambledImageInterceptor)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        .add("Referer", "$baseUrl/")

    private val apiHeaders: Headers by lazy {
        headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/plain, */*")
            .build()
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiBaseUrl/series/search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "latest")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, apiHeaders)
    }

    override fun popularMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiBaseUrl/series/search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "latest_chapter")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, apiHeaders)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        if (response.code == 403) throw Exception("خطأ 403: يرجى فتح الموقع في WebView لتجاوز الحماية")
        val data = response.parseAs<MetaData<BrowseManga>>()
        val mangas = data.data.map { manga ->
            SManga.create().apply {
                url = manga.slug
                title = manga.title
                thumbnail_url = manga.coverImage
            }
        }
        return MangasPage(mangas, data.meta.hasNextPage())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$apiBaseUrl/series/search".toHttpUrl().newBuilder()
                .addQueryParameter("search", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, apiHeaders)
        }
        return popularMangaRequest(page)
    }

    override fun searchMangaParse(response: Response) = latestUpdatesParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiBaseUrl/series/${manga.url}", apiHeaders)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<Series>().series.let { manga ->
        SManga.create().apply {
            title = manga.title
            description = manga.description
            thumbnail_url = manga.metadata.coverImage
            author = manga.metadata.author.joinToString()
            genre = manga.metadata.genres.joinToString()
            initialized = true
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/series/${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())
        val scriptData = document.select("script:containsData(initialChapters)").firstOrNull()?.data()
            ?: return emptyList()
        
        return try {
            val data = scriptData.parseAs<InitialChapters>()
            data.initialChapters.map { chapter ->
                SChapter.create().apply {
                    url = chapter.id.toString()
                    name = "الفصل ${chapter.number}"
                    date_upload = try {
                        apiDateFormat.parse(chapter.createdAt)?.time ?: 0L
                    } catch (e: Exception) { 0L }
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/chapter/${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body.string())
        val scriptData = document.select("script:containsData(\"images\")").firstOrNull()?.data()
            ?: return emptyList()

        return try {
            val imageData = scriptData.parseAs<Images>()
            imageData.images.mapIndexed { i, url -> Page(i, imageUrl = url) }
        } catch (e: Exception) { emptyList() }
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

    companion object {
        internal val apiDateFormat by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
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
