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
import okhttp3.OkHttpClient
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

    companion object {
        private val mergeCache = mutableMapOf<String, PageMapData>()
        private const val MERGE_PREFIX = "https://procomic.pro/img/"
    }

    // نموذج لتخزين بيانات الصفحة المقطعة
    data class PageMapData(
        val pieces: List<String>,
        val order: List<Int>,
        val mode: String,
        val width: Int,
        val height: Int,
    )

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addNetworkInterceptor(MergeInterceptor())
        .build()

    private inner class MergeInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()

            if (!url.startsWith(MERGE_PREFIX)) {
                return chain.proceed(request)
            }

            val mapData = mergeCache[url]
                ?: return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Cache miss")
                    .body("".toResponseBody(null))
                    .build()

            return try {
                val merged = assemblePage(chain, request, mapData)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(merged.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            } catch (e: Exception) {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Error: ${e.message}")
                    .body("".toResponseBody(null))
                    .build()
            }
        }

        private fun assemblePage(
            chain: Interceptor.Chain,
            original: Request,
            mapData: PageMapData,
        ): ByteArray {
            // تحديد الشبكة من الـ mode
            // grid_3x2 = 3 أعمدة × 2 صفوف = 6 قطع
            // grid_2x1 = 2 أعمدة × 1 صف
            // vertical_2 = عمود واحد × 2 صفوف
            val (cols, rows) = parseMode(mapData.mode, mapData.pieces.size)

            // إعادة الترتيب - order[i] = فهرس القطعة التي تذهب للموضع i
            val orderedPieces = if (mapData.order.isNotEmpty()) {
                List(mapData.pieces.size) { i ->
                    val srcIdx = mapData.order.indexOf(i)
                    if (srcIdx >= 0) mapData.pieces.getOrNull(srcIdx) else null
                }.filterNotNull()
            } else {
                mapData.pieces
            }

            // تحميل كل القطع
            val bitmaps = mutableListOf<Bitmap?>()
            for (pieceUrl in mapData.pieces) {
                val cleanUrl = pieceUrl.replace("&amp;", "&")
                val req = Request.Builder()
                    .url(cleanUrl)
                    .header("Referer", "$baseUrl/")
                    .header("Accept", "image/avif,image/webp,image/*,*/*")
                    .header("User-Agent", original.header("User-Agent") ?: "Mozilla/5.0")
                    .build()
                val resp = chain.proceed(req)
                val bytes = resp.body.bytes()
                resp.close()
                bitmaps.add(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }

            try {
                val validBitmaps = bitmaps.filterNotNull()
                if (validBitmaps.isEmpty()) throw Exception("No bitmaps loaded")

                val pieceW = validBitmaps.first().width
                val pieceH = validBitmaps.first().height
                val totalW = pieceW * cols
                val totalH = pieceH * rows

                val result = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)

                // نرسم كل قطعة في موضعها الصحيح حسب الـ order
                for (pos in 0 until (cols * rows)) {
                    val srcIdx = if (mapData.order.isNotEmpty()) {
                        mapData.order.getOrElse(pos) { pos }
                    } else {
                        pos
                    }
                    val bmp = bitmaps.getOrNull(srcIdx) ?: continue

                    val col = pos % cols
                    val row = pos / cols
                    canvas.drawBitmap(bmp, (col * pieceW).toFloat(), (row * pieceH).toFloat(), null)
                }

                val out = ByteArrayOutputStream()
                result.compress(Bitmap.CompressFormat.JPEG, 85, out)
                result.recycle()
                return out.toByteArray()
            } finally {
                bitmaps.forEach { it?.recycle() }
            }
        }

        // استخراج عدد الأعمدة والصفوف من الـ mode
        private fun parseMode(mode: String, pieceCount: Int): Pair<Int, Int> {
            return when {
                mode.startsWith("grid_") -> {
                    // grid_3x2 → cols=3, rows=2
                    val parts = mode.removePrefix("grid_").split("x")
                    val cols = parts.getOrNull(0)?.toIntOrNull() ?: 1
                    val rows = parts.getOrNull(1)?.toIntOrNull() ?: 1
                    Pair(cols, rows)
                }
                mode.startsWith("vertical_") -> {
                    // vertical_5 → cols=1, rows=5
                    val rows = mode.removePrefix("vertical_").toIntOrNull() ?: pieceCount
                    Pair(1, rows)
                }
                else -> {
                    // افتراضي: عمود واحد
                    Pair(1, pieceCount)
                }
            }
        }
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

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
        return try {
            val data = response.parseAs<SeriesDetailResponse>()
            val parts = response.request.url.toString().split("/")
            val idx = parts.indexOf("public")
            val type = parts.getOrElse(idx + 1) { "manga" }
            val id = parts.getOrElse(idx + 2) { "0" }
            SManga.create().apply {
                url = "$type/$id/${data.slug ?: ""}"
                title = data.title ?: ""
                thumbnail_url = data.coverImage
                author = data.author
                artist = data.artist
                description = data.synopsis ?: data.description
                status = when (data.status?.lowercase()) {
                    "ongoing", "مستمر" -> SManga.ONGOING
                    "completed", "مكتمل" -> SManga.COMPLETED
                    "hiatus" -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
            }
        } catch (e: Exception) {
            SManga.create()
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
        val parts = response.request.url.toString().split("/")
        val idx = parts.indexOf("public")
        val seriesType = parts.getOrElse(idx + 1) { "manga" }
        val seriesId = parts.getOrElse(idx + 2) { "0" }
        val data = response.parseAs<ChaptersResponse>()
        return data.data.map { ch ->
            SChapter.create().apply {
                url = "$seriesType/$seriesId/${ch.id}/${ch.chapterNumber}"
                name = "الفصل ${ch.chapterNumber}" +
                    (if (!ch.title.isNullOrBlank()) " - ${ch.title}" else "")
                date_upload = runCatching {
                    dateFormat.parse(ch.publishedAt ?: "")?.time
                }.getOrNull() ?: 0L
                chapter_number = ch.chapterNumber.toFloatOrNull() ?: 0f
                scanlator = if (ch.lockedByCoins == true) "🔒 مدفوع" else null
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        val seriesType = parts.getOrElse(0) { "manga" }
        val seriesId = parts.getOrElse(1) { "0" }
        val chapterId = parts.getOrElse(2) { "0" }
        val chapterNumber = parts.getOrElse(3) { "1" }

        val slugResp = client.newCall(
            GET("$baseUrl/api/public/$seriesType/$seriesId", headers),
        ).execute()
        val slug = try {
            slugResp.parseAs<SeriesDetailResponse>().slug ?: seriesId
        } catch (e: Exception) {
            seriesId
        }

        return GET(
            "$baseUrl/series/$seriesType/$seriesId/$slug/$chapterId/$chapterNumber",
            headers.newBuilder()
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .set("Referer", "$baseUrl/series/$seriesType/$seriesId/$slug")
                .build(),
        )
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val urlParts = response.request.url.toString().split("/")
        val chapterId = urlParts.getOrElse(urlParts.size - 2) { "0" }

        val token = Regex(
            """eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\.[a-zA-Z0-9_\-]+\.[a-zA-Z0-9_\-]+""",
        ).find(html)?.value ?: return emptyList()

        val apiResp = client.newCall(
            GET(
                "$baseUrl/chapter-deferred-media/$chapterId?token=$token&split=0",
                headers.newBuilder().set("Accept", "application/json").build(),
            ),
        ).execute()

        val data = apiResp.parseAs<ChapterDeferredResponse>()
        if (!data.success || data.data == null) return emptyList()

        val pages = mutableListOf<Page>()
        var index = 0

        val allPieces = data.data.maps.flatMap { it.pieces }.toSet()

        // الصور الكاملة
        data.data.images.forEach { url ->
            if (url !in allPieces) {
                pages.add(Page(index++, imageUrl = url))
            }
        }

        // الصور المقطعة مع بيانات الشبكة
        data.data.maps.forEach { map ->
            if (map.pieces.isNotEmpty()) {
                val key = "$MERGE_PREFIX$chapterId/$index"
                mergeCache[key] = PageMapData(
                    pieces = map.pieces,
                    order = map.order,
                    mode = map.mode,
                    width = map.dim.getOrElse(0) { 800 },
                    height = map.dim.getOrElse(1) { 5000 },
                )
                pages.add(Page(index++, imageUrl = key))
            }
        }

        return pages
    }

    override fun imageUrlParse(response: Response) = ""

    private inline fun <reified T> Response.parseAs(): T =
        json.decodeFromStream(body.byteStream())

    @Serializable
    data class LatestUpdatesResponse(
        val success: Boolean = false,
        val data: List<SeriesDto> = emptyList(),
    )

    @Serializable
    data class SeriesDto(
        @SerialName("mangaId") val id: Int = 0,
        @SerialName("mangaSlug") val slug: String = "",
        @SerialName("mangaTitle") val title: String = "",
        val coverImage: String? = null,
        val type: String = "manga",
        val coverImageApp: CoverImageApp? = null,
    ) {
        fun toSManga() = SManga.create().apply {
            url = "$type/$id/$slug"
            title = this@SeriesDto.title
            thumbnail_url = coverImageApp?.card?.mobile
                ?: coverImageApp?.desktop
                ?: coverImage
        }
    }

    @Serializable
    data class CoverImageApp(
        val desktop: String? = null,
        val card: CardImages? = null,
    )

    @Serializable
    data class CardImages(
        val mobile: String? = null,
        val desktop: String? = null,
    )

    @Serializable
    data class SeriesDetailResponse(
        val id: Int = 0,
        val title: String? = null,
        val slug: String? = null,
        val coverImage: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val description: String? = null,
        val synopsis: String? = null,
        val status: String? = null,
    )

    @Serializable
    data class ChaptersResponse(
        val data: List<ChapterDto> = emptyList(),
        val total: Int = 0,
    )

    @Serializable
    data class ChapterDto(
        val id: Int = 0,
        @SerialName("chapter_number") val chapterNumber: String = "0",
        val title: String? = null,
        @SerialName("published_at") val publishedAt: String? = null,
        val lockedByCoins: Boolean? = null,
    )

    @Serializable
    data class ChapterDeferredResponse(
        val success: Boolean = false,
        val data: ChapterDeferredData? = null,
    )

    @Serializable
    data class ChapterDeferredData(
        val chapterId: Int = 0,
        val images: List<String> = emptyList(),
        val maps: List<PageMap> = emptyList(),
    )

    @Serializable
    data class PageMap(
        val dim: List<Int> = emptyList(),
        val mode: String = "",
        val pieces: List<String> = emptyList(),
        val order: List<Int> = emptyList(),
    )
}
