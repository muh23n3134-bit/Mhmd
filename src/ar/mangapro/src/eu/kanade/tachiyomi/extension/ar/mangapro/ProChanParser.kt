package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import java.util.Calendar

object ProChanParser {

    fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.select("h1").text().trim()
            description = document.select("div.line-clamp-6, div.text-sm.leading-relaxed").text().trim()
            genre = document.select("a[href*='genre=']").joinToString { it.text() }
            
            val typeText = document.select("div:contains(النوع), span:contains(مانجا), span:contains(مانوا)").text().lowercase()
            author = document.select("div:contains(المؤلف) + div, span:contains(المؤلف) + span").text().trim()
            
            status = when {
                document.select("div:contains(مستمر), span:contains(مستمر)").isNotEmpty() -> SManga.ONGOING
                document.select("div:contains(مكتمل), span:contains(مكتمل)").isNotEmpty() -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = document.select("img[src*='cover']").attr("abs:src")
        }
    }

    fun chapterListParse(document: Document): List<SChapter> {
        return document.select("a[href*='/chapter/']").map { element ->
            SChapter.create().apply {
                url = element.attr("href")
                val nameElement = element.select("span, div").firstOrNull { it.text().contains("الفصل") }
                name = nameElement?.text() ?: "الفصل ${element.attr("href").substringAfterLast("/")}"
                date_upload = Calendar.getInstance().timeInMillis
            }
        }
    }
}
