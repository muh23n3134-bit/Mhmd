package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
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
        .rateLimit(2)
        .addInterceptor(::imageStitchingInterceptor)
        .build()

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== القوائم (تصحيح استخراج الروابط) ==============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/series?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        // نختار الروابط التي تمثل صفحة المانجا الأساسية فقط ونبتعد عن روابط الفصول
        val mangas = document.select("a[href*=/series/]").mapNotNull { element ->
            val href = element.attr("abs:href")
            val match = Regex("""/series/([^/]+)/(\d+)/([^/]+)$""").find(href) ?: return@mapNotNull null
            
            SManga.create().apply {
                url = href.substringAfter(baseUrl)
                title = element.select("h3, h2, p, span").firstOrNull { it.text().isNotBlank() }?.text()?.trim() ?: ""
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }.distinctBy { it.url }
        return MangasPage(mangas, mangas.size >= 10)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updates?page=$page", headers)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/series?search=$query&page=$page", headers)
    }
    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ============================== تفاصيل العمل ==============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())
        return SManga.create().apply {
            title = document.select("h1").text().trim()
            description = document.select("p.text-sm.line-clamp-6, div.description").text().trim()
            thumbnail_url = document.select("img[alt=poster], img.object-cover").firstOrNull()?.attr("abs:src") ?: ""
            genre = document.select("div.flex.wrap a").joinToString { it.text() }
            status = if (document.text().contains("مستمر")) SManga.ONGOING else SManga.COMPLETED
            initialized = true
        }
    }

    // ============================== الفصول (حل مشكلة النوع والـ 404) ==============================

    override fun chapterListRequest(manga: SManga): Request {
        // استخراج النوع والآيدي ديناميكياً (manhua أو manhwa أو manga)
        val match = Regex("""/series/([^/]+)/(\d+)""").find(manga.url)
        val type = match?.groupValues?.get(1) ?: "manhua"
        val id = match?.groupValues?.get(2) ?: ""
        
        return GET("$baseUrl/api/public/$type/$id/chapters?limit=1000&order=desc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val jsonString = response.body.string()
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)
        val chaptersData = jsonObject["data"]?.jsonArray ?: return emptyList()

        return chaptersData.map { element ->
            val item = element.jsonObject
            SChapter.create().apply {
                // نستخدم مسار خاص للفصول لتمييزها عن السلاسل
                url = "/chapter_api/${item["id"]?.jsonPrimitive?.content}"
                name = "الفصل " + (item["chapterNumber"]?.jsonPrimitive?.content ?: "")
                date_upload = System.currentTimeMillis()
            }
        }
    }

    // ============================== الصفحات والدمج ==============================

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.substringAfterLast("/")
        // جلب الصور من الـ API العام
        return GET("$baseUrl/api/public/chapters/$chapterId/images", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val jsonString = response.body.string()
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)
        val data = jsonObject["data"]?.jsonObject ?: return emptyList()
        val mediaArray = data["media"]?.jsonArray ?: return emptyList()

        return mediaArray.mapIndexed { index, element ->
            val parts = element.jsonObject["p"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            Page(index, "", parts.joinToString("|") + "#stitch_logic")
        }
    }

    private fun imageStitchingInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        if (!url.contains("#stitch_logic")) return chain.proceed(request)

        val partUrls = url.substringBefore("#stitch_logic").split("|")
        val bitmaps = partUrls.mapNotNull {
            val res = chain.proceed(request.newBuilder().url(it).build())
            if (res.isSuccessful) BitmapFactory.decodeStream(res.body.byteStream()) else null
        }

        if (bitmaps.isEmpty()) return chain.proceed(request)

        val width = bitmaps[0].width
        val totalHeight = bitmaps.sumOf { it.height }
        val result = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        var y = 0f
        bitmaps.forEach { 
            canvas.drawBitmap(it, 0f, y, paint)
            y += it.height
            it.recycle() 
        }

        val out = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 85, out)
        result.recycle()

        return chain.proceed(request).newBuilder()
            .body(out.toByteArray().toResponseBody("image/jpeg".toMediaType()))
            .build()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
    override fun getFilterList() = FilterList()
    private fun getPrefBaseUrl(): String = preferences.getString("overrideBaseUrl", "https://procomic.pro")!!
    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}
