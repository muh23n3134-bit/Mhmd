package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Calendar

object ProChanParser {
    fun parseLibrary(jsonString: String, json: Json): Pair<List<SManga>, Boolean> {
        val res = json.decodeFromString<LibraryDto>(jsonString)
        val mangas = res.library.map { item ->
            SManga.create().apply {
                url = "/series/${item.type}/${item.id}/${item.slug}"
                title = item.title
                thumbnail_url = if (item.coverImage.startsWith("http")) item.coverImage else "https://app.prochan.net/series" + item.coverImage
            }
        }
        return Pair(mangas, mangas.isNotEmpty())
    }

    fun chapterListParse(jsonString: String, json: Json): List<SChapter> {
        val res = json.decodeFromString<ChapterResponseDto>(jsonString)
        return res.data.map { item ->
            SChapter.create().apply {
                url = "/chapter/${item.id}"
                name = "الفصل ${item.chapterNumber ?: ""}${if (!item.title.isNullOrBlank()) " - ${item.title}" else ""}"
                date_upload = Calendar.getInstance().timeInMillis
            }
        }
    }

    fun pageListParse(jsonString: String, json: Json): List<Page> {
        val res = json.decodeFromString<DataDto<ImagesDto>>(jsonString)
        return res.data.images.mapIndexed { i, url ->
            Page(i, "", url)
        }
    }
}
