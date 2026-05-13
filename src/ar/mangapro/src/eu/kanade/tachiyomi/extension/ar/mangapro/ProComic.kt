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
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class ProComic : HttpSource() {

    override val name = "ProComic"
    override val baseUrl = "https://procomic.pro"
    override val lang = "ar"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // =================== Popular / Latest ===================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/api/public/content/latest-updates?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonString = response.body.string()
        val res = json.decodeFromString<MangaListResponse>(jsonString)
        val mangas = res.data.map { item ->
            SManga.create().apply {
                // نستخدم نوع العمل (manhua/manga) في الرابط لتجنب الـ 404 لاحقاً
                url = "/series/${item.type}/${item.id}/${item.slug}"
                title = item.title ?: ""
                thumbnail_url = item.coverImage?.let { 
                    if (it.startsWith("http")) it else "https://app.prochan.net/series$it" 
                }
            }
        }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // =================== Search ===================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/api/library?search=$query&page=$page", headers)

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // =================== Manga Details ===================

    override fun mangaDetailsParse(response: Response): SManga = SManga.create()

    // =================== Chapter List ===================

    override fun chapterListRequest(manga: SManga): Request {
        val segments = manga.url.trim('/').split("/")
        val type = segments.getOrNull(1) ?: "manhua"
        val id = segments.getOrNull(2) ?: ""
        return GET("$baseUrl/api/public/$type/$id/chapters?page=1&limit=500&order=desc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val jsonString = response.body.string()
        val res = json.decodeFromString<ChaptersResponse>(jsonString)
        val mangaUrl = response.request.url.toString()
        
        // استخراج النوع والـ ID لبناء رابط الصفحة (Web URL)
        val segments = response.request.url.pathSegments
        val type = segments[2]
        val mangaId = segments[3]

        return res.data.map { item ->
            SChapter.create().apply {
                // الرابط هنا يجب أن يكون رابط الويب لكي نقوم بعمل Parse له في pageList
                url = "/series/$type/$mangaId/manga/${item.id}/${item.chapterNumber}"
                name = "الفصل ${item.chapterNumber}"
                date_upload = item.publishedAt?.let { parseDate(it) } ?: 0L
            }
        }
    }

    // =================== Page List (حل مشكلة 404) ===================

    override fun pageListRequest(chapter: SChapter): Request {
        // نطلب صفحة الويب مباشرة لاستخراج البيانات منها
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        
        // البحث عن مصفوفة الصور داخل كود الصفحة (كما في ملف 1.txt)
        val imagesRegex = """\\"images\\":\s*\[(.*?)\]""".toRegex()
        val match = imagesRegex.find(html)
        
        if (match != null) {
            val imagesString = match.groupValues[1]
            val imageUrls = imagesString.split(",")
                .map { it.trim().replace("\\\"", "").replace("\"", "") }
                .filter { it.isNotBlank() }

            return imageUrls.mapIndexed { index, path ->
                // الرابط الأساسي للصور في سيرفرات برو تشان
                val fullUrl = if (path.startsWith("http")) path else "https://app.prochan.net/series$path"
                Page(index, "", fullUrl)
            }
        }
        
        throw Exception("لم يتم العثور على روابط الصور في الصفحة")
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // =================== Helpers ===================

    private fun parseDate(dateStr: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    @Serializable
    data class MangaListResponse(val data: List<MangaItem> = emptyList())

    @Serializable
    data class MangaItem(
        val id: Int,
        val title: String? = null,
        val slug: String? = null,
        val type: String? = "manhua",
        val coverImage: String? = null
    )

    @Serializable
    data class ChaptersResponse(val data: List<ChapterDto> = emptyList())

    @Serializable
    data class ChapterDto(
        val id: Int,
        @SerialName("chapter_number") val chapterNumber: String,
        @SerialName("published_at") val publishedAt: String? = null
    )
}
