package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.Page
import keiyoushi.utils.parseAs
import okhttp3.Response
import java.util.Calendar

object ProChanParser {
    fun parseLibrary(response: Response): Pair<List<SManga>, Boolean> {
        val res = response.parseAs<LibraryDto>()
        val mangas = res.library.map { item ->
            SManga.create().apply {
                url = "/series/${item.type}/${item.id}/${item.slug}"
                title = item.title
                thumbnail_url = if (item.coverImage.startsWith("http")) item.coverImage else "https://app.prochan.net/series" + item.coverImage
            }
        }
        return Pair(mangas, mangas.isNotEmpty())
    }

    fun chapterListParse(response: Response, type: String, mangaId: String): List<SChapter> {
        val res = response.parseAs<ChapterListDto>()
        return res.data.map { item ->
            SChapter.create().apply {
                url = "/series/$type/$mangaId/manga/${item.id}/${item.chapter_number}"
                name = "الفصل ${item.chapter_number}" + if (!item.title.isNullOrBlank()) " - ${item.title}" else ""
                date_upload = Calendar.getInstance().timeInMillis
            }
        }
    }

    fun pageListParse(response: Response): List<Page> {
        val res = response.parseAs<DataDto<ImagesDto>>()
        return res.data.images.mapIndexed { i, url ->
            Page(i, "", url)
        }
    }
}
