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

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(ImageMergeInterceptor())
        .build()

    private inner class ImageMergeInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()

            if (!url.startsWith("https://merge.local/")) return chain.proceed(request)

            return try {
                val encoded = url.removePrefix("https://merge.local/")
                val pieceUrls = encoded.split("|||")
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
                    .message("Merge failed: ${e.message}")
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
                    val pieceRequest = Request.Builder()
                        .url(pieceUrl)
                        .header("Referer", "$baseUrl/")
                        .header("Accept", "image/avif,image/webp,image/*,*/*")
                        .header(
                            "User-Agent",
                            originalRequest.header("User-Agent")
                                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        )
                        .build()

                    val response = chain.proceed(pieceRequest)
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")

                    val bytes = response.body.bytes()
                    response.close()

                    var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                    if (bitmap == null && pieceUrl.contains(".avif")) {
                        val baseUrlPart = pieceUrl.substringBefore("?")
                        val queryPart = if (pieceUrl.contains("?")) {
                            "?" + pieceUrl.substringAfter("?")
                        } else {
                            ""
                        }
                        val webpUrl = baseUrlPart.replace(".avif", ".webp") + queryPart

                        val webpRequest = Request.Builder()
                            .url(webpUrl)
                            .header("Referer", "$baseUrl/")
                            .header("Accept", "image/webp,image/*,*/*")
                            .build()
                        val webpResponse = chain.proceed(webpRequest)
                        if (webpResponse.isSuccessful) {
                            val webpBytes = webpResponse.body.bytes()
                            webpResponse.close()
                            bitmap = BitmapFactory.decodeByteArray(webpBytes, 0, webpBytes.size)
                        } else {
                            webpResponse.close()
                        }
                    }

                    bitmaps.add(bitmap ?: throw Exception("فشل فك تشفير الصورة"))
                }

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
                merged.recycle()
                return output.toByteArray()
            } finally {
                bitmaps.forEach { it.recycle() }
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
            val publicIdx = parts.indexOf("public")
            val type = parts.getOrElse(publicIdx + 1) { "manga" }
            val id = parts.getOrElse(publicIdx + 2) { "0" }

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
        val publicIdx = parts.indexOf("public")
        val seriesType = parts.getOrElse(publicIdx + 1) { "manga" }
        val seriesId = parts.getOrElse(publicIdx + 2) { "0" }

        val data = response.parseAs<ChaptersResponse>()
        return data.data.map { chapter ->
            SChapter.create().apply {
                url = "$seriesType/$seriesId/${chapter.id}/${chapter.chapterNumber}"
                name = "الفصل ${chapter.chapterNumber}" +
                    (if (!chapter.title.isNullOrBlank()) " - ${chapter.title}" else "")
                date_upload = runCatching {
                    dateFormat.parse(chapter.publishedAt ?: "")?.time
                }.getOrNull() ?: 0L
                chapter_number = chapter.chapterNumber.toFloatOrNull() ?: 0f
                scanlator = if (chapter.lockedByCoins == true) "🔒 مدفوع" else null
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        val seriesType = parts.getOrElse(0) { "manga" }
        val seriesId = parts.getOrElse(1) { "0" }
        val chapterId = parts.getOrElse(2) { "0" }
        val chapterNumber = parts.getOrElse(3) { "1" }

        val slugResponse = client.newCall(
            GET("$baseUrl/api/public/$seriesType/$seriesId", headers),
        ).execute()
        val seriesSlug = try {
            slugResponse.parseAs<SeriesDetailResponse>().slug ?: seriesId
        } catch (e: Exception) {
            seriesId
        }

        val htmlUrl = "$baseUrl/series/$seriesType/$seriesId/$seriesSlug/$chapterId/$chapterNumber"
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
        val urlParts = response.request.url.toString().split("/")
        val chapterId = urlParts.getOrElse(urlParts.size - 2) { "0" }

        val jwtRegex = Regex(
            """eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\.[a-zA-Z0-9_\-]+\.[a-zA-Z0-9_\-]+""",
        )
        val token = jwtRegex.find(html)?.value ?: return emptyList()

        val pagesResponse = client.newCall(
            GET(
                "$baseUrl/chapter-deferred-media/$chapterId?token=$token&split=0",
                headers.newBuilder().set("Accept", "application/json").build(),
            ),
        ).execute()

        val pagesData = pagesResponse.parseAs<ChapterDeferredResponse>()
        if (!pagesData.success || pagesData.data == null) return emptyList()

        val pages = mutableListOf<Page>()
        var index = 0

        val allPieceUrls = pagesData.data.maps.flatMap { it.pieces }.toSet()

        // الصور الكاملة
        pagesData.data.images.forEach { imageUrl ->
            if (imageUrl !in allPieceUrls) {
                pages.add(Page(index++, imageUrl = imageUrl))
            }
        }

        // الصور المقطعة - نضع المعلومات في url وليس imageUrl
        // حتى يمر عبر imageUrlRequest ثم الـ Interceptor
        pagesData.data.maps.forEach { map ->
            val ordered = if (map.order.isNotEmpty()) {
                map.order.mapNotNull { i -> map.pieces.getOrNull(i) }
            } else {
                map.pieces
            }
            if (ordered.isNotEmpty()) {
                val mergeUrl = "https://merge.local/" + ordered.joinToString("|||")
                pages.add(Page(index++, url = mergeUrl, imageUrl = mergeUrl))
            }
        }

        return pages
    }

    // هذا هو المفتاح - عندما Mihon يطلب imageUrl للصفحة
    // نعيد نفس الـ URL حتى يمر عبر الـ Interceptor
    override fun imageUrlParse(response: Response): String {
        return response.request.url.toString()
    }

    // تعديل imageRequest حتى يمر عبر الـ Interceptor بشكل صحيح
    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: page.url
        return Request.Builder()
            .url(imageUrl)
            .headers(headers)
            .build()
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
        val pieces: List<String> = emptyList(),
        val order: List<Int> = emptyList(),
    )
}
