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

    // مخزن مؤقت لقطع الصور المدمجة
    [span_2](start_span)private val mergeCache = mutableMapOf<String, List<String>>()[span_2](end_span)

    // إعداد العميل مع المحاكي والمعترض الخاص بالدمج
    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(BrowserSimulatorInterceptor()) // المحاكي الجديد
        .addInterceptor(ImageMergeInterceptor())
        .build()

    /**
     * محاكي المتصفح: يضمن أن جميع الطلبات تخرج برؤوس (Headers) مطابقة للمتصفح الحقيقي
     */
    private inner class BrowserSimulatorInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val requestBuilder = originalRequest.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .header("Referer", "$baseUrl/")
                .header("Cache-Control", "no-cache")

            return chain.proceed(requestBuilder.build())
        }
    }

    private inner class ImageMergeInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()

            [span_3](start_span)val pieceUrls = mergeCache[url][span_3](end_span)
            if (pieceUrls == null) return chain.proceed(request)

            return try {
                val mergedBytes = downloadAndMerge(chain, request, pieceUrls)
                Response.Builder()
                    [span_4](start_span).request(request)[span_4](end_span)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mergedBytes.toResponseBody("image/jpeg".toMediaType()))
                    [span_5](start_span).build()[span_5](end_span)
            } catch (e: Exception) {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    [span_6](start_span).message("Merge failed: ${e.message}")[span_6](end_span)
                    .body("".toResponseBody(null))
                    .build()
            }
        }

        private fun downloadAndMerge(
            chain: Interceptor.Chain,
            [span_7](start_span)originalRequest: Request,[span_7](end_span)
            pieceUrls: List<String>,
        ): ByteArray {
            val bitmaps = mutableListOf<Bitmap>()
            try {
                pieceUrls.forEach { pieceUrl ->
                    // تنظيف الرابط من &amp; لضمان عدم فشل التوكن
                    val cleanUrl = pieceUrl.replace("&amp;", "&")
                    
                    [span_8](start_span)val pieceRequest = Request.Builder()[span_8](end_span)
                        .url(cleanUrl)
                        .headers(originalRequest.headers)
                        .build()

                    val response = chain.proceed(pieceRequest)
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")

                    [span_9](start_span)val bytes = response.body.bytes()[span_9](end_span)
                    response.close()

                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    [span_10](start_span)bitmaps.add(bitmap ?: throw Exception("فشل فك تشفير الصورة"))[span_10](end_span)
                }

                val maxWidth = bitmaps.maxOf { it.width }
                val totalHeight = bitmaps.sumOf { it.height }
                val merged = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(merged)
                [span_11](start_span)var currentY = 0f[span_11](end_span)

                bitmaps.forEach { bitmap ->
                    canvas.drawBitmap(bitmap, 0f, currentY, null)
                    currentY += bitmap.height
                }

                [span_12](start_span)val output = ByteArrayOutputStream()[span_12](end_span)
                merged.compress(Bitmap.CompressFormat.JPEG, 90, output)
                merged.recycle()
                return output.toByteArray()
            } finally {
                [span_13](start_span)bitmaps.forEach { it.recycle() }[span_13](end_span)
            }
        }
    }

    // --- بقية الدوال الأساسية للمصدر ---

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/public/content/latest-updates".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "30")
            .addQueryParameter("category", "comics")
            .addQueryParameter("page", page.toString())
            [span_14](start_span).build()[span_14](end_span)
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

    [span_15](start_span)override fun searchMangaParse(response: Response) = popularMangaParse(response)[span_15](end_span)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val parts = manga.url.split("/")
        val type = parts.getOrElse(0) { "manga" }
        val id = parts.getOrElse(1) { "0" }
        return GET("$baseUrl/api/public/$type/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        [span_16](start_span)val data = response.parseAs<SeriesDetailResponse>()[span_16](end_span)
        val parts = response.request.url.toString().split("/")
        val publicIdx = parts.indexOf("public")
        val type = parts.getOrElse(publicIdx + 1) { "manga" }
        val id = parts.getOrElse(publicIdx + 2) { "0" }

        return SManga.create().apply {
            [span_17](start_span)url = "$type/$id/${data.slug ?: ""}"[span_17](end_span)
            title = data.title ?: ""
            thumbnail_url = data.coverImage
            [span_18](start_span)description = data.synopsis ?: data.description[span_18](end_span)
            status = when (data.status?.lowercase()) {
                "ongoing", "مستمر" -> SManga.ONGOING
                "completed", "مكتمل" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            [span_19](start_span)}
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val parts = manga.url.split("/")
        val type = parts.getOrElse(0) { "manga" }
        val id = parts.getOrElse(1) { "0" }[span_19](end_span)
        val url = "$baseUrl/api/public/$type/$id/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("limit", "500")
            .addQueryParameter("order", "desc")
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val parts = response.request.url.toString().split("/")
        [span_20](start_span)val publicIdx = parts.indexOf("public")[span_20](end_span)
        val seriesType = parts.getOrElse(publicIdx + 1) { "manga" }
        val seriesId = parts.getOrElse(publicIdx + 2) { "0" }

        val data = response.parseAs<ChaptersResponse>()
        return data.data.map { chapter ->
            SChapter.create().apply {
                url = "$seriesType/$seriesId/${chapter.id}/${chapter.chapterNumber}"
                [span_21](start_span)name = "الفصل ${chapter.chapterNumber}" + (if (!chapter.title.isNullOrBlank()) " - ${chapter.title}" else "")[span_21](end_span)
                date_upload = runCatching { dateFormat.parse(chapter.publishedAt ?: "")?.time }.getOrNull() ?: 0L
                [span_22](start_span)chapter_number = chapter.chapterNumber.toFloatOrNull() ?: 0f[span_22](end_span)
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        [span_23](start_span)val seriesType = parts.getOrElse(0) { "manga" }[span_23](end_span)
        val seriesId = parts.getOrElse(1) { "0" }
        val chapterId = parts.getOrElse(2) { "0" }
        val chapterNumber = parts.getOrElse(3) { "1" }

        val slugResponse = client.newCall(GET("$baseUrl/api/public/$seriesType/$seriesId", headers)).execute()
        [span_24](start_span)val seriesSlug = try { slugResponse.parseAs<SeriesDetailResponse>().slug ?: seriesId } catch (e: Exception) { seriesId }[span_24](end_span)

        val htmlUrl = "$baseUrl/series/$seriesType/$seriesId/$seriesSlug/$chapterId/$chapterNumber"
        return GET(htmlUrl, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val urlParts = response.request.url.toString().split("/")
        val chapterId = urlParts.getOrElse(urlParts.size - 2) { "0" }

        [span_25](start_span)val jwtRegex = Regex("""eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\.[a-zA-Z0-9_\-]+\.[a-zA-Z0-9_\-]+""")[span_25](end_span)
        val token = jwtRegex.find(html)?.value ?: return emptyList()

        val pagesResponse = client.newCall(
            GET("$baseUrl/chapter-deferred-media/$chapterId?token=$token&split=0", headers)
        [span_26](start_span)).execute()[span_26](end_span)

        val pagesData = pagesResponse.parseAs<ChapterDeferredResponse>()
        [span_27](start_span)if (!pagesData.success || pagesData.data == null) return emptyList()[span_27](end_span)

        val pages = mutableListOf<Page>()
        var index = 0

        // تنظيف وحصر روابط القطع
        val allPieceUrls = pagesData.data.maps.flatMap { it.pieces }.map { it.replace("&amp;", "&") }.toSet()

        pagesData.data.images.forEach { imageUrl ->
            val cleanImgUrl = imageUrl.replace("&amp;", "&")
            if (cleanImgUrl !in allPieceUrls) {
                pages.add(Page(index++, imageUrl = cleanImgUrl))
            }
        [span_28](start_span)}

        pagesData.data.maps.forEach { map ->
            val cleanPieces = map.pieces.map { it.replace("&amp;", "&") }
            val ordered = if (map.order.isNotEmpty()) {
                map.order.mapNotNull { i -> cleanPieces.getOrNull(i) }
            } else {
                cleanPieces
            }

            if (ordered.isNotEmpty()) {
                val cacheKey = "https://procomic.pro/merge/$chapterId/$index"[span_28](end_span)
                mergeCache[cacheKey] = ordered
                pages.add(Page(index++, imageUrl = cacheKey))
            [span_29](start_span)}
        }

        return pages
    }

    override fun imageUrlParse(response: Response) = ""

    override fun imageRequest(page: Page): Request {
        return Request.Builder().url(page.imageUrl!!).headers(headers).build()[span_29](end_span)
    }

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromStream(body.byteStream())

    // --- النماذج البرمجية (Data Classes) ---
    @Serializable data class LatestUpdatesResponse(val success: Boolean = false, val data: List<SeriesDto> = emptyList())
    @Serializable data class SeriesDto(
        @SerialName("mangaId") val id: Int = 0,
        @SerialName("mangaSlug") val slug: String = "",
        @SerialName("mangaTitle") val title: String = "",
        val coverImage: String? = null,
        val type: String = "manga",
        val coverImageApp: CoverImageApp? = null
    ) {
        fun toSManga() = SManga.create().apply {
            url = "$type/$id/$slug"
            title = this@SeriesDto.title
            thumbnail_url = coverImageApp?.card?.mobile ?: coverImageApp?.desktop ?: coverImage
        }
    }
    [span_30](start_span)@Serializable data class CoverImageApp(val desktop: String? = null, val card: CardImages? = null)[span_30](end_span)
    @Serializable data class CardImages(val mobile: String? = null, val desktop: String? = null)
    @Serializable data class SeriesDetailResponse(val id: Int = 0, val title: String? = null, val slug: String? = null, val coverImage: String? = null, val synopsis: String? = null, val description: String? = null, val status: String? = null)
    @Serializable data class ChaptersResponse(val data: List<ChapterDto> = emptyList())
    @Serializable data class ChapterDto(val id: Int = 0, @SerialName("chapter_number") val chapterNumber: String = "0", val title: String? = null, @SerialName("published_at") val publishedAt: String? = null)
    [span_31](start_span)@Serializable data class ChapterDeferredResponse(val success: Boolean = false, val data: ChapterDeferredData? = null)[span_31](end_span)
    @Serializable data class ChapterDeferredData(val chapterId: Int = 0, val images: List<String> = emptyList(), val maps: List<PageMap> = emptyList())
    @Serializable data class PageMap(val pieces: List<String> = emptyList(), val order: List<Int> = emptyList())
}
