package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import rx.Observable

class ProChan : HttpSource() {
    override val name = "ProChan"
    override val lang = "ar"
    private val domain = "procomic.pro"
    override val baseUrl = "https://$domain"
    override val supportsLatest = true
    override val versionId = 5

    // إعدادات العميل لمحاكاة المتصفح وتجنب الحظر الأساسي
    override val client = network.cloudflareClient.newBuilder()
        .addNetworkInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .build()
            chain.proceed(request)
        }
        .build()

    // 1. طلب قائمة المانجا الشهيرة
    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/public/series/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("status", "approved")
            addQueryParameter("limit", "18")
            addQueryParameter("page", page.toString())
            addQueryParameter("sort", "views") // الترتيب حسب المشاهدات للأعمال الشائعة
        }.build()
        return GET(url, headers)
    }

    // 2. تحليل الاستجابة وتحويلها إلى قائمة أعمال
    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<MetaData<BrowseManga>>()
        
        val mangas = data.data.map { manga ->
            SManga.create().apply {
                // الرابط الذي سيستخدم لاحقاً لجلب التفاصيل
                url = "/series/${manga.type}/${manga.id}/${manga.slug}"
                title = manga.title
                thumbnail_url = if (manga.coverImage?.startsWith("/") == true) {
                    "https://${manga.cdn ?: "cdn"}.$domain${manga.coverImage}"
                } else {
                    manga.coverImage
                }
            }
        }
        return MangasPage(mangas, data.meta.hasNextPage())
    }

    // لغرض التبسيط في هذا الجزء، سنجعل الأحدث والبحث يستخدمون نفس منطق "الشائعة" مؤقتاً
    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = popularMangaRequest(page)
    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // دوال غير مفعلة في هذا الجزء
    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()
    override fun pageListParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}

// الكائنات المطلوبة لتحويل بيانات الـ JSON
@Serializable
data class MetaData<T>(val data: List<T>, val meta: PageMeta)

@Serializable
data class PageMeta(val currentPage: Int? = null, val totalPages: Int? = null) {
    fun hasNextPage() = (currentPage ?: 0) < (totalPages ?: 0)
}

@Serializable
data class BrowseManga(
    val id: Int,
    val title: String,
    val slug: String,
    val type: String,
    val coverImage: String? = null,
    val cdn: String? = null
