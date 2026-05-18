package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(MergeImageInterceptor(json))
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // =================== Popular ===================
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
        val mangas = data.data
            .filter { it.type != "novel" }
            .map { it.toSManga() }
        return MangasPage(mangas, mangas.size >= 30)
    }

    // =================== Latest ===================
    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // =================== Search ===================
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

    // =================== Details ===================
    override fun mangaDetailsRequest(manga: SManga): Request {
        val parts = manga.url.split("/")
        val type = parts.getOrElse(0) { "manga" }
        val id = parts.getOrElse(1) { "0" }
        return GET("$baseUrl/api/public/$type/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        return try {
            val data = response.parseAs<SeriesDetailResponse>()
            SManga.create().apply {
                val requestUrl = response.request.url.toString()
                val parts = requestUrl.split("/")
                val typeIdx = parts.indexOf("public") + 1
                val type = parts.getOrElse(typeIdx) { "manga" }
                val id = parts.getOrElse(typeIdx + 1) { "0" }
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

    // =================== Chapters ===================
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
        val requestUrl = response.request.url.toString()
        val urlParts = requestUrl.split("/")
        val publicIdx = urlParts.indexOf("public")
        val seriesType = urlParts.getOrElse(publicIdx + 1) { "manga" }
        val seriesId = urlParts.getOrElse(publicIdx + 2) { "0" }

        val data = response.parseAs<ChaptersResponse>()
        return data.data.map { chapter ->
            SChapter.create().apply {
                url = "$seriesType/$seriesId/${chapter.id}/${chapter.chapterNumber}"
                name = "الفصل ${chapter.chapterNumber}"
                    .plus(if (!chapter.title.isNullOrBlank()) " - ${chapter.title}" else "")
                date_upload = runCatching {
                    dateFormat.parse(chapter.publishedAt ?: "")?.time ?: 0L
                }.getOrDefault(0L)
                chapter_number = chapter.chapterNumber.toFloatOrNull() ?: 0f
                scanlator = if (chapter.lockedByCoins == true) "🔒 مدفوع" else null
            }
        }
    }

    // =================== Pages ===================
    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        val seriesType = parts.getOrElse(0) { "manga" }
        val seriesId = parts.getOrElse(1) { "0" }
        val chapterId = parts.getOrElse(2) { "0" }
        val chapterNum = parts.getOrElse(3) { "1" }

        val slugResponse = client.newCall(
            GET("$baseUrl/api/public/$seriesType/$seriesId", headers),
        ).execute()

        val seriesSlug = try {
            slugResponse.parseAs<SeriesDetailResponse>().slug ?: seriesId
        } catch (e: Exception) {
            seriesId
        }

        val htmlUrl = "$baseUrl/series/$seriesType/$seriesId/$seriesSlug/$chapterId/$chapterNum"
        return GET(
            htmlUrl,
            headers.newBuilder()
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .set("Referer", "$baseUrl/series/$seriesType/$seriesId/$seriesSlug")
                .build(),
        )
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val requestUrl = response.request.url.toString()

        val urlParts = requestUrl.split("/")
        val chapterId = urlParts.getOrElse(urlParts.size - 2) { "0" }

        val jwtRegex = Regex("""eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\.[a-zA-Z0-9_\-]+\.[a-zA-Z0-9_\-]+""")
        val token = jwtRegex.find(html)?.value ?: return emptyList()

        val apiUrl = "$baseUrl/chapter-deferred-media/$chapterId".toHttpUrl().newBuilder()
            .addQueryParameter("token", token)
            .addQueryParameter("split", "0")
            .build()

        val pagesResponse = client.newCall(
            GET(
                apiUrl,
                headers.newBuilder()
                    .set("Accept", "application/json")
                    .set("Referer", requestUrl)
                    .build(),
            ),
        ).execute()

        val pagesData = pagesResponse.parseAs<ChapterDeferredResponse>()
        if (!pagesData.success || pagesData.data == null) return emptyList()

        val pages = mutableListOf<Page>()
        var index = 0

        // الصور الكاملة المباشرة
        pagesData.data.images.forEach { imageUrl ->
            pages.add(Page(index++, imageUrl = imageUrl))
        }

        // كل map = صفحة واحدة مدموجة من عدة قطع
        pagesData.data.maps.forEach { map ->
            val encoded = Base64.encodeToString(
                json.encodeToString(map).toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            // رابط وهمي يلتقطه الـ Interceptor لدمج الصور
            val mergedUrl = "https://merge.local/$encoded"
            pages.add(Page(index++, imageUrl = mergedUrl))
        }

        return pages
    }

    override fun imageUrlParse(response: Response) = ""

    // =================== Helpers ===================
    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromStream(body.byteStream())
    }

    // =================== Models ===================
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
            status = SManga.UNKNOWN
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

/**
 * Interceptor يلتقط روابط merge.local ويقوم بـ:
 * 1. فك تشفير بيانات الـ map
 * 2. تنزيل كل القطع
 * 3. دمجها في صورة واحدة حسب mode و order
 * 4. إرجاع الصورة المدموجة كاستجابة عادية
 */
class MergeImageInterceptor(private val json: Json) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (!url.startsWith("https://merge.local/")) {
            return chain.proceed(request)
        }

        return try {
            val encoded = url.removePrefix("https://merge.local/")
            val mapJson = String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val map = json.decodeFromString(ProComic.PageMap.serializer(), mapJson)

            // ترتيب القطع
            val orderedUrls = if (map.order.isNotEmpty() && map.order.size == map.pieces.size) {
                map.order.mapNotNull { i -> map.pieces.getOrNull(i) }
            } else {
                map.pieces
            }

            // تنزيل كل القطع
            val bitmaps = orderedUrls.map { pieceUrl ->
                val pieceRequest = Request.Builder()
                    .url(pieceUrl)
                    .header("Referer", "https://procomic.pro/")
                    .build()
                val pieceResponse = chain.proceed(pieceRequest)
                val bytes = pieceResponse.body.bytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw Exception("فشل فك تشفير القطعة: $pieceUrl")
            }

            // دمج القطع
            val merged = mergeBitmaps(bitmaps, map.mode)

            // تحويل لـ JPEG
            val output = ByteArrayOutputStream()
            merged.compress(Bitmap.CompressFormat.JPEG, 90, output)
            val mergedBytes = output.toByteArray()

            // تنظيف الذاكرة
            bitmaps.forEach { it.recycle() }
            merged.recycle()

            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(mergedBytes.toResponseBody("image/jpeg".toMediaTypeOrNull()))
                .build()
        } catch (e: Exception) {
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Merge failed: ${e.message}")
                .body("".toByteArray().toResponseBody(null))
                .build()
        }
    }

    private fun mergeBitmaps(pieces: List<Bitmap>, mode: String): Bitmap {
        val (cols, rows) = parseMode(mode, pieces.size)

        // افتراض أن كل القطع بنفس الأبعاد تقريباً
        val pieceW = pieces[0].width
        val pieceH = pieces[0].height

        val totalW = pieceW * cols
        val totalH = pieceH * rows

        val result = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        for (i in pieces.indices) {
            val row = i / cols
            val col = i % cols
            val piece = pieces[i]
            val destRect = Rect(
                col * pieceW,
                row * pieceH,
                col * pieceW + pieceW,
                row * pieceH + pieceH,
            )
            canvas.drawBitmap(piece, null, destRect, null)
        }

        return result
    }

    private fun parseMode(mode: String, piecesCount: Int): Pair<Int, Int> {
        // grid_CxR : C أعمدة × R صفوف
        // vertical_N : عمود واحد × N صفوف
        return when {
            mode.startsWith("vertical_") -> {
                val n = mode.removePrefix("vertical_").toIntOrNull() ?: piecesCount
                1 to n
            }
            mode.startsWith("grid_") -> {
                val parts = mode.removePrefix("grid_").split("x")
                if (parts.size == 2) {
                    val c = parts[0].toIntOrNull() ?: 1
                    val r = parts[1].toIntOrNull() ?: piecesCount
                    c to r
                } else {
                    1 to piecesCount
                }
            }
            mode.startsWith("horizontal_") -> {
                val n = mode.removePrefix("horizontal_").toIntOrNull() ?: piecesCount
                n to 1
            }
            else -> 1 to piecesCount
        }
    }
}
