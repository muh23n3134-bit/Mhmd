package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
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
        val url = "$baseUrl/api/public/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "30")
            .addQueryParameter("order", "popular")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<SeriesListResponse>()
        val mangas = data.data.map { it.toSManga() }
        val hasNextPage = data.pagination?.hasNextPage ?: false
        return MangasPage(mangas, hasNextPage)
    }

    // =================== Latest ===================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/public/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "30")
            .addQueryParameter("order", "latest")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // =================== Search ===================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/public/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "30")
            .addQueryParameter("q", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // =================== Details ===================

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$baseUrl/api/public/series/${manga.url}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = response.parseAs<SeriesDetailResponse>()
        return data.data.toSManga()
    }

    // =================== Chapters ===================

    override fun chapterListRequest(manga: SManga): Request {
        val parts = manga.url.split("/")
        val type = parts.getOrElse(0) { "manga" }
        val id = parts.getOrElse(1) { "0" }
        val url = "$baseUrl/api/public/$type/$id/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("limit", "1000")
            .addQueryParameter("order", "desc")
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<ChaptersResponse>()
        return data.data.map { chapter ->
            SChapter.create().apply {
                url = chapter.id.toString()
                name = "الفصل ${chapter.number}"
                date_upload = runCatching {
                    dateFormat.parse(chapter.publishedAt ?: "")?.time ?: 0L
                }.getOrDefault(0L)
                chapter_number = chapter.number.toFloatOrNull() ?: 0f
            }
        }
    }

    // =================== Pages ===================

    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$baseUrl/api/public/chapter/${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()

        // استخراج التوكن من الصفحة
        val tokenRegex = Regex(""""token"\s*:\s*"([^"]+)"""")
        val token = tokenRegex.find(html)?.groupValues?.get(1) ?: return emptyList()

        // استخراج chapter ID من الـ URL
        val chapterId = response.request.url.pathSegments.last()

        val apiUrl = "$baseUrl/chapter-deferred-media/$chapterId".toHttpUrl().newBuilder()
            .addQueryParameter("token", token)
            .addQueryParameter("split", "0")
            .build()

        val apiResponse = client.newCall(GET(apiUrl, headers)).execute()
        val pagesData = apiResponse.parseAs<ChapterPagesResponse>()

        if (!pagesData.success || pagesData.data == null) return emptyList()

        val pages = mutableListOf<Page>()
        var index = 0

        // الصور المباشرة
        pagesData.data.images.forEach { imageUrl ->
            pages.add(Page(index++, imageUrl = imageUrl))
        }

        // الصور المشفرة - إعادة الترتيب
        pagesData.data.maps.forEach { map ->
            val ordered = reorderImages(map.pieces, map.order)
            ordered.forEach { imageUrl ->
                pages.add(Page(index++, imageUrl = imageUrl))
            }
        }

        return pages
    }

    override fun imageUrlParse(response: Response) = ""

    // =================== Helpers ===================

    private fun reorderImages(pieces: List<String>, order: List<Int>): List<String> {
        if (order.isEmpty() || pieces.isEmpty()) return pieces
        return order.mapNotNull { i -> pieces.getOrNull(i) }
    }

    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromStream(body.byteStream())
    }

    // =================== Models ===================

    @Serializable
    data class SeriesListResponse(
        val success: Boolean = false,
        val data: List<SeriesDto> = emptyList(),
        val pagination: PaginationDto? = null,
    )

    @Serializable
    data class SeriesDetailResponse(
        val success: Boolean = false,
        val data: SeriesDto,
    )

    @Serializable
    data class SeriesDto(
        val id: Int = 0,
        val title: String = "",
        val slug: String = "",
        val type: String = "manga",
        val cover: String? = null,
        val author: String? = null,
        val description: String? = null,
        val status: String? = null,
    ) {
        fun toSManga() = SManga.create().apply {
            url = "$type/$id"
            title = this@SeriesDto.title
            thumbnail_url = cover
            author = this@SeriesDto.author
            description = this@SeriesDto.description
            status = when (this@SeriesDto.status?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    @Serializable
    data class ChaptersResponse(
        val success: Boolean = false,
        val data: List<ChapterDto> = emptyList(),
    )

    @Serializable
    data class ChapterDto(
        val id: Int = 0,
        val number: String = "0",
        val title: String? = null,
        val publishedAt: String? = null,
    )

    @Serializable
    data class ChapterPagesResponse(
        val success: Boolean = false,
        val data: ChapterPagesData? = null,
    )

    @Serializable
    data class ChapterPagesData(
        val chapterId: Int = 0,
        val images: List<String> = emptyList(),
        val maps: List<PageMap> = emptyList(),
    )

    @Serializable
    data class PageMap(
        val pieces: List<String> = emptyList(),
        val order: List<Int> = emptyList(),
    )

    @Serializable
    data class PaginationDto(
        val hasNextPage: Boolean = false,
    )
}
