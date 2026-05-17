package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    // إعداد محلل JSON ليقبل التغييرات الجديدة من الموقع
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    private companion object {
        const val SCRAMBLED_PATH = "__scrambled__"
        const val ORDER_IS_TARGET_INDEX = false
        const val REVERSE_SPLITS = false
        const val REVERSE_NORMAL_IMAGES = false
        const val REVERSE_MAPS = false
    }

    // الـ Interceptor الخاص بدمج الصور المقطعة
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()

            if (request.url.encodedPath != "/$SCRAMBLED_PATH") {
                return@addInterceptor chain.proceed(request)
            }

            try {
                val encoded = request.url.queryParameter("map")
                    ?: throw Exception("بيانات الصورة المقطعة مفقودة")

                val mapJson = String(
                    Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP),
                    Charsets.UTF_8,
                )

                val map = json.decodeFromString<MergeMap>(mapJson)
                val bitmap = reconstructImage(map, chain, request)

                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                val bytes = output.toByteArray()

                bitmap.recycle() // تفريغ الذاكرة

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bytes.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            } catch (e: Throwable) {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Image merge failed: ${e.message}")
                    .body("".toResponseBody(null))
                    .build()
            }
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json, text/plain, */*")

    // =========================
    // Popular & Latest
    // =========================

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

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================
    // Search
    // =========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/public/content/latest-updates".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "30")
            .addQueryParameter("category", "comics")
            .addQueryParameter("page", page.toString())
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("q", query)
                }
            }
            .build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================
    // Manga Details
    // =========================

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
        } catch (_: Exception) {
            SManga.create()
        }
    }

    // =========================
    // Chapters
    // =========================

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

        return data.data.map { chapter ->
            SChapter.create().apply {
                url = "$seriesType/$seriesId/${chapter.id}/${chapter.chapterNumber}"

                name = "الفصل ${chapter.chapterNumber}" +
                    if (!chapter.title.isNullOrBlank()) {
                        " - ${chapter.title}"
                    } else {
                        ""
                    }

                date_upload = runCatching {
                    dateFormat.parse(chapter.publishedAt ?: "")?.time
                }.getOrNull() ?: 0L

                chapter_number = chapter.chapterNumber.toFloatOrNull() ?: 0f

                scanlator = if (chapter.lockedByCoins == true) {
                    "🔒 مدفوع"
                } else {
                    null
                }
            }
        }
    }

    // =========================
    // Pages (تم التحديث لحل مشكلة No Pages Found)
    // =========================

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        val seriesType = parts.getOrElse(0) { "manga" }
        val seriesId = parts.getOrElse(1) { "0" }
        val chapterId = parts.getOrElse(2) { "0" }
        val chapterNumber = parts.getOrElse(3) { "1" }

        val slug = try {
            client.newCall(
                GET("$baseUrl/api/public/$seriesType/$seriesId", headers),
            ).execute().use { slugResponse ->
                slugResponse.parseAs<SeriesDetailResponse>().slug ?: seriesId
            }
        } catch (_: Exception) {
            seriesId
        }

        val chapterUrl = "$baseUrl/series/$seriesType/$seriesId/$slug/$chapterId/$chapterNumber"

        return GET(
            chapterUrl,
            headers.newBuilder()
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .set("Referer", "$baseUrl/series/$seriesType/$seriesId/$slug")
                .build(),
        )
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body?.string() ?: throw Exception("استجابة الخادم فارغة")

        val urlParts = response.request.url.pathSegments
        val chapterId = urlParts.getOrNull(urlParts.size - 2) ?: throw Exception("لا يمكن العثور على معرّف الفصل من الرابط")

        // استخراج توكن الحماية (JWT)
        val tokenRegex = """(eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\.[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+)""".toRegex()
        val tokenMatch = tokenRegex.find(html) 
            ?: throw Exception("لم يتم العثور على رمز المصادقة (Token). قد يكون الموقع غيّر طريقة الحماية.")
        val token = tokenMatch.value

        val apiHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .set("Referer", response.request.url.toString())
            .build()

        // جلب الجزء الأول (Split 0)
        val firstResult = try {
            client.newCall(
                GET("$baseUrl/chapter-deferred-media/$chapterId?token=$token&split=0", apiHeaders),
            ).execute().use { it.parseAs<ChapterDeferredResponse>() }
        } catch (e: Exception) {
            throw Exception("فشل جلب بيانات الفصل: ${e.message}")
        }

        if (!firstResult.success || firstResult.data == null) {
            throw Exception("لا يمكن الوصول لصفحات هذا الفصل. قد يكون مدفوعاً أو محمياً.")
        }

        val splitIndex = firstResult.data.splitIndex.coerceAtLeast(1)
        val splitDataList = mutableListOf<ChapterDeferredData>()
        
        splitDataList.add(firstResult.data)

        // جلب باقي الأجزاء إن وجدت
        for (split in 1..splitIndex) {
            val result = try {
                client.newCall(
                    GET("$baseUrl/chapter-deferred-media/$chapterId?token=$token&split=$split", apiHeaders),
                ).execute().use { it.parseAs<ChapterDeferredResponse>() }
            } catch (_: Exception) {
                continue
            }

            if (result.success && result.data != null) {
                splitDataList.add(result.data)
            }
        }

        val orderedSplitData = if (REVERSE_SPLITS) splitDataList.asReversed() else splitDataList

        val pages = mutableListOf<Page>()
        val seen = mutableSetOf<String>()
        var pageIndex = 0

        for (splitData in orderedSplitData) {
            // معالجة الصور العادية
            val images = if (REVERSE_NORMAL_IMAGES) splitData.images.asReversed() else splitData.images
            images.forEach { imageUrl ->
                val cleanUrl = imageUrl.cleanUrl()
                if (cleanUrl.isNotBlank() && seen.add(cleanUrl)) {
                    pages.add(Page(pageIndex++, imageUrl = cleanUrl.toAbsoluteUrl()))
                }
            }

            // معالجة الصور المقطعة
            val maps = if (REVERSE_MAPS) splitData.maps.asReversed() else splitData.maps
            maps.forEach { map ->
                if (map.pieces.isEmpty()) return@forEach

                val mapKey = map.pieces.joinToString("|")
                if (!seen.add(mapKey)) return@forEach

                val mergeMap = MergeMap(
                    dim = map.dim,
                    mode = map.mode,
                    pieces = map.pieces.map { it.cleanUrl().toAbsoluteUrl() },
                    order = map.order,
                )

                pages.add(Page(pageIndex++, imageUrl = encodeMergeMap(mergeMap)))
            }
        }

        if (pages.isEmpty()) throw Exception("تم الاتصال بالخادم، لكن لم يتم العثور على أي صفحات.")

        return pages
    }

    override fun imageUrlParse(response: Response): String = ""

    // =========================
    // Image Reconstruction (فك التشفير)
    // =========================

    private fun encodeMergeMap(map: MergeMap): String {
        val encoded = Base64.encodeToString(
            json.encodeToString(map).toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP,
        )

        return baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(SCRAMBLED_PATH)
            .addQueryParameter("map", encoded)
            .build()
            .toString()
    }

    private fun reconstructImage(
        map: MergeMap,
        chain: okhttp3.Interceptor.Chain,
        originalRequest: Request,
    ): Bitmap {
        val width = map.dim.getOrNull(0)?.takeIf { it > 0 } ?: 800
        val height = map.dim.getOrNull(1)?.takeIf { it > 0 } ?: 1200

        val pieceCount = map.pieces.size.coerceAtLeast(1)
        var cols = 1
        var rows = pieceCount

        when {
            map.mode.startsWith("grid_") -> {
                val parts = map.mode.removePrefix("grid_").split("x")
                cols = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 } ?: 1
                rows = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            }

            map.mode.startsWith("vertical_") -> {
                cols = 1
                rows = map.mode.removePrefix("vertical_").toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: pieceCount
            }

            map.mode.startsWith("horizontal_") -> {
                cols = map.mode.removePrefix("horizontal_").toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: pieceCount
                rows = 1
            }
        }

        if (cols * rows < pieceCount) {
            rows = (pieceCount + cols - 1) / cols
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        fun drawPiece(pieceUrl: String, targetIndex: Int) {
            if (targetIndex < 0 || targetIndex >= cols * rows) return

            val pieceRequest = originalRequest.newBuilder()
                .url(pieceUrl.toAbsoluteUrl())
                .header("Referer", "$baseUrl/")
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val pieceBitmap = chain.proceed(pieceRequest).use { pieceResponse ->
                if (!pieceResponse.isSuccessful) {
                    throw Exception("فشل تحميل قطعة الصورة: HTTP ${pieceResponse.code}")
                }

                val bytes = pieceResponse.body?.bytes() ?: throw Exception("محتوى الصورة فارغ")
                decodeBytes(bytes) ?: throw Exception("فشل فك تشفير قطعة الصورة")
            }

            val col = targetIndex % cols
            val row = targetIndex / cols

            val left = col * width / cols
            val top = row * height / rows
            val right = (col + 1) * width / cols
            val bottom = (row + 1) * height / rows

            val dst = Rect(left, top, right, bottom)

            canvas.drawBitmap(pieceBitmap, null, dst, null)
            pieceBitmap.recycle()
        }

        if (map.order.isNotEmpty() && map.order.size == map.pieces.size) {
            if (ORDER_IS_TARGET_INDEX) {
                map.pieces.forEachIndexed { sourceIndex, pieceUrl ->
                    val targetIndex = map.order.getOrNull(sourceIndex) ?: sourceIndex
                    drawPiece(pieceUrl, targetIndex)
                }
            } else {
                map.order.forEachIndexed { targetIndex, sourceIndex ->
                    val pieceUrl = map.pieces.getOrNull(sourceIndex) ?: return@forEachIndexed
                    drawPiece(pieceUrl, targetIndex)
                }
            }
        } else {
            map.pieces.forEachIndexed { index, pieceUrl ->
                drawPiece(pieceUrl, index)
            }
        }

        return result
    }

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        val decodedByTachiyomi = runCatching {
            val decoder = ImageDecoder.newInstance(bytes.inputStream()) ?: return@runCatching null
            try {
                decoder.decode()
            } finally {
                decoder.recycle()
            }
        }.getOrNull()

        return decodedByTachiyomi ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // =========================
    // Helpers
    // =========================

    private fun String.cleanUrl(): String {
        return replace("&amp;", "&").trim()
    }

    private fun String.toAbsoluteUrl(): String {
        return when {
            startsWith("http://") || startsWith("https://") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$baseUrl$this"
            else -> "$baseUrl/$this"
        }
    }

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = body?.string() ?: throw Exception("الاستجابة من الخادم فارغة")
        return json.decodeFromString(responseBody)
    }

    // =========================
    // Data Classes
    // =========================

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
        fun toSManga(): SManga {
            return SManga.create().apply {
                url = "$type/$id/$slug"
                title = this@SeriesDto.title
                thumbnail_url = coverImageApp?.card?.mobile
                    ?: coverImageApp?.desktop
                    ?: coverImage
            }
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
        val splitIndex: Int = 0,
        val images: List<String> = emptyList(),
        val maps: List<PageMap> = emptyList(),
        val source: String? = null,
    )

    @Serializable
    data class PageMap(
        val dim: List<Int> = emptyList(),
        val mode: String = "",
        val pieces: List<String> = emptyList(),
        val order: List<Int> = emptyList(),
        val token: String = "",
    )

    @Serializable
    data class MergeMap(
        val dim: List<Int> = emptyList(),
        val mode: String = "",
        val pieces: List<String> = emptyList(),
        val order: List<Int> = emptyList(),
    )
}
