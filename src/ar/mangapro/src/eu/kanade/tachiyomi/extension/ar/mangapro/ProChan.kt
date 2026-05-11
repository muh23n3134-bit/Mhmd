package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.injectLazy

class ProChan : HttpSource() {
    override val name = "ProChan"
    override val baseUrl = "https://procomic.pro"
    override val lang = "ar"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/library?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val res = ProChanParser.parseLibrary(response.body.string(), json)
        return MangasPage(res.first, res.second)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = 
        GET("$baseUrl/api/library?search=$query", headers)
    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create()

    private fun parseMangaUrl(url: String): Pair<String, String> {
        val segments = url.trim('/').split("/")
        val type = segments.getOrNull(1) ?: "manhua"
        val id = segments.getOrNull(2) ?: ""
        return type to id
    }

    override fun chapterListRequest(manga: SManga): Request {
        val (type, id) = parseMangaUrl(manga.url)
        return GET("$baseUrl/api/public/$type/$id/chapters?page=1&limit=500&order=desc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        return ProChanParser.chapterListParse(response.body.string(), json)
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.split("/").last { it.isNotBlank() }
        return GET("$baseUrl/api/public/chapters/$id", headers)
    }

    override fun pageListParse(response: Response): List<eu.kanade.tachiyomi.source.model.Page> {
        return ProChanParser.pageListParse(response.body.string(), json)
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
