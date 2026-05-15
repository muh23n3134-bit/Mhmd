package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
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

            if (!url.contains("#merge")) return chain.proceed(request)

            return try {
                val pieceUrls = url.substringBefore("#merge").split("|")
                val mergedBytes = downloadAndMerge(chain, request, pieceUrls)

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mergedBytes.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            } catch (e: Exception) {
                // بدلاً من إرجاع 500 ووقف القراءة، سنرجع استجابة فارغة لتجنب الانهيار
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Merge failed: ${e.message}")
                    .body("".toResponseBody(null))
                    .build()
            }
        }

        private fun downloadAndMerge(chain: Interceptor.Chain, originalRequest: Request, pieceUrls: List<String>): ByteArray {
            val bitmaps = mutableListOf<Bitmap>()
            try {
                pieceUrls.forEach { pieceUrl ->
                    val response = chain.proceed(originalRequest.newBuilder().url(pieceUrl).build())
                    if (!response.isSuccessful) throw Exception("قطعة مفقودة: ${response.code}")
                    
                    val bytes = response.body.bytes()
                    response.close()

                    var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    
                    // محاولة بديلة إذا فشل AVIF
                    if (bitmap == null && pieceUrl.contains(".avif")) {
                        val webpUrl = pieceUrl.replace(".avif", ".webp")
                        val webpResponse = chain.proceed(originalRequest.newBuilder().url(webpUrl).build())
                        if (webpResponse.isSuccessful) {
                            val webpBytes = webpResponse.body.bytes()
                            bitmap = BitmapFactory.decodeByteArray(webpBytes, 0, webpBytes.size)
                        }
                        webpResponse.close()
                    }

                    bitmaps.add(bitmap ?: throw Exception("تعذر فك تشفير قطعة"))
                }

                val maxWidth = bitmaps.maxOf { it.width }
                val totalHeight = bitmaps.sumOf { it.height }
                val merged = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(merged)
                var currentY = 0f
                bitmaps.forEach { 
                    canvas.drawBitmap(it, 0f, currentY, null)
                    currentY += it.height
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

    // =================== الجزء المهم: إصلاح ظهور الصور ===================
    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val chapterId = response.request.url.toString().split("/").let { it[it.size - 2] }
        val token = Regex("""eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\.[a-zA-Z0-9_\-]+\.[a-zA-Z0-9_\-]+""").find(html)?.value ?: return emptyList()

        val pagesResponse = client.newCall(GET("$baseUrl/chapter-deferred-media/$chapterId?token=$token&split=0", headers)).execute()
        val pagesData = pagesResponse.parseAs<ChapterDeferredResponse>()
        if (!pagesData.success || pagesData.data == null) return emptyList()

        val pages = mutableListOf<Page>()
        var index = 0

        // 1. إضافة الصور العادية (التي ليست جزءاً من الخرائط المقطعة)
        val pieceUrls = pagesData.data.maps.flatMap { it.pieces }.toSet()
        pagesData.data.images.forEach { imageUrl ->
            if (!pieceUrls.contains(imageUrl)) {
                pages.add(Page(index++, imageUrl = imageUrl))
            }
        }

        // 2. إضافة الصور المقطعة كروابط دمج
        pagesData.data.maps.forEach { map ->
            val ordered = if (map.order.isNotEmpty()) map.order.mapNotNull { map.pieces.getOrNull(it) } else map.pieces
            if (ordered.isNotEmpty()) {
                pages.add(Page(index++, imageUrl = ordered.joinToString("|") + "#merge"))
            }
        }

        return pages
    }

    // (بقية الدوال Models و Request تظل كما هي في ملفك الأصلي)
    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")
    override fun popularMangaRequest(page: Int) = GET("$baseUrl/api/public/content/latest-updates?limit=30&category=comics&page=$page", headers)
    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<LatestUpdatesResponse>()
        return MangasPage(data.data.filter { it.type != "novel" }.map { it.toSManga() }, data.data.size >= 30)
    }
    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = GET("$baseUrl/api/public/content/latest-updates?limit=30&category=comics&page=$page&q=$query", headers)
    override fun searchMangaParse(response: Response) = popularMangaParse(response)
    override fun mangaDetailsRequest(manga: SManga) = GET("$baseUrl/api/public/${manga.url.split("/")[0]}/${manga.url.split("/")[1]}", headers)
    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<SeriesDetailResponse>().let { data ->
        val parts = response.request.url.toString().split("/"); val type = parts[parts.indexOf("public")+1]; val id = parts[parts.indexOf("public")+2]
        SManga.create().apply { url = "$type/$id/${data.slug}"; title = data.title ?: ""; thumbnail_url = data.coverImage; description = data.synopsis ?: data.description; status = if (data.status?.contains("مستمر") == true) SManga.ONGOING else SManga.COMPLETED }
    }
    override fun chapterListRequest(manga: SManga) = GET("$baseUrl/api/public/${manga.url.split("/")[0]}/${manga.url.split("/")[1]}/chapters?page=1&limit=500&order=desc", headers)
    override fun chapterListParse(response: Response): List<SChapter> = response.parseAs<ChaptersResponse>().data.map { ch ->
        val p = response.request.url.toString().split("/"); val t = p[p.indexOf("public")+1]; val id = p[p.indexOf("public")+2]
        SChapter.create().apply { url = "$t/$id/${ch.id}/${ch.chapterNumber}"; name = "الفصل ${ch.chapterNumber}"; date_upload = runCatching { dateFormat.parse(ch.publishedAt ?: "")?.time }.getOrNull() ?: 0L }
    }
    override fun imageUrlParse(response: Response) = ""
    private inline fun <reified T> Response.parseAs(): T = json.decodeFromStream(body.byteStream())
    @Serializable data class LatestUpdatesResponse(val success: Boolean, val data: List<SeriesDto>)
    @Serializable data class SeriesDto(@SerialName("mangaId") val id: Int, val slug: String, @SerialName("mangaTitle") val title: String, val coverImage: String?, val type: String) { fun toSManga() = SManga.create().apply { url = "$type/$id/$slug"; title = this@SeriesDto.title; thumbnail_url = coverImage } }
    @Serializable data class SeriesDetailResponse(val slug: String?, val title: String?, val coverImage: String?, val synopsis: String?, val description: String?, val status: String?)
    @Serializable data class ChaptersResponse(val data: List<ChapterDto>)
    @Serializable data class ChapterDto(val id: Int, @SerialName("chapter_number") val chapterNumber: String, val publishedAt: String?)
    @Serializable data class ChapterDeferredResponse(val success: Boolean, val data: ChapterDeferredData?)
    @Serializable data class ChapterDeferredData(val images: List<String>, val maps: List<PageMap>)
    @Serializable data class PageMap(val pieces: List<String>, val order: List<Int>)
}
