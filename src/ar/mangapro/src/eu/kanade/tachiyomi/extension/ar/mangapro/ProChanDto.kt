package eu.kanade.tachiyomi.extension.ar.mangapro

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class LibraryDto(
    val library: List<LibraryItem> = emptyList(),
    val recents: List<LibraryItem> = emptyList(),
    val favorites: List<LibraryItem> = emptyList()
)

@Serializable
data class LibraryItem(
    val id: Int,
    val slug: String,
    val title: String,
    val type: String,
    val coverImage: String? = null,
    @SerialName("cdn_path") val cdnPath: String = "cdn1",
    val metadata: LibraryItemMetadata? = null
)

@Serializable
data class LibraryItemMetadata(
    val descriptions: Map<String, String>? = null,
    val coverImageApp: CoverImageApp? = null,
    val genres: List<String>? = null,
    val author: JsonElement? = null
)

@Serializable
data class CoverImageApp(
    val mobile: String? = null,
    val desktop: String? = null
)

@Serializable
data class ChapterListDto(
    val data: List<ChapterItem> = emptyList(),
    val total: Int = 0
)

@Serializable
data class ChapterItem(
    val id: Int,
    @SerialName("chapter_number") val chapterNumber: String,
    val title: String? = null,
    @SerialName("cdn_path") val cdnPath: String = "cdn1",
    val metadata: ChapterMetadata? = null
)

@Serializable
data class ChapterMetadata(
    val images: List<String> = emptyList(),
    val maps: List<ImageMap> = emptyList()
)

@Serializable
data class ImageMap(
    val token: String? = null
)

@Serializable
data class DataDto<T>(val data: T)

@Serializable
data class ImagesDto(
    val images: List<String>
)

@Serializable
data class ScrambledData(
    val token: String? = null
)

@Serializable
data class MangaDetailDto(
    val id: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    val coverImage: String? = null,
    val author: JsonElement? = null,
    val genres: List<String>? = null,
    val metadata: LibraryItemMetadata? = null
)
