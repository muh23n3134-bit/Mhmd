package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import java.util.Calendar

object ProChanParser {

    private val cdnMap = mapOf(
        "cdn1" to "https://cdn1.prochan.net",
        "cdn2" to "https://cdn2.prochan.net",
        "cdn3" to "https://cdn3.prochan.net",
        "cdn4" to "https://cdn4.prochan.net"
    )

    private fun buildCoverUrl(item: LibraryItem): String? {
        item.metadata?.coverImageApp?.mobile?.let { return it }
        val cover = item.coverImage ?: return null
        if (cover.startsWith("http")) return cover
        val cdnBase = cdnMap[item.cdnPath] ?: cdnMap["cdn1"]!!
        return "$cdnBase$cover"
    }

    fun parseLibrary(response: Response): Pair<List<SManga>, Boolean> {
        val res = response.parseAs<LibraryDto>()
        val mangas = res.library.map { item ->
            SManga.create().apply {
                url = "/series/${item.type}/${item.id}/${item.slug}"
                title = item.title
                thumbnail_url = buildCoverUrl(item)
            }
        }
        return Pair(mangas, mangas.isNotEmpty())
    }

    fun mangaDetailsParse(response: Response): SManga {
        val res = response.parseAs<DataDto<MangaDetailDto>>()
        val item = res.data
        return SManga.create().apply {
            title = item.title ?: ""
            description = item.metadata?.descriptions?.get("ar")
                ?: item.metadata?.descriptions?.get("en")
                ?: item.description
            author = try {
                item.author?.jsonPrimitive?.content
            } catch (_: Exception) { null }
            status = when (item.status?.lowercase()) {
                "ongoing", "مستمر" -> SManga.ONGOING
                "completed", "مكتمل" -> SManga.COMPLETED
                "hiatus", "متوقف" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            genre = (item.genres ?: item.metadata?.genres)?.joinToString(", ")
            thumbnail_url = item.metadata?.coverImageApp?.mobile
                ?: item.coverImage?.let {
                    if (it.startsWith("http")) it else "https://app.prochan.net/series$it"
                }
        }
    }

    fun chapterListParse(response: Response, type: String, mangaId: String): List<SChapter> {
        val res = response.parseAs<ChapterListDto>()
        return res.data.map { item ->
            SChapter.create().apply {
                url = "/series/$type/$mangaId/manga/${item.id}/${item.chapterNumber}"
                name = "الفصل ${item.chapterNumber}" +
                    if (!item.title.isNullOrBlank()) " - ${item.title}" else ""
                date_upload = Calendar.getInstance().timeInMillis
            }
        }
    }

    fun pageListFromChapterItem(item: ChapterItem, decryptionKey: String): List<Page> {
        val cdnBase = cdnMap[item.cdnPath] ?: cdnMap["cdn1"]!!
        val metadata = item.metadata ?: return emptyList()
        if (metadata.maps.isNotEmpty() && metadata.maps.size == metadata.images.size) {
            return metadata.maps.mapIndexed { i, map ->
                val imagePath = metadata.images[i]
                val scrambled = ScrambledData(token = map.token)
                val tokenValue = ProChanSecurity.decryptImage(scrambled, decryptionKey)
                val finalUrl = if (tokenValue != null) {
                    "$cdnBase${imagePath}?v=${tokenValue.v}&cid=${tokenValue.cid}"
                } else {
                    "$cdnBase$imagePath"
                }
                Page(i, "", finalUrl)
            }
        }
        return metadata.images.mapIndexed { i, path ->
            Page(i, "", "$cdnBase$path")
        }
    }

    fun pageListParse(response: Response): List<Page> {
        val res = response.parseAs<DataDto<ImagesDto>>()
        return res.data.images.mapIndexed { i, url -> Page(i, "", url) }
    }
}
