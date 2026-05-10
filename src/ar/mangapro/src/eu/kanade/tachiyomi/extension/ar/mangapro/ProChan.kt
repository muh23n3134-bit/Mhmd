package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.closeQuietly
import okio.Buffer
import rx.Observable
import tachiyomi.decoder.ImageDecoder
import java.io.IOException
import java.lang.UnsupportedOperationException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

class ProChan : HttpSource() {
    override val name = "ProChan"
    override val lang = "ar"
    private val domain = "procomic.pro"
    override val baseUrl = "https://$domain"
    override val supportsLatest = true
    override val versionId = 5

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
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    private val rscHeaders = headersBuilder()
        .set("rsc", "1")
        .build()

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        val filters = getFilterList().apply {
            firstInstance<SortFilter>().state = 2
        }
        return fetchSearchManga(page, "", filters)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        val filters = getFilterList().apply {
            firstInstance<SortFilter>().state = 1
        }
        return fetchSearchManga(page, "", filters)
    }

    private val pageNumber = ConcurrentHashMap<String, Int>()

    private fun searchKey(query: String, filters: FilterList): String {
        val filterPart = filters.filterIsInstance<Filter<*>>()
            .joinToString("|") { it.state.toString() }
        return "$query::$filterPart"
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val key = searchKey(query, filters)
        if (page == 1) pageNumber[key] = 1

        return client.newCall(searchMangaRequest(pageNumber[key]!!, query, filters))
            .asObservableSuccess()
            .map { response ->
                val data = response.parseAs<MetaData<BrowseManga>>()
                val statusFilter = filters.firstInstance<StatusFilter>().selected
                
                val mangas = data.data.filter { manga ->
                    manga.type in SUPPORTED_TYPES && (statusFilter == null || manga.progress == statusFilter)
                }.map { manga ->
                    SManga.create().apply {
                        url = "/series/${manga.type}/${manga.id}/${manga.slug}"
                        title = manga.title
                        thumbnail_url = (manga.coverImageApp?.desktop ?: manga.coverImage)?.let {
                            if (it.startsWith("/")) "https://${manga.cdn ?: "cdn"}.$domain$it" else it
                        }
                    }
                }
                MangasPage(mangas, data.meta.hasNextPage())
            }
            .flatMap {
                if (it.mangas.isEmpty() && it.hasNextPage) {
                    pageNumber[key] = pageNumber[key]!! + 1
                    fetchSearchManga(pageNumber[key]!!, query, filters)
                } else {
                    if (!it.hasNextPage) pageNumber.remove(key)
                    Observable.just(it)
                }
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/public/series/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("status", "approved")
            addQueryParameter("limit", "18")
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("search", query)
            filters.firstInstance<TypeFilter>().selected?.also { addQueryParameter("type", it) }
            addQueryParameter("sort", filters.firstInstance<SortFilter>().selected)
            filters.firstInstance<YearFilter>().selected?.also { addQueryParameter("year", it) }
        }.build()
        return GET(url, headers)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", rscHeaders)

    override fun mangaDetailsParse(response: Response): SManga {
        val nextJsData = response.extractNextJs<Series>() ?: throw IOException("Failed to parse details")
        val manga = nextJsData.series
        return SManga.create().apply {
            url = "/series/${manga.type}/${manga.id}/${manga.slug}"
            title = manga.title
            artist = manga.metadata.artist.joinToString()
            author = manga.metadata.author.joinToString()
            description = manga.description?.trim()
            genre = (listOf(manga.type) + manga.metadata.genres).joinToString()
            status = when (manga.progress?.trim()) {
                "مستمر" -> SManga.ONGOING
                "مكتمل" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = (manga.coverImageApp?.desktop ?: manga.metadata.coverImage)?.let {
                if (it.startsWith("/")) "https://${manga.cdn ?: "cdn"}.$domain$it" else it
            }
            initialized = true
        }
    }

    override fun chapterListRequest(manga: SManga) = GET("$baseUrl${manga.url}", rscHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.extractNextJs<InitialChapters>() ?: return emptyList()
        val chapters = data.initialChapters.toMutableList()
        val type = response.request.url.pathSegments[1]
        val id = response.request.url.pathSegments[2]
        val slug = response.request.url.pathSegments[3]

        if (data.totalChapters > chapters.size) {
            val next = client.newCall(GET("$baseUrl/api/public/$type/$id/chapters?page=2&limit=100&order=desc", headers)).execute()
            if (next.isSuccessful) {
                chapters.addAll(next.parseAs<Data<List<Chapter>>>().data)
            }
        }

        return chapters.filter { it.language == "AR" }.map { chapter ->
            SChapter.create().apply {
                url = "/series/$type/$id/$slug/${chapter.id}/${chapter.number}"
                name = "الفصل ${chapter.number}" + (chapter.title?.let { " - $it" } ?: "")
                chapter_number = chapter.number.toFloatOrNull() ?: -1f
                date_upload = dateFormat.tryParse(chapter.createdAt)
            }
        }.sortedByDescending { it.chapter_number }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}", rscHeaders)

    override fun pageListParse(response: Response): List<Page> {
        val bodyString = response.body.string()
        val imageData = bodyString.extractNextJsRsc<Images>() ?: return emptyList()
        val chapterUrl = response.request.url.toString()
        
        val pages = mutableListOf<Page>()
        imageData.images.forEachIndexed { i, url -> pages.add(Page(i, chapterUrl, url)) }
        imageData.maps.forEach { map ->
            val mapJson = map.toJsonString<ScrambledData>()
            pages.add(Page(pages.size, chapterUrl, "http://$SCRAMBLED_IMAGE_HOST/#$mapJson"))
        }
        return pages
    }

    private fun scrambledImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != SCRAMBLED_IMAGE_HOST) return chain.proceed(request)

        val chapterUrl = request.header("Referer") ?: baseUrl
        val cdn = if (chapterUrl.contains("/manhua/")) "cdn2" else "cdn1"

        val scrambledImage = when (val data = request.url.fragment!!.parseAs<ScrambledData>()) {
            is ScrambledImage -> data
            is ScrambledImageToken -> decodeScrambledImageToken(data)
        }

        val layout = scrambledImage.mode.substringAfter("_")
        val orderedPieces = scrambledImage.order.map { scrambledImage.pieces[it] }

        val pieceBitmaps = runBlocking {
            orderedPieces.map { pieceUrl ->
                async(Dispatchers.IO) {
                    val imgUrl = if (pieceUrl.startsWith("/")) "https://$cdn.$domain$pieceUrl" else pieceUrl
                    val res = client.newCall(request.newBuilder().url(imgUrl).build()).await()
                    val decoder = ImageDecoder.newInstance(res.body.byteStream())
                    val bmp = decoder?.decode()
                    decoder?.recycle()
                    bmp!!
                }
            }.awaitAll()
        }

        val resultBitmap = Bitmap.createBitmap(scrambledImage.dim[0], scrambledImage.dim[1], Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        if (scrambledImage.mode.startsWith("grid")) {
            val cols = layout.split('x')[0].toInt()
            pieceBitmaps.forEachIndexed { i, bmp ->
                canvas.drawBitmap(bmp, (i % cols * bmp.width).toFloat(), (i / cols * bmp.height).toFloat(), null)
            }
        } else {
            var x = 0f
            pieceBitmaps.forEach { canvas.drawBitmap(it, x, 0f, null); x += it.width }
        }

        val buffer = Buffer().apply { resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream()) }
        pieceBitmaps.forEach { it.recycle() }
        resultBitmap.recycle()

        return Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(buffer.asResponseBody("image/jpg".toMediaType(), buffer.size))
            .build()
    }

    private fun decodeScrambledImageToken(data: ScrambledImageToken): ScrambledImage {
        val value = String(urlSafeBase64(data.token), Charsets.UTF_8).parseAs<ScrambledImageTokenValue>()
        val hash = MessageDigest.getInstance("SHA-256").digest("prochan-browser-map:2e6f9a1c4d8b7e3f0a5c9d2b6e1f4a8c7d3b0e6a9f2c5d8b1e4a7c0d3f6b9e2:${value.cid}".toByteArray())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(hash, "AES"), GCMParameterSpec(128, urlSafeBase64(value.iv)))
        }
        return String(cipher.doFinal(urlSafeBase64(value.data) + urlSafeBase64(value.tag)), Charsets.UTF_8).parseAs()
    }

    private fun urlSafeBase64(data: String) = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(data)

    override fun getFilterList() = FilterList(TypeFilter(), SortFilter(), YearFilter(), StatusFilter())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}

@Serializable data class Data<T>(val data: T)
@Serializable data class MetaData<T>(val data: List<T>, val meta: PageMeta)
@Serializable data class PageMeta(val currentPage: Int? = null, val totalPages: Int? = null) { fun hasNextPage() = (currentPage ?: 0) < (totalPages ?: 0) }
@Serializable data class BrowseManga(val id: Int, val title: String, val slug: String, val type: String, val progress: String? = null, val coverImage: String? = null, val coverImageApp: CoverImageApp? = null, val cdn: String? = null)
@Serializable data class CoverImageApp(val desktop: String? = null)
@Serializable data class Series(val series: MangaDetail)
@Serializable data class MangaDetail(val id: Int, val title: String, val slug: String, val type: String, val description: String? = null, val progress: String? = null, val coverImage: String? = null, val coverImageApp: CoverImageApp? = null, val cdn: String? = null, val metadata: MangaMetadata)
@Serializable data class MangaMetadata(val artist: List<String> = emptyList(), val author: List<String> = emptyList(), val genres: List<String> = emptyList(), val coverImage: String? = null)
@Serializable data class InitialChapters(val initialChapters: List<Chapter>, val totalChapters: Int)
@Serializable data class Chapter(val id: Int, val number: String, val title: String? = null, val language: String? = "AR", val createdAt: String)
@Serializable data class Images(val images: List<String> = emptyList(), val maps: List<ScrambledData> = emptyList())
@Serializable @JsonClassDiscriminator("type") sealed interface ScrambledData
@Serializable @SerialName("image") data class ScrambledImage(val dim: List<Int>, val pieces: List<String>, val order: List<Int>, val mode: String) : ScrambledData
@Serializable @SerialName("token") data class ScrambledImageToken(val token: String) : ScrambledData
@Serializable data class ScrambledImageTokenValue(val iv: String, val tag: String, val data: String, val cid: Int)

private val SUPPORTED_TYPES = setOf("manga", "manhwa", "manhua")
private const val SCRAMBLED_IMAGE_HOST = "127.0.0.1"

class TypeFilter : SelectFilter("النوع", arrayOf("الكل", "مانجا", "مانهوا", "مانها"), arrayOf(null, "manga", "manhwa", "manhua"))
class SortFilter : SelectFilter("الترتيب", arrayOf("الأحدث", "المشاهدات"), arrayOf("latest", "views"))
class YearFilter : SelectFilter("السنة", (listOf("الكل") + (2024 downTo 2010).map { it.toString() }).toTypedArray())
class StatusFilter : SelectFilter("الحالة", arrayOf("الكل", "مستمر", "مكتمل"), arrayOf(null, "مستمر", "مكتمل"))
open class SelectFilter(n: String, val v: Array<String>, val k: Array<String?> = emptyArray()) : Filter.Select<String>(n, v) { val selected get() = if (k.isNotEmpty()) k[state] else v[state].takeIf { it != "الكل" } }
