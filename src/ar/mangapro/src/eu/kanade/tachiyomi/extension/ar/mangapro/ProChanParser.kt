package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
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

    fun chapterListParseFromJson(response: Response): List<SChapter> {
        val data = response.parseAs<Data<Series>>()
        return data.data.series.initialChapters?.initialChapters?.map { chapter ->
            SChapter.create().apply {
                url = "/chapter/${chapter.id}"
                name = "الفصل ${chapter.number}${if (!chapter.title.isNullOrBlank()) " - ${chapter.title}" else ""}"
                date_upload = Calendar.getInstance().timeInMillis
            }
        } ?: emptyList()
    }
}
