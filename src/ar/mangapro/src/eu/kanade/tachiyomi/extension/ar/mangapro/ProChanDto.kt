package eu.kanade.tachiyomi.extension.ar.mangapro

import kotlinx.serialization.Serializable

@Serializable
data class Data<T>(val data: T)

@Serializable
data class Series(val series: MangaDetails)

@Serializable
data class MangaDetails(
    val id: Int,
    val initialChapters: InitialChapters? = null
)

@Serializable
data class InitialChapters(
    val initialChapters: List<Chapter>
)

@Serializable
data class Chapter(
    val id: Int,
    val number: String,
    val title: String? = null
)

@Serializable
data class Images(
    val images: List<String>
)
