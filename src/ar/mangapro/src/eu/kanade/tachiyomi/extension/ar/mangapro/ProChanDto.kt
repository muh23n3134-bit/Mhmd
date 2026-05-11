package eu.kanade.tachiyomi.extension.ar.mangapro

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
    val data: List<ChapterItem>
)

@Serializable
data class ChapterItem(
    val id: Int,
    val chapter_number: String,
    val title: String? = null
)

@Serializable
data class DataDto<T>(val data: T)

@Serializable
data class ImagesDto(
    val images: List<String>
)
