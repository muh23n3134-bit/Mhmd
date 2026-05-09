package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
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
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.closeQuietly
import okio.Buffer
import rx.Observable
import tachiyomi.decoder.ImageDecoder
import java.io.IOException
import java.lang.UnsupportedOperationException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

class ProChan : HttpSource() {
    override val name = "ProChan"
    override val lang = "ar"
    private val domain = "procomic.pro"
    override val baseUrl = "https://$domain"
    override val supportsLatest = true
    override val versionId = 6

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(::scrambledImageInterceptor)
        .addNetworkInterceptor(
            CookieInterceptor(
                domain,
                listOf(
                    "safe_browsing" to "off",
                    "language" to "ar",
                ),
            ),
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36")
        .set("X-Requested-With", "XMLHttpRequest")
        .set("X-Nextjs-Data", "1")
        .set("Accept", "text/x-component, application/json, text/plain, */*")
        .set("sec-ch-ua", "\"Chromium\";v=\"145\", \"Google Chrome\";v=\"145\", \"Not-A.Brand\";v=\"99\"")
        .set("sec-ch-ua-mobile", "?1")
        .set("sec-ch-ua-platform", "\"Android\"")
        .set("sec-ch-ua-model", "\"Infinix X688B\"")

    private val rscHeaders = headersBuilder()
        .set("rsc", "1")
        .build()

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        val filters = getFilterList().apply {
            firstInstance<SortFilter>().state = 2
        }
        return fetchSearchManga(page, "", filters)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        val filters = getFilterList().apply {
            firstInstance<SortFilter>().state = 1
        }
        return fetchSearchManga(page, "", filters)
    }

    private val pageNumber = ConcurrentHashMap<String, Int>()

    private fun searchKey(query: String, filters: FilterList): String {
        val filterPart = filters.filterIsInstance<Filter<*>>()
            .joinToString("|") { it.state.toString() }
        return "$query::$filterPart"
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            val path = url.pathSegments
            if (url.host == domain && path.size >= 4 && path[0] == "series") {
                val type = path[1]
                if (type !in SUPPORTED_TYPES) throw Exception("نوع غير مدعوم")
                val mangaId = path[2]
                val slug = path[3]
                val manga = SManga.create().apply {
                    this@apply.url = "/series/$type/$mangaId/$slug"
                }
                return fetchMangaDetails(manga).map { MangasPage(listOf(it), false) }
            } else {
                throw Exception("رابط غير مدعوم")
            }
        }
        val key = searchKey(query, filters)
        if (page == 1) pageNumber[key] = 1
        return client.newCall(searchMangaRequest(pageNumber[key]!!, query, filters))
            .asObservableSuccess()
            .map { response ->
                val statusFilter = filters.firstInstance<StatusFilter>().selected
                val genreFilter = filters.firstInstance<GenreFilter>()
                val tagFilter = filters.firstInstance<TagFilter>()
                val data = response.parseAs<MetaData<BrowseManga>>()
                val mangas = data.data.asSequence()
                    .filter { it.type in SUPPORTED_TYPES }
                    .filter { statusFilter == null || it.progress == statusFilter }
                    .filter { genreFilter.included.isEmpty() || it.metadata.genres.containsAll(genreFilter.included) }
                    .filter { genreFilter.excluded.none { ex -> ex in it.metadata.genres } }
                    .filter { tagFilter.included.isEmpty() || it.metadata.tags.containsAll(tagFilter.included) }
                    .filter { tagFilter.excluded.none { ex -> ex in it.metadata.tags } }
                    .map { manga ->
                        SManga.create().apply {
                            url = "/series/${manga.type}/${manga.id}/${manga.slug}"
                            title = manga.title
                            thumbnail_url = (manga.coverImageApp?.desktop ?: manga.coverImage)?.let {
                                if (it.startsWith("/")) manga.cdn?.let { cdn -> "https://$cdn.$domain$it" } else it
                            }
                        }
                    }
                    .toList()
                MangasPage(mangas, data.meta.hasNextPage())
            }
            .flatMap {
                if (it.mangas.isEmpty() && it.hasNextPage) {
                    pageNumber[key] = pageNumber[key]!! + 1
                    fetchSearchManga(pageNumber[key]!!, query, filters)
                } else {
                    if (!it.hasNextPage) pageNumber.remove(key)
                    Observable.just(it)
                }
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/public/series/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("status", "approved")
            addQueryParameter("limit", "18")
            addQueryParameter("page", page.toString())
            query.takeIf(String::isNotBlank)?.also { addQueryParameter("search", it) }
            filters.firstInstance<TypeFilter>().selected?.also { addQueryParameter("type", it) }
            addQueryParameter("sort", filters.firstInstance<SortFilter>().selected)
            filters.firstInstance<YearFilter>().selected?.also { addQueryParameter("year", it) }
        }.build()
        return GET(url, headers)
    }

    override fun getFilterList() = FilterList(
        TypeFilter(),
        SortFilter(),
        YearFilter(),
        StatusFilter(),
        GenreFilter(),
        TagFilter(),
    )

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), rscHeaders)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val manga = response.extractNextJs<Series>()!!.series
        return SManga.create().apply {
            url = "/series/${manga.type}/${manga.id}/${manga.slug}"
            title = manga.title
            artist = manga.metadata.artist.joinToString()
            author = manga.metadata.author.joinToString()
            description = buildString {
                manga.description?.also { append(it.trim(), "\n\n") }
                buildList {
                    addAll(manga.metadata.altTitles)
                    manga.metadata.originalTitle?.also { add(it) }
                }.also {
                    if (it.isNotEmpty()) {
                        append("عناوين بديلة\n")
                        it.forEach { t -> append("- ", t, "\n") }
                        append("\n")
                    }
                }
            }.trim()
            genre = buildList {
                add(manga.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
                manga.metadata.year?.also { add(it) }
                manga.metadata.origin?.also { o -> add(o.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }) }
                when (manga.type) {
                    "manga" -> add("مانجا")
                    "manhwa" -> add("مانها")
                    "manhua" -> add("مانهوا")
                }
                if (manga.metadata.genres.isNotEmpty()) {
                    val genreMap = genres.associate { it.second to it.first }
                    manga.metadata.genres.mapTo(this) { genreMap[it] ?: it }
                }
                if (manga.metadata.tags.isNotEmpty()) {
                    val tagsMap = tags.associate { it.second to it.first }
                    manga.metadata.tags.mapTo(this) { tagsMap[it] ?: it }
                }
            }.joinToString()
            status = when (manga.progress?.trim()) {
                "مستمر" -> SManga.ONGOING
                "مكتمل" -> SManga.COMPLETED
                "متوقف" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            thumbnail_url = (manga.coverImageApp?.desktop ?: manga.metadata.coverImage)?.let {
                if (it.startsWith("/")) manga.cdn?.let { cdn -> "https://$cdn.$domain$it" } else it
            }
            initialized = true
        }
    }

    override fun chapterListRequest(manga: SManga) = GET(getMangaUrl(manga), rscHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.extractNextJs<InitialChapters>()!!
        val chapters = data.initialChapters.toMutableList()
        val size = chapters.size
        var page = 2
        val type = response.request.url.pathSegments[1]
        val id = response.request.url.pathSegments[2]
        val slug = response.request.url.pathSegments[3]

        while (data.totalChapters > chapters.size) {
            val request = GET("$baseUrl/api/public/$type/$id/chapters?page=${page++}&limit=$size&order=desc", headers)
            val nextChapters = client.newCall(request).execute().also {
                if (!it.isSuccessful) { it.close(); throw Exception("HTTP ${it.code}") }
            }.parseAs<Data<List<Chapter>>>()
            chapters.addAll(nextChapters.data)
        }
        countViews(id)
        return chapters.filter { it.language == "AR" }.map { chapter ->
            SChapter.create().apply {
                url = "/series/$type/$id/$slug/${chapter.id}/${chapter.number}"
                name = buildString {
                    append("\u200F")
                    if (chapter.coins != null && chapter.coins > 0) append("🔒 ")
                    append("الفصل ").append(chapter.number.toFloat().toString().substringBefore(".0"))
                    chapter.title?.trim()?.takeIf { it.isNotBlank() }?.let { t ->
                        if (t != chapter.number.trim() && t != chapter.number) {
                            append(" \u200F- ").append(t)
                        }
                    }
                }
                scanlator = chapter.uploader ?: "\u200B"
                chapter_number = chapter.number.toFloat()
                date_upload = dateFormat.tryParse(chapter.createdAt)
            }
        }.sortedByDescending { it.chapter_number }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), rscHeaders)

    override fun getChapterUrl(chapter: SChapter): String {
        val url = if (chapter.url.startsWith("{")) chapter.url.parseAs<ChapterUrl>().url else chapter.url
        return "$baseUrl$url"
    }

    override fun pageListParse(response: Response): List<Page> {
        val responseBody = response.body.string()
        val imageData = responseBody.extractNextJsRsc<Images>()
        if (imageData == null) {
            val coins = responseBody.extractNextJsRsc<Coins>()?.coins
            if (coins != null && coins > 0) throw Exception("فصل مدفوع") else return emptyList()
        }
        val seriesId = response.request.url.pathSegments[2]
        val chapterId = response.request.url.pathSegments[4]
        val images = imageData.images.toMutableList()
        val maps = mutableListOf<ScrambledData>()
        if (imageData.deferredMedia != null) {
            val deferredUrl = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("chapter-deferred-media")
                .addPathSegment(chapterId)
                .addQueryParameter("token", imageData.deferredMedia.token)
                .build()
            val deferredImages = client.newCall(GET(deferredUrl, headers)).execute().parseAs<Data<DeferredImages>>()
            images.addAll(deferredImages.data.images)
            maps.addAll(deferredImages.data.maps)
        }
        countViews(seriesId, chapterId)
        val chapterUrl = response.request.url.toString()
        val pages = mutableListOf<Page>()
        images.mapIndexedTo(pages) { i, url -> Page(i, chapterUrl, url) }
        maps.mapIndexedTo(pages) { i, data -> Page(pages.size + i, chapterUrl, "http://$SCRAMBLED_IMAGE_HOST/#${data.toJsonString()}") }
        return pages
    }

    override fun imageRequest(page: Page): Request {
        val headers = headersBuilder().set("Referer", page.url).build()
        return GET(page.imageUrl!!, headers)
    }

    private fun scrambledImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != SCRAMBLED_IMAGE_HOST) return chain.proceed(request)
        val chapterUrl = request.header("Referer")!!
        val cdn = when (chapterUrl.toHttpUrl().pathSegments[1]) {
            "manga" -> "cdn1"; "manhua" -> "cdn2"; else -> "cdn3"
        }
        val scrambledImage = when (val data = url.fragment!!.parseAs<ScrambledData>()) {
            is ScrambledImage -> data; is ScrambledImageToken -> decodeScrambledImageToken(data)
        }
        val (puzzleMode, layout) = scrambledImage.mode.split("_", limit = 2)
        val width = scrambledImage.dim[0]
        val height = scrambledImage.dim[1]
        val orderedPieces = scrambledImage.order.map { scrambledImage.pieces[it] }
        val pieceBitmaps = runBlocking {
            orderedPieces.map { pieceUrl ->
                async(Dispatchers.IO.limitedParallelism(2)) {
                    var imgUrl = (if (pieceUrl.startsWith("/")) "https://$cdn.$domain$pieceUrl" else pieceUrl).toHttpUrl()
                    if (imgUrl.host.startsWith("cdn")) {
                        val payload = Url(url = imgUrl.toString()).toJsonString().toRequestBody(JSON_MEDIA_TYPE)
                        val signRequest = POST("$baseUrl/api/cdn-image/sign", headersBuilder().set("Referer", chapterUrl).build(), payload)
                        val response = client.newCall(signRequest).await()
                        if (response.isSuccessful) {
                            val token = response.parseAs<Token>()
                            imgUrl = imgUrl.newBuilder().setQueryParameter("token", token.token).setQueryParameter("expires", token.expires.toString()).build()
                        } else { response.close() }
                    }
                    val response = client.newCall(request.newBuilder().url(imgUrl).build()).await()
                    response.body.use { body ->
                        val decoder = ImageDecoder.newInstance(body.byteStream()) ?: throw Exception("Decoder error")
                        try { decoder.decode() ?: throw Exception("Decode fail") } finally { decoder.recycle() }
                    }
                }
            }.awaitAll()
        }
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        when (puzzleMode) {
            "vertical" -> {
                var x = 0f
                for (b in pieceBitmaps) { canvas.drawBitmap(b, x, 0f, null); x += b.width }
            }
            "grid" -> {
                val (cols, rows) = layout.split('x', limit = 2).map { it.toInt() }
                var y = 0f
                var idx = 0
                for (r in 0 until rows) {
                    var x = 0f
                    var maxHeight = 0f
                    for (c in 0 until cols) {
                        if (idx < pieceBitmaps.size) {
                            val b = pieceBitmaps[idx++]
                            canvas.drawBitmap(b, x, y, null)
                            x += b.width
                            if (b.height > maxHeight) maxHeight = b.height.toFloat()
                        }
                    }
                    y += maxHeight
                }
            }
        }
        val stream = Buffer()
        resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream.outputStream())
        return Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(stream.readByteString().asResponseBody("image/png".toMediaType()))
            .build()
    }

    private fun decodeScrambledImageToken(data: ScrambledImageToken): ScrambledImage {
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(data.token.toByteArray()), "AES")
        val iv = data.token.substring(10, 22).toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(Base64.decode(data.data)).decodeToString().parseAs<ScrambledImage>()
    }

    private fun countViews(seriesId: String, chapterId: String? = null) {
        val userAgent = headers["User-Agent"]!!
        val payload = ViewsDto(
            chapterId = chapterId?.toInt(),
            contentId = seriesId.toInt(),
            deviceType = when {
                MOBILE_REGEX.containsMatchIn(userAgent) -> "mobile"
                TABLES_REGEX.containsMatchIn(userAgent) -> "tablet"
                else -> "desktop"
            },
            surface = if (chapterId == null) "series" else "chapter",
        ).toJsonString().toRequestBody(JSON_MEDIA_TYPE)
        client.newCall(POST("$baseUrl/api/views", headers, payload)).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) { response.closeQuietly() }
            override fun onFailure(call: Call, e: IOException) { Log.e(name, "View count failed", e) }
        })
    }

    companion object {
        private const val SCRAMBLED_IMAGE_HOST = "scrambled"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SUPPORTED_TYPES = listOf("manga", "manhwa", "manhua")
        private val MOBILE_REGEX = Regex("Android|iPhone|Infinix|Tecno", RegexOption.IGNORE_CASE)
        private val TABLES_REGEX = Regex("iPad|Tablet", RegexOption.IGNORE_CASE)
    }
}
