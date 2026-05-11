package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import okhttp3.Request
import okhttp3.Response

class ProChan : HttpSource() {
    override val name = "ProChan"
    override val baseUrl = "https://procomic.pro"
    override val lang = "ar"
    override val supportsLatest = true
    override val client = ProChanHttp.configureClient(network.cloudflareClient, baseUrl)
    override fun headersBuilder() = ProChanHttp.getHeaders(baseUrl).newBuilder()

    private var decryptionKey: String = ""

    private fun fetchDecryptionKey() {
        if (decryptionKey.isNotEmpty()) return
        try {
            val response = client.newCall(GET("$baseUrl/api/config", headers)).execute()
            val body = response.body.string()
            val match = Regex("\"key\"\\s*:\\s*\"([^\"]+)\"").find(body)
            decryptionKey = match?.groupValues?.get(1) ?: ""
        } catch (_: Exception) {}
    }

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/api/library?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val res = ProChanParser.parseLibrary(response)
        return MangasPage(res.first, res.second)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/api/library?search=$query&page=$page", headers)
    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val segments = manga.url.trim('/').split("/")
        val type = segments.getOrNull(1) ?: "manga"
        val id = segments.getOrNull(2) ?: ""
        return GET("$baseUrl/api/public/$type/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga =
        ProChanParser.mangaDetailsParse(response)

    override fun chapterListRequest(manga: SManga): Request {
        val segments = manga.url.trim('/').split("/")
        val type = segments.getOrNull(1) ?: "manga"
        val id = segments.getOrNull(2) ?: ""
        return GET("$baseUrl/api/public/$type/$id/chapters?page=1&limit=500&order=desc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val url = response.request.url
        val segments = url.pathSegments
        val type = segments.getOrNull(2) ?: "manga"
        val mangaId = segments.getOrNull(3) ?: ""
        return ProChanParser.chapterListParse(response, type, mangaId)
    }

    override fun pageListRequest(chapter: SChapter): Request =
        GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val segments = response.request.url.pathSegments
        val chapterId = segments.getOrNull(segments.size - 2) ?: ""
        val apiResponse = client.newCall(
            GET("$baseUrl/api/public/chapters/$chapterId", headers)
        ).execute()
        val res = apiResponse.parseAs<DataDto<ChapterItem>>()
        val item = res.data
        if (item.metadata?.maps?.isNotEmpty() == true) {
            fetchDecryptionKey()
        }
        return ProChanParser.pageListFromChapterItem(item, decryptionKey)
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
