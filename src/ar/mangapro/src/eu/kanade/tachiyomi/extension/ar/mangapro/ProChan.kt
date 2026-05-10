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
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // --- Popular & Latest ---
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/public/content/latest-updates?limit=18&page=$page", headers)
    
    override fun popularMangaParse(response: Response): MangasPage {
        val jsonString = response.body.string()
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)
        val data = jsonObject["data"]?.jsonArray ?: return MangasPage(emptyList(), false)
        
        val mangas = data.map { element ->
            val item = element.jsonObject
            SManga.create().apply {
                url = "/series/${item["type"]?.jsonPrimitive?.content}/${item["id"]?.jsonPrimitive?.content}/${item["slug"]?.jsonPrimitive?.content}"
                title = item["title"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = item["poster"]?.jsonPrimitive?.content
            }
        }
        return MangasPage(mangas, mangas.size >= 18)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // --- Search ---
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = popularMangaRequest(page) // يمكن تطويره لاحقاً
    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // --- Details ---
    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())
        return SManga.create().apply {
            title = document.select("h1").text().trim()
            description = document.select("p").text().trim()
            thumbnail_url = document.select("img").firstOrNull()?.attr("abs:src") ?: ""
        }
    }

    // --- Chapters (تم التعديل لاستخدام الـ API الجديد) ---
    override fun chapterListRequest(manga: SManga): Request {
        val mangaId = manga.url.split("/")[3] // استخراج الرقم (مثل 34) من الرابط
        return GET("$baseUrl/api/public/manhua/$mangaId/chapters?limit=100&order=desc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val jsonString = response.body.string()
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)
        val chaptersData = jsonObject["data"]?.jsonArray ?: return emptyList()

        return chaptersData.map { element ->
            val item = element.jsonObject
            SChapter.create().apply {
                url = "${response.request.url}/chapter/${item["id"]?.jsonPrimitive?.content}"
                name = "الفصل ${item["chapterNumber"]?.jsonPrimitive?.content}"
                date_upload = System.currentTimeMillis()
            }
        }
    }

    // --- Pages (نظام دمج الـ 3 قطع) ---
    override fun pageListParse(response: Response): List<Page> {
        val jsonString = response.body.string()
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)
        val mediaArray = jsonObject["data"]?.jsonObject?.get("media")?.jsonArray ?: return emptyList()

        return mediaArray.mapIndexed { index, element ->
            val mediaObj = element.jsonObject
            val parts = mediaObj["p"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            // ندمج روابط القطع برمز | ليقوم الـ Interceptor بمعالجتها
            val combinedUrl = parts.joinToString("|")
            Page(index, "", "$combinedUrl#unsplit")
        }
    }

    // --- Interceptor لدمج الصور المقطعة عمودياً ---
    private fun imageStitchingInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (!url.contains("#unsplit")) return chain.proceed(request)

        val partUrls = url.substringBefore("#unsplit").split("|")
        
        // تحميل القطع
        val bitmaps = partUrls.map { partUrl ->
            val partRequest = request.newBuilder().url(partUrl).build()
            val response = chain.proceed(partRequest)
            BitmapFactory.decodeStream(response.body.byteStream())
        }

        // دمج القطع عمودياً
        val width = bitmaps[0].width
        val totalHeight = bitmaps.sumOf { it.height }
        val result = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        var currentY = 0f
        bitmaps.forEach { bitmap ->
            canvas.drawBitmap(bitmap, 0f, currentY, null)
            currentY += bitmap.height
        }

        val output = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 90, output)
        
        return chain.proceed(request).newBuilder()
            .body(output.toByteArray().toResponseBody("image/jpeg".toMediaType()))
            .build()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
    override fun getFilterList() = FilterList()
    private fun getPrefBaseUrl(): String = preferences.getString("overrideBaseUrl", "https://procomic.pro")!!
    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}
