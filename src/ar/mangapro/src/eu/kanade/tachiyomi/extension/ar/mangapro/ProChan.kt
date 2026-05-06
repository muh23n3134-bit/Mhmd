package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.closeQuietly
import java.util.Calendar

class ProChan : HttpSource() {

    override val name = "ProChan"

    override val baseUrl = "https://procomic.pro"

    override val lang = "ar"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(CookieInterceptor(name, "procomic.pro"))
        .addInterceptor(::imageIntercept)
        .build()

    private val imageClient = network.client.newBuilder()
        .addInterceptor(::imageIntercept)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

    override fun fetchPopularManga(page: Int) = fetchSearchManga(page, "", FilterList())

    override fun fetchLatestUpdates(page: Int) = fetchSearchManga(page, "", FilterList(SortFilter("latest_chapter")))

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList) = client.asObservableSuccess(searchMangaRequest(page, query, filters))
        .map { response ->
            val data = response.extractNextJsRsc<MetaData<BrowseManga>>()
                ?: response.extractNextJs<MetaData<BrowseManga>>()
                ?: throw Exception("Failed to parse data")

            MangasPage(
                data.data.map { manga ->
                    SManga.create().apply {
                        url = "/series/${manga.type}/${manga.id}/${manga.slug}"
                        title = manga.title
                        thumbnail_url = manga.coverImageApp?.url ?: manga.coverImage ?: "${IMAGE_DOMAIN}/${manga.cdn}/${manga.id}/cover.webp"
                    }
                },
                data.meta.hasNextPage(),
            )
        }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotEmpty()) addQueryParameter("s", query)

            filters.forEach { filter ->
                when (filter) {
                    is TypeFilter -> filter.selected?.let { addQueryParameter("type", it) }
                    is StatusFilter -> filter.selected?.let { addQueryParameter("status", it) }
                    is SortFilter -> addQueryParameter("order", filter.selected)
                    is GenreFilter -> {
                        filter.included.forEach { addQueryParameter("genres[]", it) }
                        filter.excluded.forEach { addQueryParameter("genres_exclude[]", it) }
                    }
                }
            }
        }.build()

        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val manga = response.extractNextJs<Data<MangaDetails>>()?.data
            ?: response.extractNextJsRsc<Data<MangaDetails>>()?.data
            ?: throw Exception("Failed to parse details")

        title = manga.title
        author = manga.authors.joinToString { it.name }
        artist = manga.artists.joinToString { it.name }
        description = manga.summary
        genre = manga.genres.joinToString { it.name }
        status = when (manga.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        thumbnail_url = manga.coverImageApp?.url ?: manga.coverImage ?: "${IMAGE_DOMAIN}/${manga.cdn}/${manga.id}/cover.webp"
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val manga = response.extractNextJs<Data<MangaDetails>>()?.data
            ?: response.extractNextJsRsc<Data<MangaDetails>>()?.data
            ?: throw Exception("Failed to parse chapter list")

        return manga.chapters.map { chapter ->
            SChapter.create().apply {
                url = "/series/${manga.type}/${manga.id}/${manga.slug}/${chapter.id}/${chapter.slug}"
                name = "الفصل ${chapter.slug}"
                date_upload = chapter.createdAt?.let { parseDate(it) } ?: 0L
            }
        }
    }

    override fun fetchPageList(chapter: SChapter): rx.Observable<List<Page>> {
        return client.asObservableSuccess(GET(baseUrl + chapter.url, headers))
            .map { response ->
                val data = response.extractNextJs<Data<ChapterDetails>>()?.data
                    ?: response.extractNextJsRsc<Data<ChapterDetails>>()?.data
                    ?: throw Exception("Failed to parse pages")

                countView(data.id)

                data.images.mapIndexed { index, image ->
                    val url = if (image is ScrambledImage) {
                        baseUrl + chapter.url + "#" + image.toJsonString()
                    } else {
                        (image as Url).url
                    }
                    Page(index, "", url)
                }
            }
    }

    private fun imageIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != SCRAMBLED_IMAGE_HOST) return chain.proceed(request)

        val fragment = url.fragment ?: return chain.proceed(request)
        val data = fragment.tryParse<ScrambledImage>() ?: return chain.proceed(request)

        val result = runBlocking(Dispatchers.IO) {
            val bitmaps = data.pieces.map { piece ->
                async {
                    val response = imageClient.newCall(GET(piece, request.headers)).await()
                    val bitmap = response.asBitmap()
                    response.closeQuietly()
                    bitmap
                }
            }.awaitAll()

            val result = Bitmap.createBitmap(data.dim[0], data.dim[1], Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)

            data.order.forEachIndexed { index, pieceIndex ->
                val bitmap = bitmaps[pieceIndex]
                val width = bitmap.width
                val height = bitmap.height
                val x = (index % (data.dim[0] / width)) * width
                val y = (index / (data.dim[0] / width)) * height
                canvas.drawBitmap(bitmap, x.toFloat(), y.toFloat(), null)
            }
            result
        }

        return Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(result.asResponseBody())
            .build()
    }

    private fun countView(chapterId: Int) {
        val payload = ViewsDto(chapterId).toJsonString().toRequestBody(JSON_MEDIA_TYPE)
        client.newCall(POST("$baseUrl/api/view", headers, payload))
            .enqueue(
                object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        response.closeQuietly()
                    }
                    override fun onFailure(call: Call, e: okio.IOException) {
                        Log.e(name, "Failed to count views", e)
                    }
                },
            )
    }

    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    private fun parseDate(dateStr: String): Long {
        return try {
            Calendar.getInstance().apply {
                time = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).parse(dateStr)!!
            }.timeInMillis
        } catch (e: Exception) {
            0L
        }
    }

    companion object {
        private const val IMAGE_DOMAIN = "https://img.procomic.pro"
        private const val SCRAMBLED_IMAGE_HOST = "127.0.0.1"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
