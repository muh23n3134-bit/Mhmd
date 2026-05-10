package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import org.jsoup.nodes.Document
import java.util.Calendar

object ProChanParser {

    fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.select("h1").text().trim()
            description = document.select("div.line-clamp-6, p.text-sm, .text-sm.leading-relaxed").firstOrNull()?.text()?.trim()
            genre = document.select("a[href*='genre=']").joinToString { it.text() }
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
        val scriptData = document.select("script#__NEXT_DATA__").firstOrNull()?.data()
        
        if (scriptData != null) {
            try {
                val json = scriptData.parseAs<NextData>()
                val seriesData = json.props.pageProps.fallback.values.firstOrNull { it.series != null }?.series
                val initialChapters = seriesData?.initialChapters?.initialChapters ?: emptyList()

                if (initialChapters.isNotEmpty()) {
                    return initialChapters.map { chapter ->
                        SChapter.create().apply {
                            url = "/chapter/${chapter.id}"
                            name = "الفصل ${chapter.number}${if (chapter.title != null) " - ${chapter.title}" else ""}"
                            date_upload = Calendar.getInstance().timeInMillis
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return document.select("a[href*='/chapter/']").map { element ->
            SChapter.create().apply {
                url = element.attr("href")
                name = element.select("span, div").firstOrNull { it.text().contains("الفصل") }?.text() ?: element.text()
                date_upload = Calendar.getInstance().timeInMillis
            }
        }.distinctBy { it.url }.reversed()
    }
}
