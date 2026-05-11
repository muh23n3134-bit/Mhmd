package eu.kanade.tachiyomi.extension.ar.mangapro

import kotlinx.serialization.Serializable

@Serializable
data class Data<T>(val data: T)

@Serializable
data class NextData(val props: NextProps)

@Serializable
data class NextProps(val pageProps: PageProps)

@Serializable
data class PageProps(
    val series: MangaDetails? = null,
    val initialChapters: InitialChapters? = null
)

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
    val number: String? = null,
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
