package eu.kanade.tachiyomi.extension.ar.mangapro

import kotlinx.serialization.Serializable

@Serializable
data class Data<T>(val data: T)

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
