package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tachiyomi.decoder.ImageDecoder
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
        private const val SCRAMBLED_SCHEME = "https://procomic.pro/__scrambled__/?map="
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            if (url.startsWith(SCRAMBLED_SCHEME)) {
                val encoded = url.removePrefix(SCRAMBLED_SCHEME)
                val mapJson = String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP))
                val pageMap = json.decodeFromString<PageMap>(mapJson)

                val mergedBytes = mergePieces(pageMap)
                    ?: return@addInterceptor Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("فشل دمج قطع الصورة")
                        .body("".toResponseBody(null))
                        .build()

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mergedBytes.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            } else {
                chain.proceed(request)
            }
        }
        .build()

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

        val allPieceUrls = data.data.maps.flatMap { it.pieces }.toSet()

        data.data.images.forEach { url ->
            if (url !in allPieceUrls) {
                pages.add(Page(index++, imageUrl = url))
            }
        }

        data.data.maps.forEach { map ->
            if (map.pieces.isNotEmpty()) {
                val mapJson = json.encodeToString(map)
                val encoded = Base64.encodeToString(
                    mapJson.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP,
                )
                pages.add(Page(index++, imageUrl = "$SCRAMBLED_SCHEME$encoded"))
            }
        }

        return pages
    }

    override fun imageUrlParse(response: Response) = ""

    private fun mergePieces(map: PageMap): ByteArray? {
        val bitmaps = mutableListOf<Bitmap?>()
        return try {
            val (cols, rows) = parseMode(map.mode, map.pieces.size)

            for (pieceUrl in map.pieces) {
                val cleanUrl = pieceUrl.replace("&amp;", "&")
                // نُضيف الـ token كـ query parameter إذا كان موجوداً
                val urlWithToken = if (map.token.isNotEmpty()) {
                    val separator = if (cleanUrl.contains("?")) "&" else "?"
                    "$cleanUrl${separator}cdn_token=${map.token}"
                } else {
                    cleanUrl
                }
                val req = Request.Builder()
                    .url(urlWithToken)
                    .header("Referer", "$baseUrl/")
                    .header("Accept", "image/avif,image/webp,image/*,*/*")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                    .build()
                val resp = client.newCall(req).execute()
                // إذا فشل الطلب مع الـ token، نجرب بدونه
                val bytes = if (resp.code == 403 || resp.code == 401) {
                    resp.close()
                    val fallbackReq = Request.Builder()
                        .url(cleanUrl)
                        .header("Referer", "$baseUrl/")
                        .header("Accept", "image/avif,image/webp,image/*,*/*")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                        .build()
                    val fallbackResp = client.newCall(fallbackReq).execute()
                    val b = fallbackResp.body.bytes()
                    fallbackResp.close()
                    b
                } else {
                    val b = resp.body.bytes()
                    resp.close()
                    b
                }
                bitmaps.add(decodeBytes(bytes))
            }

            val valid = bitmaps.filterNotNull()
            if (valid.isEmpty()) return null

            val pieceW = valid.first().width
            val pieceH = valid.first().height
            val totalW = pieceW * cols
            val totalH = pieceH * rows

            val result = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)

            // order[i] = الموضع الذي تذهب إليه القطعة i في الصورة النهائية
            for (srcIdx in 0 until (cols * rows)) {
                val destPos = if (map.order.isNotEmpty()) {
                    map.order.getOrElse(srcIdx) { srcIdx }
                } else {
                    srcIdx
                }
                val bmp = bitmaps.getOrNull(srcIdx) ?: continue
                val col = destPos % cols
                val row = destPos / cols
                canvas.drawBitmap(bmp, (col * pieceW).toFloat(), (row * pieceH).toFloat(), null)
            }

            val out = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.JPEG, 85, out)
            result.recycle()
            bitmaps.forEach { it?.recycle() }
            bitmaps.clear()

            out.toByteArray()
        } catch (e: Exception) {
            bitmaps.forEach { it?.recycle() }
            bitmaps.clear()
            null
        }
    }

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        val decoder = ImageDecoder.newInstance(bytes.inputStream()) ?: run {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        return try {
            decoder.decode()
        } catch (e: Exception) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            decoder.recycle()
        }
    }

    private fun parseMode(mode: String, pieceCount: Int): Pair<Int, Int> {
        return when {
            mode.startsWith("grid_") -> {
                val parts = mode.removePrefix("grid_").split("x")
                Pair(
                    parts.getOrNull(0)?.toIntOrNull() ?: 1,
                    parts.getOrNull(1)?.toIntOrNull() ?: 1,
                )
            }
            mode.startsWith("vertical_") -> {
                Pair(1, mode.removePrefix("vertical_").toIntOrNull() ?: pieceCount)
            }
            else -> Pair(1, pieceCount)
        }
    }

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
    data class CoverImageApp(val desktop: String? = null, val card: CardImages? = null)

    @Serializable
    data class CardImages(val mobile: String? = null, val desktop: String? = null)

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
        val token: String = "",
    )
}
