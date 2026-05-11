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
    val images: List<String>,
    val maps: List<ScrambledData>? = null
)

@Serializable
data class ScrambledData(
    val token: String? = null,
    val mode: String? = null,
    val dim: List<Int>? = null,
    val pieces: List<String>? = null,
    val order: List<Int>? = null
)

@Serializable
data class ScrambledImageTokenValue(
    val iv: String,
    val tag: String,
    val data: String,
    val m: String,
    val v: Int,
    val cid: Int
)
