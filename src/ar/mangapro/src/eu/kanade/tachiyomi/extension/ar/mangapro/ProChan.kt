package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
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

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(ProChanInterceptor(baseUrl))
        .build()

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/public/series/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("status", "approved")
            addQueryParameter("limit", "18")
            addQueryParameter("page", page.toString())
            addQueryParameter("sort", "views")
        }.build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<MetaData<BrowseManga>>()
        val mangas = data.data.map { manga ->
            SManga.create().apply {
                url = "/series/${manga.type}/${manga.id}/${manga.slug}"
                title = manga.title
                thumbnail_url = (manga.coverImageApp?.desktop ?: manga.coverImage)?.let {
                    if (it.startsWith("/")) "https://${manga.cdn ?: "cdn"}.$domain$it" else it
                }
            }
        }
        return MangasPage(mangas, data.meta.hasNextPage())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/public/series/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("status", "approved")
            addQueryParameter("limit", "18")
            addQueryParameter("page", page.toString())
            addQueryParameter("sort", "latest")
        }.build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/public/series/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("status", "approved")
            addQueryParameter("limit", "18")
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("search", query)
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()
    override fun pageListParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
