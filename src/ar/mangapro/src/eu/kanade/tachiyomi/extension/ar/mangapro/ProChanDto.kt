package eu.kanade.tachiyomi.extension.ar.mangapro

import kotlinx.serialization.Serializable

@Serializable
data class Data<T>(val data: T)

@Serializable
data class MetaData<T>(
    val data: List<T>,
    val meta: PageMeta,
)

@Serializable
data class PageMeta(
    val currentPage: Int? = null,
    val totalPages: Int? = null,
) {
    fun hasNextPage() = (currentPage ?: 0) < (totalPages ?: 0)
}

@Serializable
data class BrowseManga(
    val id: Int,
    val title: String,
    val slug: String,
    val type: String,
    val coverImage: String? = null,
    val coverImageApp: CoverImageApp? = null,
    val progress: String? = null,
    val cdn: String? = null,
    val metadata: MangaMetadata,
)

@Serializable
data class CoverImageApp(
    val desktop: String? = null,
    val mobile: String? = null,
)

@Serializable
data class Series(
    val series: MangaDetails,
)

@Serializable
data class MangaDetails(
    val id: Int,
    val title: String,
    val slug: String,
    val type: String,
    val description: String? = null,
    val progress: String? = null,
    val coverImage: String? = null,
    val coverImageApp: CoverImageApp? = null,
    val cdn: String? = null,
    val metadata: MangaMetadata,
)

@Serializable
data class MangaMetadata(
    val artist: List<String> = emptyList(),
    val author: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val altTitles: List<String> = emptyList(),
    val originalTitle: String? = null,
    val year: String? = null,
    val origin: String? = null,
    val coverImage: String? = null,
)

@Serializable
data class InitialChapters(
    val initialChapters: List<Chapter>,
    val totalChapters: Int,
)

@Serializable
data class Chapter(
    val id: Int,
    val number: String,
    val title: String? = null,
    val createdAt: String,
    val uploader: String? = null,
    val coins: Int? = null,
    val language: String? = null,
)

@Serializable
data class Images(
    val images: List<String>,
    val deferredMedia: DeferredMedia? = null,
)

@Serializable
data class DeferredMedia(
    val token: String,
)

@Serializable
data class DeferredImages(
    val images: List<String>,
    val maps: List<ScrambledData>,
)

@Serializable
sealed class ScrambledData

@Serializable
data class ScrambledImage(
    val mode: String,
    val dim: List<Int>,
    val pieces: List<String>,
    val order: List<Int>,
) : ScrambledData()

@Serializable
data class ScrambledImageToken(
    val token: String,
) : ScrambledData()

@Serializable
data class ScrambledImageTokenValue(
    val iv: String,
    val tag: String,
    val data: String,
    val m: String,
    val v: Int,
    val cid: Int,
)

@Serializable
data class Token(
    val token: String,
    val expires: Long,
)

@Serializable
data class Key(
    val key: String,
)

@Serializable
data class Url(
    val url: String,
)

@Serializable
data class ChapterUrl(
    val url: String,
)

@Serializable
data class Coins(
    val coins: Int,
)

@Serializable
data class ViewsDto(
    val chapterId: Int? = null,
    val contentId: Int,
    val deviceType: String,
    val surface: String,
)
