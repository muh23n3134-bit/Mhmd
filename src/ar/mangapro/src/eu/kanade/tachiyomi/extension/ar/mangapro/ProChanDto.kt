package eu.kanade.tachiyomi.extension.ar.mangapro

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class LibraryDto(
    val library: List<LibraryItem> = emptyList()
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
data class ChapterResponseDto(
    val data: List<ChapterDto> = emptyList()
)

@Serializable
data class ChapterDto(
    val id: Long,
    @SerialName("chapter_number") val chapterNumber: String? = null,
    val title: String? = null
)

@Serializable
data class DataDto<T>(val data: T)

@Serializable
data class ImagesDto(
    val images: List<String> = emptyList()
)
