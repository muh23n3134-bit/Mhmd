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
        val hasNextPage = mangas.size >= 30
        return MangasPage(mangas, hasNextPage)
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
            .addQueryParameter("q", query)
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
        val data = response.parseAs<SeriesDetailResponse>()
        return SManga.create().apply {
            title = data.data.title ?: ""
            thumbnail_url = data.data.coverImage
            author = data.data.author
            artist = data.data.artist
            description = data.data.description
            genre = data.data.genres?.joinToString(", ") { it.en }
            status = when (data.data.status?.lowercase()) {
                "ongoing", "مستمر" -> SManga.ONGOING
                "completed", "مكتمل" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
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
                    .plus(if (!chapter.title.isNullOrBlank()) " - ${chapter.title}" else "")
                date_upload = runCatching {
                    dateFormat.parse(chapter.publishedAt ?: "")?.time ?: 0L
                }.getOrDefault(0L)
                chapter_number = chapter.number.toFloatOrNull() ?: 0f
                scanlator = if (chapter.lockedByCoins == true) "🔒 مدفوع" else null
            }
        }
    }

    // =================== Pages ===================

    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$baseUrl/api/public/chapter/${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterData = response.parseAs<ChapterDetailResponse>()
        val chapterId = chapterData.data?.id ?: return emptyList()
        val token = chapterData.data.token ?: return emptyList()

        val apiUrl = "$baseUrl/chapter-deferred-media/$chapterId".toHttpUrl().newBuilder()
            .addQueryParameter("token", token)
            .addQueryParameter("split", "0")
            .build()

        val apiResponse = client.newCall(GET(apiUrl, headers)).execute()
        val pagesData = apiResponse.parseAs<ChapterPagesResponse>()

        if (!pagesData.success || pagesData.data == null) return emptyList()

        val pages = mutableListOf<Page>()
        var index = 0

        pagesData.data.images.forEach { imageUrl ->
            pages.add(Page(index++, imageUrl = imageUrl))
        }

        pagesData.data.maps.forEach { map ->
            reorderImages(map.pieces, map.order).forEach { imageUrl ->
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
        val status: String? = null,
        val chapters: List<ChapterPreviewDto> = emptyList(),
        val coverImageApp: CoverImageApp? = null,
    ) {
        fun toSManga() = SManga.create().apply {
            url = "$type/$id"
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
    data class ChapterPreviewDto(
        val id: Int = 0,
        val number: String = "0",
        val publishedAt: String? = null,
    )

    @Serializable
    data class SeriesDetailResponse(
        val success: Boolean = false,
        val data: SeriesDetailDto,
    )

    @Serializable
    data class SeriesDetailDto(
        val id: Int = 0,
        val title: String? = null,
        val coverImage: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val description: String? = null,
        val status: String? = null,
        val genres: List<GenreDto>? = null,
    )

    @Serializable
    data class GenreDto(
        val id: Int = 0,
        val en: String = "",
    )

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
        val lockedByCoins: Boolean? = null,
    )

    @Serializable
    data class ChapterDetailResponse(
        val success: Boolean = false,
        val data: ChapterDetailDto? = null,
    )

    @Serializable
    data class ChapterDetailDto(
        val id: Int = 0,
        val token: String? = null,
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
}
