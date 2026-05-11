package eu.kanade.tachiyomi.extension.ar.mangapro

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LibraryDto(
    val library: List<LibraryItem>
)

@Serializable
data class LibraryItem(
    val id: Int,
    val slug: String,
    val title: String,
    val type: String,
    val coverImage: String
)

@Serializable
data class ChapterListDto(
    val data: List<ChapterItem>,
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
    val author: String? = null,
    val genres: List<String>? = null
)
