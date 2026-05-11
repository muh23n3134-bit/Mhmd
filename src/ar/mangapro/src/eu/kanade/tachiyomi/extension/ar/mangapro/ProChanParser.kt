package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.Page
import keiyoushi.utils.parseAs
import okhttp3.Response
import org.jsoup.nodes.Document
import java.util.Calendar

object ProChanParser {
    fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.select("h1").text().trim()
            description = document.select("div.line-clamp-6, p.text-sm").firstOrNull()?.text()?.trim()
            genre = document.select("a[href*='genre=']").joinToString { it.text() }
            status = if (document.select("div:contains(مستمر)").isNotEmpty()) SManga.ONGOING else SManga.COMPLETED
            thumbnail_url = document.select("img[src*='cover']").attr("abs:src")
        }
    }

    fun chapterListParseFromHtml(document: Document): List<SChapter> {
        return document.select("a[href*='/chapter/']").map { element ->
            SChapter.create().apply {
                url = element.attr("href")
                val rawName = element.select("span, p, div").firstOrNull { it.text().contains("الفصل") || it.text().any { c -> c.isDigit() } }?.text()
                name = rawName ?: element.text().trim()
                date_upload = Calendar.getInstance().timeInMillis
            }
        }.distinctBy { it.url }
    }

    fun pageListParse(response: Response): List<Page> {
        return try {
            val res = response.parseAs<Data<Images>>()
            res.data.images.mapIndexed { i, url ->
                Page(i, "", url)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
