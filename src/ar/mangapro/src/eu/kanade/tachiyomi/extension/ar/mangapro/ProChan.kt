package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
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

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/series?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        
        val mangas = document.select("div.grid div.relative.group, a[href^='/series/']").mapNotNull { element: Element ->
            val link = if (element.tagName() == "a") element else element.select("a").first()
            val titleText = element.select("h3, div.text-sm").text().trim()
            val img = element.select("img").attr("abs:src")

            if (link != null && titleText.isNotEmpty()) {
                SManga.create().apply {
                    url = link.attr("href")
                    title = titleText
                    thumbnail_url = img
                }
            } else null
        }

        val hasNextPage = document.select("button:contains(التالي), a[href*='page=']").isNotEmpty()

        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/series?search=$query&page=$page", headers)
        } else {
            popularMangaRequest(page)
        }
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()
    override fun pageListParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
