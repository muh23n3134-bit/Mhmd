package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class ProChan : HttpSource() {
    override val name = "ProChan"
    override val baseUrl = "https://procomic.pro"
    override val lang = "ar"
    override val supportsLatest = true
    override val client = ProChanHttp.configureClient(network.cloudflareClient, baseUrl)
    override fun headersBuilder() = ProChanHttp.getHeaders(baseUrl).newBuilder()

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/library?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val res = ProChanParser.parseLibrary(response)
        return MangasPage(res.first, res.second)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = 
        GET("$baseUrl/api/library?search=$query", headers)
    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create()

    override fun chapterListRequest(manga: SManga): Request {
        val segments = manga.url.trim('/').split("/")
        val type = segments.getOrNull(1) ?: "manhua"
        val id = segments.getOrNull(2) ?: ""
        return GET("$baseUrl/api/public/$type/$id/chapters?page=1&limit=500&order=desc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaUrl = response.request.url.toString()
        val segments = response.request.url.pathSegments
        val type = segments[2]
        val mangaId = segments[3]
        return ProChanParser.chapterListParse(response, type, mangaId)
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<eu.kanade.tachiyomi.source.model.Page> {
        val id = response.request.url.pathSegments.let { it[it.size - 2] }
        val apiRequest = client.newCall(GET("$baseUrl/api/public/chapters/$id", headers)).execute()
        return ProChanParser.pageListParse(apiRequest)
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
