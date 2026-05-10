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
        .add("Accept", "application/json, text/plain, */*")

    // ============================== MANGA LISTING (HTML) ==============================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/series?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        val mangaElements = document.select("div.grid a[href*=/series/]")
        
        val mangas = mangaElements.mapNotNull { element ->
            val href = element.attr("href")
            // التحقق من وجود المعرف الرقمي في الرابط لضمان جودة البيانات
            val idMatch = Regex("""/series/\w+/(\d+)""").find(href)
            if (idMatch == null) return@mapNotNull null

            SManga.create().apply {
                url = href.substringAfter(baseUrl)
                title = element.select("h3, h2, p").firstOrNull { it.text().isNotBlank() }?.text()?.trim() ?: "Unknown"
                thumbnail_url = element.select("img").attr("abs:src")
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

    // ============================== MANGA DETAILS ==============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())
        return SManga.create().apply {
            title = document.select("h1").firstOrNull()?.text()?.trim() ?: ""
            description = document.select("p.text-sm.line-clamp-6, div.description p").text().trim()
            thumbnail_url = document.select("img[alt=poster], img.object-cover").firstOrNull()?.attr("abs:src") ?: ""
            genre = document.select("div.flex.wrap a[href*=genres]").joinToString { it.text() }
            status = when {
                document.text().contains("مستمر") -> SManga.ONGOING
                document.text().contains("مكتمل") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    // ============================== CHAPTERS (API DRIVEN) ==============================

    override fun chapterListRequest(manga: SManga): Request {
        // استخراج ID المانجا باستخدام Regex لضمان الدقة حتى لو تغير الرابط
        val mangaId = Regex("""/series/\w+/(\d+)""").find(manga.url)?.groupValues?.get(1)
            ?: throw Exception("Could not parse Manga ID")
        
        return GET("$baseUrl/api/public/manhua/$mangaId/chapters?limit=1000&order=desc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val responseBody = response.body.string()
        val jsonObject = json.decodeFromString<JsonObject>(responseBody)
        val chaptersArray = jsonObject["data"]?.jsonArray ?: return emptyList()

        return chaptersArray.map { element ->
            val chapterObj = element.jsonObject
            val cId = chapterObj["id"]?.jsonPrimitive?.content ?: ""
            val cNum = chapterObj["chapterNumber"]?.jsonPrimitive?.content ?: ""
            
            SChapter.create().apply {
                // نستخدم مساراً فريداً للفصل لتمييزه برمجياً
                url = "/internal_api/chapter/$cId"
                name = "الفصل $cNum"
                date_upload = System.currentTimeMillis()
            }
        }
    }

    // ============================== PAGES (STITCHING LOGIC) ==============================

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.substringAfterLast("/")
        return GET("$baseUrl/api/public/chapters/$chapterId/images", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val responseBody = response.body.string()
        val jsonObject = json.decodeFromString<JsonObject>(responseBody)
        val dataObj = jsonObject["data"]?.jsonObject ?: return emptyList()
        val mediaArray = dataObj["media"]?.jsonArray ?: return emptyList()

        return mediaArray.mapIndexed { index, element ->
            val mediaItem = element.jsonObject
            val partUrls = mediaItem["p"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            
            // دمج الروابط في رابط واحد مع علامة مميزة للمحترض (Interceptor)
            val joinedUrl = partUrls.joinToString("|")
            Page(index, "", "$joinedUrl#unsplit_process")
        }
    }

    // ============================== ADVANCED IMAGE INTERCEPTOR ==============================

    private fun imageStitchingInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val urlString = request.url.toString()

        if (!urlString.contains("#unsplit_process")) return chain.proceed(request)

        // تنظيف الرابط وفصل القطع
        val cleanUrls = urlString.substringBefore("#unsplit_process").split("|")
        
        val bitmaps = cleanUrls.mapNotNull { partUrl ->
            val partRequest = request.newBuilder().url(partUrl).build()
            val partResponse = chain.proceed(partRequest)
            if (partResponse.isSuccessful) {
                BitmapFactory.decodeStream(partResponse.body.byteStream())
            } else null
        }

        if (bitmaps.isEmpty()) throw Exception("Failed to load image parts")

        // حساب الأبعاد النهائية
        val maxWidth = bitmaps.maxOf { it.width }
        val totalHeight = bitmaps.sumOf { it.height }

        // إنشاء الصورة المدمجة
        val resultBitmap = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        var currentY = 0f
        for (bitmap in bitmaps) {
            canvas.drawBitmap(bitmap, 0f, currentY, paint)
            currentY += bitmap.height
            bitmap.recycle() // تنظيف الذاكرة فوراً
        }

        val outputStream = ByteArrayOutputStream()
        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val responseData = outputStream.toByteArray()
        resultBitmap.recycle()

        return chain.proceed(request).newBuilder()
            .body(responseData.toResponseBody("image/jpeg".toMediaType()))
            .code(200)
            .message("OK")
            .build()
    }

    // ============================== CONFIGURATION & UTILS ==============================

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList()

    private fun getPrefBaseUrl(): String = preferences.getString("overrideBaseUrl", "https://procomic.pro")!!

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = "overrideBaseUrl"
            title = "Base URL"
            setDefaultValue("https://procomic.pro")
            summary = "تغيير الرابط الأساسي في حال تعطل الموقع"
        }
        screen.addPreference(baseUrlPref)
    }
}
