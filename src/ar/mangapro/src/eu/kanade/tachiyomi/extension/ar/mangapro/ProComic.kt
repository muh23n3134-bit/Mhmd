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
        val parts = manga.url.split("/")
        val type = parts.getOrElse(0) { "manga" }
        val id = parts.getOrElse(1) { "0" }
        return GET("$baseUrl/api/public/$type/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        return try {
            val data = response.parseAs<SeriesDetailResponse>()
            SManga.create().apply {
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
        val data = response.parseAs<ChaptersResponse>()
        return data.data.map { chapter ->
            SChapter.create().apply {
                url = chapter.id.toString()
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
        return GET("$baseUrl/api/public/chapter/${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<ChapterDetailResponse>()
        val cdnPath = data.cdnPath ?: "cdn1"
        val baseImageUrl = "https://$cdnPath.procomic.pro"

        val pages = mutableListOf<Page>()
        var index = 0

        // الصور المباشرة
        data.metadata?.images?.forEach { path ->
            val fullUrl = if (path.startsWith("http")) path else "$baseImageUrl$path"
            pages.add(Page(index++, imageUrl = fullUrl))
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
    data class SeriesDetailResponse(
        val id: Int = 0,
        val title: String? = null,
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
    data class ChapterDetailResponse(
        val id: Int = 0,
        @SerialName("content_id") val contentId: Int = 0,
        @SerialName("chapter_number") val chapterNumber: String = "0",
        @SerialName("cdn_path") val cdnPath: String? = "cdn1",
        val metadata: ChapterMetadata? = null,
    )

    @Serializable
    data class ChapterMetadata(
        val images: List<String> = emptyList(),
    )
}
