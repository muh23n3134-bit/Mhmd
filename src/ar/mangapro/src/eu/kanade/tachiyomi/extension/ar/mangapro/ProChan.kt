package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import uy.kohesive.injekt.injectLazy

class ProChan : HttpSource(), ConfigurableSource {

    override val name = "ProChan"
    override val lang = "ar"
    override val supportsLatest = true
    override val baseUrl by lazy { getPrefBaseUrl() }
    private val preferences by getPreferencesLazy()
    private val json: Json by injectLazy()

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(1)
        .addInterceptor(::imageStitchingInterceptor)
        .build()

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/series?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        val mangas = document.select("a[href*=/series/]").mapNotNull { element ->
            val url = element.attr("href")
            val title = element.select("h3, h2, p").firstOrNull { it.text().isNotBlank() }?.text()?.trim()
            val thumb = element.select("img").attr("abs:src")
            if (title == null || !url.contains("/series/")) return@mapNotNull null
            SManga.create().apply {
                this.url = url.substringAfter(baseUrl)
                this.title = title
                this.thumbnail_url = thumb
            }
        }.distinctBy { it.url }
        return MangasPage(mangas, mangas.size >= 12)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updates?page=$page", headers)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/series?search=$query&page=$page", headers)
    }
    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())
        return SManga.create().apply {
            title = document.select("h1").text().trim()
            description = document.select("p.text-sm.line-clamp-6").text().trim()
            thumbnail_url = document.select("img[alt=poster]").firstOrNull()?.attr("abs:src") ?: ""
            genre = document.select("div.flex.wrap a").joinToString { it.text() }
            status = if (document.text().contains("مستمر")) SManga.ONGOING else SManga.COMPLETED
            initialized = true
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val segments = manga.url.trim('/').split("/")
        val mangaId = segments.getOrNull(2) ?: ""
        return GET("$baseUrl/api/public/manhua/$mangaId/chapters?limit=500&order=desc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val jsonString = response.body.string()
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)
        val chaptersData = jsonObject["data"]?.jsonArray ?: return emptyList()
        return chaptersData.map { element ->
            val item = element.jsonObject
            SChapter.create().apply {
                url = "/chapter/${item["id"]?.jsonPrimitive?.content}"
                name = "الفصل " + (item["chapterNumber"]?.jsonPrimitive?.content ?: "")
                date_upload = System.currentTimeMillis()
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.substringAfterLast("/")
        return GET("$baseUrl/api/public/chapters/$chapterId/images", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val jsonString = response.body.string()
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)
        val mediaArray = jsonObject["data"]?.jsonObject?.get("media")?.jsonArray ?: return emptyList()
        return mediaArray.mapIndexed { index, element ->
            val parts = element.jsonObject["p"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            Page(index, "", parts.joinToString("|") + "#unsplit")
        }
    }

    private fun imageStitchingInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        if (!url.contains("#unsplit")) return chain.proceed(request)
        val partUrls = url.substringBefore("#unsplit").split("|")
        val bitmaps = partUrls.map {
            val res = chain.proceed(request.newBuilder().url(it).build())
            BitmapFactory.decodeStream(res.body.byteStream())
        }
        val result = Bitmap.createBitmap(bitmaps[0].width, bitmaps.sumOf { it.height }, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        var y = 0f
        bitmaps.forEach { canvas.drawBitmap(it, 0f, y, null); y += it.height }
        val out = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return chain.proceed(request).newBuilder().body(out.toByteArray().toResponseBody("image/jpeg".toMediaType())).build()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
    override fun getFilterList() = FilterList()
    private fun getPrefBaseUrl(): String = preferences.getString("overrideBaseUrl", "https://procomic.pro")!!
    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}
