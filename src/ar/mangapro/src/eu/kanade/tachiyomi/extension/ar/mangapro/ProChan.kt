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

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/series?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.grid > div, a[href^='/series/']").mapNotNull { element: Element ->
            val link = if (element.tagName() == "a") element else element.select("a[href^='/series/']").first()
            val titleText = element.select("h3, div.text-sm, span.font-bold").firstOrNull { it.text().isNotBlank() }?.text()?.trim()
            val img = element.select("img").attr("abs:src")
            if (link != null && !titleText.isNullOrEmpty()) {
                SManga.create().apply {
                    url = link.attr("href")
                    title = titleText
                    thumbnail_url = img
                }
            } else null
        }.distinctBy { it.url }
        val hasNextPage = document.select("button:contains(التالي), a[href*='page=']").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = 
        if (query.isNotBlank()) GET("$baseUrl/series?search=$query", headers) else popularMangaRequest(page)
    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga = ProChanParser.mangaDetailsParse(response.asJsoup())

    override fun chapterListRequest(manga: SManga): Request {
        val segments = manga.url.trim('/').split("/")
        val id = segments.find { it.toLongOrNull() != null } ?: segments.last()
        return GET("$baseUrl/api/public/series/$id", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> = ProChanParser.chapterListParseFromJson(response)
    override fun pageListParse(response: Response) = ProChanParser.pageListParse(response)
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
