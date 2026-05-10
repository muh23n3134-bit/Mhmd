package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class ProChan : HttpSource() {

    override val name = "ProChan"

    override val baseUrl = "https://procomic.pro"

    override val lang = "ar"

    override val supportsLatest = true

    override val client = ProChanHttp.configureClient(network.cloudflareClient, baseUrl)

    override fun headersBuilder() = ProChanHttp.getHeaders(baseUrl).newBuilder()

    override fun popularMangaRequest(page: Int): Request {
        val sessionRequest = Request.Builder()
            .url(baseUrl)
            .headers(headers)
            .build()
        
        try {
            client.newCall(sessionRequest).execute().close()
        } catch (_: Exception) {}

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
                    if (it.startsWith("/")) "https://${manga.cdn ?: "cdn"}.procomic.pro$it" else it
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
