package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

class ProComic : HttpSource() {

    override val name = "ProComic"
    override val baseUrl = "https://procomic.pro"
    override val lang = "ar"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    private val mergeCache = mutableMapOf<String, List<String>>()

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(ImageMergeInterceptor())
        .build()

    private inner class ImageMergeInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()

            val pieceUrls = mergeCache[url]
            if (pieceUrls == null) return chain.proceed(request)

            return try {
                val mergedBytes = downloadAndMerge(chain, request, pieceUrls)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mergedBytes.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            } catch (e: Exception) {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Merge failed")
                    .body("".toResponseBody(null))
                    .build()
            }
        }

        private fun downloadAndMerge(
            chain: Interceptor.Chain,
            originalRequest: Request,
            pieceUrls: List<String>,
        ): ByteArray {
            val bitmaps = mutableListOf<Bitmap>()
            try {
                pieceUrls.forEach { pieceUrl ->
                    val cleanUrl = pieceUrl.replace("&amp;", "&")
                    val pieceRequest = Request.Builder()
                        .url(cleanUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Referer", "$baseUrl/")
                        .build()

                    val response = chain.proceed(pieceRequest)
                    if (response.isSuccessful) {
                        val bytes = response.body.bytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                            bitmaps.add(it)
                        }
                    }
                    response.close()
                }

                if (bitmaps.isEmpty()) throw Exception("No bitmaps")

                val maxWidth = bitmaps.maxOf { it.width }
                val totalHeight = bitmaps.sumOf { it.height }
                val merged = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(merged)
                var currentY = 0f

                bitmaps.forEach { bitmap ->
                    canvas.drawBitmap(bitmap, 0f, currentY, null)
                    currentY += bitmap.height
                }

                val output = ByteArrayOutputStream()
                merged.compress(Bitmap.CompressFormat.JPEG, 85, output)
                return output.toByteArray()
            } finally {
                bitmaps.forEach { it.recycle() }
            }
        }
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/public/content/latest-updates".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "30")
            .addQueryParameter("category", "comics")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<LatestUpdatesResponse>()
        val mangas = data.data.filter { it.type != "novel" }.map { it.toSManga() }
        return MangasPage(mangas, mangas.size >= 30)
    }

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/public/content/latest-updates".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "30")
            .addQueryParameter("category", "comics")
            .addQueryParameter("page", page.toString())
            .apply { if (query.isNotBlank()) addQueryParameter("q", query) }
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val parts = manga.url.split("/")
        val type = parts.getOrElse(0) { "manga" }
        val id = parts.getOrElse(1) { "0" }
        return GET("$baseUrl/api/public/$type/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = response.parseAs<SeriesDetailResponse>()
        val urlParts = response.request.url.toString().split("/")
        val type = urlParts.getOrElse(urlParts.indexOf("public") + 1) { "manga" }
        val id = urlParts.getOrElse(urlParts.indexOf("public") + 2) { "0" }

        return SManga.create().apply {
            url = "$type/$id/${data.slug ?: ""}"
            title = data.title ?: ""
            thumbnail_url = data.coverImage
            description = data.synopsis ?: data.description
            status = when (data.status?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val parts = manga.url.split("/")
        val type = parts.getOrElse(0) { "manga" }
        val id = parts.getOrElse(1) { "0" }
        val url = "$baseUrl/api/public/$type/$id/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("limit", "500")
            .addQueryParameter("order", "desc")
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val urlParts = response.request.url.toString().split("/")
        val type = urlParts.getOrElse(urlParts.indexOf("public") + 1) { "manga" }
        val id = urlParts.getOrElse(urlParts.indexOf("public") + 2) { "0" }

        val data = response.parseAs<ChaptersResponse>()
        return data.data.map { chapter ->
            SChapter.create().apply {
                url = "$type/$id/${chapter.id}/${chapter.chapterNumber}"
                name = "الفصل ${chapter.chapterNumber}" + (if (!chapter.title.isNullOrBlank()) " - ${chapter.title}" else "")
                date_upload = runCatching { dateFormat.parse(chapter.publishedAt ?: "")?.time }.getOrNull() ?: 0L
                chapter_number = chapter.chapterNumber.toFloatOrNull() ?: 0f
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        val type = parts[0]; val id = parts[1]; val chId = parts[2]; val chNum = parts[3]
        return GET("$baseUrl/series/$type/$id/slug/$chId/$chNum", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val chapterId = response.request.url.toString().split("/").let { it[it.size - 2] }
        val token = Regex("""eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\.[a-zA-Z0-9_\-]+\.[a-zA-Z0-9_\-]+""").find(html)?.value ?: return emptyList()

        val apiResponse = client.newCall(GET("$baseUrl/chapter-deferred-media/$chapterId?token=$token&split=0", headers)).execute()
        val pagesData = apiResponse.parseAs<ChapterDeferredResponse>()
        if (!pagesData.success || pagesData.data == null) return emptyList()

        val pages = mutableListOf<Page>()
        var index = 0
        val allPieceUrls = pagesData.data.maps.flatMap { it.pieces }.map { it.replace("&amp;", "&") }.toSet()

        pagesData.data.images.forEach { 
            val cleanUrl = it.replace("&amp;", "&")
            if (cleanUrl !in allPieceUrls) pages.add(Page(index++, imageUrl = cleanUrl))
        }

        pagesData.data.maps.forEach { map ->
            val pieces = map.pieces.map { it.replace("&amp;", "&") }
            val ordered = if (map.order.isNotEmpty()) map.order.mapNotNull { pieces.getOrNull(it) } else pieces
            val cacheKey = "https://procomic.pro/merge/$chapterId/$index"
            mergeCache[cacheKey] = ordered
            pages.add(Page(index++, imageUrl = cacheKey))
        }
        return pages
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromStream(body.byteStream())

    @Serializable data class LatestUpdatesResponse(val data: List<SeriesDto> = emptyList())
    @Serializable data class SeriesDto(
        @SerialName("mangaId") val id: Int, 
        @SerialName("mangaSlug") val slug: String = "", 
        @SerialName("mangaTitle") val title: String = "", 
        val coverImage: String? = null, 
        val type: String = "manga"
    ) {
        fun toSManga() = SManga.create().apply {
            url = "$type/$id/$slug"
            title = this@SeriesDto.title
            thumbnail_url = coverImage
        }
    }
    @Serializable data class SeriesDetailResponse(val title: String? = null, val slug: String? = null, val coverImage: String? = null, val synopsis: String? = null, val description: String? = null, val status: String? = null)
    @Serializable data class ChaptersResponse(val data: List<ChapterDto> = emptyList())
    @Serializable data class ChapterDto(val id: Int, @SerialName("chapter_number") val chapterNumber: String, val title: String? = null, @SerialName("published_at") val publishedAt: String? = null)
    @Serializable data class ChapterDeferredResponse(val success: Boolean, val data: ChapterDeferredData? = null)
    @Serializable data class ChapterDeferredData(val images: List<String> = emptyList(), val maps: List<PageMap> = emptyList())
    @Serializable data class PageMap(val pieces: List<String> = emptyList(), val order: List<Int> = emptyList())
}
