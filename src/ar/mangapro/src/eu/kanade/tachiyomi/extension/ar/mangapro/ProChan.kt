package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.Canvas
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.closeQuietly
import okio.Buffer
import rx.Observable
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class ProChan : HttpSource() {
    override val name = "ProChan"
    override val lang = "ar"
    private val domain = "procomic.pro"
    override val baseUrl = "https://$domain"
    override val supportsLatest = true
    override val versionId = 6

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(::scrambledImageInterceptor)
        .addNetworkInterceptor(
            CookieInterceptor(
                domain,
                listOf(
                    "safe_browsing" to "off",
                    "language" to "ar",
                ),
            ),
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .set("Accept-Language", "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
        .set("sec-ch-ua-mobile", "?1")
        .set("sec-ch-ua-platform", "\"Android\"")
        .set("sec-ch-ua-model", "\"Infinix X688B\"")

    private val rscHeaders = headersBuilder()
        .set("rsc", "1")
        .set("Accept", "text/x-component")
        .build()

    override fun popularMangaRequest(page: Int): Request = searchMangaRequest(page, "", getFilterList().apply { firstInstance<SortFilter>().state = 2 })
    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)
    override fun latestUpdatesRequest(page: Int): Request = searchMangaRequest(page, "", getFilterList().apply { firstInstance<SortFilter>().state = 1 })
    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return client.newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .map { response -> popularMangaParse(response) }
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return client.newCall(latestUpdatesRequest(page))
            .asObservableSuccess()
            .map { response -> latestUpdatesParse(response) }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/public/series/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("status", "approved")
            addQueryParameter("limit", "18")
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("search", query)
            val sort = filters.firstInstance<SortFilter>().selected
            addQueryParameter("sort", sort)
        }.build()
        
        return Request.Builder()
            .url(url)
            .headers(headersBuilder().set("X-Requested-With", "XMLHttpRequest").build())
            .build()
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val data = response.parseAs<MetaData<BrowseManga>>()
        val mangas = data.data.filter { it.type in SUPPORTED_TYPES }.map { manga ->
            SManga.create().apply {
                url = "/series/${manga.type}/${manga.id}/${manga.slug}"
                title = manga.title
                thumbnail_url = (manga.coverImageApp?.desktop ?: manga.coverImage)?.let {
                    if (it.startsWith("/")) {
                        val cdnHost = manga.cdn ?: "cdn"
                        "https://$cdnHost.$domain$it"
                    } else it
                }
            }
        }
        return MangasPage(mangas, data.meta.hasNextPage())
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", rscHeaders)

    override fun mangaDetailsParse(response: Response): SManga {
        val seriesData = response.extractNextJs<Series>() ?: throw IOException("خطأ في جلب بيانات المانجا - تحقق من WebView")
        val manga = seriesData.series
        return SManga.create().apply {
            url = "/series/${manga.type}/${manga.id}/${manga.slug}"
            title = manga.title
            artist = manga.metadata.artist.joinToString()
            author = manga.metadata.author.joinToString()
            description = manga.description
            genre = manga.metadata.genres.joinToString()
            status = when (manga.progress?.trim()) {
                "مستمر" -> SManga.ONGOING
                "مكتمل" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = (manga.coverImageApp?.desktop ?: manga.metadata.coverImage)?.let {
                if (it.startsWith("/")) {
                    val cdnHost = manga.cdn ?: "cdn"
                    "https://$cdnHost.$domain$it"
                } else it
            }
            initialized = true
        }
    }

    override fun chapterListRequest(manga: SManga) = GET("$baseUrl${manga.url}", rscHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.extractNextJs<InitialChapters>() ?: return emptyList()
        val type = response.request.url.pathSegments[1]
        val id = response.request.url.pathSegments[2]
        val slug = response.request.url.pathSegments[3]

        return data.initialChapters.map { chapter ->
            SChapter.create().apply {
                url = "/series/$type/$id/$slug/${chapter.id}/${chapter.number}"
                name = "الفصل ${chapter.number}"
                date_upload = dateFormat.tryParse(chapter.createdAt)
            }
        }.sortedByDescending { it.chapter_number }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}", rscHeaders)

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()
        val imageData = body.extractNextJsRsc<Images>() ?: return emptyList()
        val chapterUrl = response.request.url.toString()
        return imageData.images.mapIndexed { i, url -> Page(i, chapterUrl, url) }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .set("Referer", page.url)
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    private fun scrambledImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != "scrambled") return chain.proceed(request)
        val scrambledImage = request.url.fragment!!.parseAs<ScrambledImage>()
        val resultBitmap = Bitmap.createBitmap(scrambledImage.dim[0], scrambledImage.dim[1], Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        val stream = Buffer()
        resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream.outputStream())
        return Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(stream.readByteString().toResponseBody("image/png".toMediaType()))
            .build()
    }

    override fun getFilterList() = FilterList(SortFilter(), TypeFilter(), YearFilter(), StatusFilter(), GenreFilter(), TagFilter())

    companion object {
        private val SUPPORTED_TYPES = listOf("manga", "manhwa", "manhua")
    }
}
