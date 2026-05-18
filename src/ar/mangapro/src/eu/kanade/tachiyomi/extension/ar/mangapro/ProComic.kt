package eu.kanade.tachiyomi.extension.ar.mangapro

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
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class ProComic : HttpSource() {

    override val name = "ProComic"
    override val baseUrl = "https://procomic.pro"
    override val lang = "ar"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    override val client = network.cloudflareClient

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
        // manga.url = type/id/slug
        val parts = manga.url.split("/")
        val type = parts.getOrElse(0) { "manga" }
        val id = parts.getOrElse(1) { "0" }
        return GET("$baseUrl/api/public/$type/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        return try {
            val data = response.parseAs<SeriesDetailResponse>()
            SManga.create().apply {
                // نحفظ slug في url بشكل كامل: type/id/slug
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
        // manga.url = type/id/slug
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
        // استخراج type/id/slug من الـ referer أو الـ URL
        val requestUrl = response.request.url.toString()
        val urlParts = requestUrl.split("/")
        val publicIdx = urlParts.indexOf("public")
        val seriesType = urlParts.getOrElse(publicIdx + 1) { "manga" }
        val seriesId = urlParts.getOrElse(publicIdx + 2) { "0" }

        // الـ slug محفوظ في الـ request header Referer
        val referer = response.request.header("Referer") ?: ""
        // نحاول استخراج slug من الـ referer
        // referer = https://procomic.pro/ (غير مفيد هنا)
        // سنحتاج تمريره بطريقة أخرى - نستخدم custom header

        val data = response.parseAs<ChaptersResponse>()

        return data.data.map { chapter ->
            SChapter.create().apply {
                // url = type/seriesId/chapterId/chapterNumber
                // الـ slug سنجلبه من mangaDetailsRequest لاحقاً
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
        // chapter.url = type/seriesId/chapterId/chapterNumber
        val parts = chapter.url.split("/")
        val seriesType = parts.getOrElse(0) { "manga" }
        val seriesId = parts.getOrElse(1) { "0" }
        val chapterId = parts.getOrElse(2) { "0" }
        val chapterNum = parts.getOrElse(3) { "1" }

        // نجلب slug من series details أولاً
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

        // استخراج chapterId من URL
        // URL: /series/{type}/{id}/{slug}/{chapterId}/{pageNum}
        val urlParts = requestUrl.split("/")
        // chapterId هو قبل الأخير
        val chapterId = urlParts.getOrElse(urlParts.size - 2) { "0" }

        // استخراج JWT Token من HTML
        val jwtRegex = Regex("""eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\.[a-zA-Z0-9_\-]+\.[a-zA-Z0-9_\-]+""")
        val token = jwtRegex.find(html)?.value ?: return emptyList()

        // جلب الصور
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

        // الصور المقطعة - كل map = صفحة واحدة مقطعة
        // نضعها كصور منفصلة بعد إعادة الترتيب
        pagesData.data.maps.forEach { map ->
            val ordered = if (map.order.isNotEmpty()) {
                map.order.mapNotNull { i -> map.pieces.getOrNull(i) }
            } else {
                map.pieces
            }
            ordered.forEach { imageUrl ->
                pages.add(Page(index++, imageUrl = imageUrl))
            }
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
            // نخزن: type/id/slug
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
        val pieces: List<String> = emptyList(),
        val order: List<Int> = emptyList(),
    )
}
