package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
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
import kotlinx.serialization.json.JsonArray
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
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayOutputStream

class ProChan : HttpSource(), ConfigurableSource {

    override val name = "ProChan"
    override val lang = "ar"
    override val supportsLatest = true
    override val baseUrl by lazy { getPrefBaseUrl() }
    private val preferences by getPreferencesLazy()
    private val json: Json by injectLazy()

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .addInterceptor(::imageReconstructorInterceptor)
        .build()

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json")

    // ============================== اكتشاف الأعمال عبر الـ API ==============================

    override fun popularMangaRequest(page: Int): Request {
        // نستخدم الـ API المباشر لجلب الأكثر شهرة
        return GET("$baseUrl/api/public/content/series?limit=18&page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonObject = json.decodeFromString<JsonObject>(response.body.string())
        val dataArray = jsonObject["data"]?.jsonArray ?: return MangasPage(emptyList(), false)

        val mangas = dataArray.map { element ->
            val item = element.jsonObject
            val id = item["id"]?.jsonPrimitive?.content ?: ""
            val type = item["type"]?.jsonPrimitive?.content ?: "manhua"
            val slug = item["slug"]?.jsonPrimitive?.content ?: ""
            
            SManga.create().apply {
                // حفظ الرابط بالصيغة الصحيحة للموقع
                url = "/series/$type/$id/$slug"
                title = item["title"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = item["poster"]?.jsonPrimitive?.content ?: ""
            }
        }
        return MangasPage(mangas, mangas.size >= 18)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/api/public/content/latest-updates?limit=18&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/api/public/content/series?search=$query&limit=18&page=$page", headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ============================== تفاصيل العمل والفصول ==============================

    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException("غير مستخدم")

    // تجاوزنا الـ HTML وجلبنا البيانات من القائمة لسرعة الأداء
    override fun fetchMangaDetails(manga: SManga): rx.Observable<SManga> {
        return rx.Observable.just(manga.apply { initialized = true })
    }

    override fun chapterListRequest(manga: SManga): Request {
        // استخراج النوع والآيدي من الرابط /series/type/id/slug
        val parts = manga.url.split("/")
        val type = parts.getOrNull(2) ?: "manhua"
        val id = parts.getOrNull(3) ?: ""
        return GET("$baseUrl/api/public/$type/$id/chapters?limit=1000&order=desc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val jsonObject = json.decodeFromString<JsonObject>(response.body.string())
        val chaptersData = jsonObject["data"]?.jsonArray ?: return emptyList()

        return chaptersData.map { element ->
            val item = element.jsonObject
            SChapter.create().apply {
                url = "/api/public/chapters/${item["id"]?.jsonPrimitive?.content}/images"
                name = "الفصل " + (item["chapterNumber"]?.jsonPrimitive?.content ?: "")
                date_upload = System.currentTimeMillis()
            }
        }
    }

    // ============================== جلب الصور وفك التشفير (Scrambled Images) ==============================

    override fun pageListParse(response: Response): List<Page> {
        val jsonObject = json.decodeFromString<JsonObject>(response.body.string())
        val data = jsonObject["data"]?.jsonObject ?: return emptyList()
        val maps = data["maps"]?.jsonArray ?: return emptyList()

        return maps.mapIndexed { index, mapElement ->
            val map = mapElement.jsonObject
            val pieces = map["pieces"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val order = map["order"]?.jsonArray?.map { it.jsonPrimitive.content.toInt() } ?: emptyList()
            val mode = map["mode"]?.jsonPrimitive?.content ?: "normal"
            
            // نرسل البيانات مدمجة في الرابط ليعالجها الـ Interceptor
            val payload = "${pieces.joinToString("|")}#ORDER#${order.joinToString(",")}$#MODE#$mode"
            Page(index, "", payload)
        }
    }

    private fun imageReconstructorInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        if (!url.contains("#ORDER#")) return chain.proceed(request)

        val pieces = url.substringBefore("#ORDER#").split("|")
        val order = url.substringAfter("#ORDER#").substringBefore("$").split(",").map { it.toInt() }
        val mode = url.substringAfter("#MODE#")

        val bitmaps = pieces.map { pieceUrl ->
            val res = chain.proceed(request.newBuilder().url(pieceUrl).build())
            BitmapFactory.decodeStream(res.body.byteStream())
        }

        if (bitmaps.isEmpty()) return chain.proceed(request)

        val firstBmp = bitmaps[0]
        val resultBmp = Bitmap.createBitmap(bitmaps.sumOf { it.width }, bitmaps.sumOf { it.height }, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBmp)

        // منطق إعادة ترتيب القطع بناءً على الـ Mode والـ Order من الـ API
        if (mode.startsWith("grid")) {
            val cols = 2 // الموقع حالياً يستخدم 2x2 في الغالب
            val rows = 2
            val tileW = firstBmp.width
            val tileH = firstBmp.height
            val gridResult = Bitmap.createBitmap(tileW * cols, tileH * rows, Bitmap.Config.ARGB_8888)
            val gridCanvas = Canvas(gridResult)
            
            order.forEachIndexed { i, pos ->
                val srcBmp = bitmaps[pos]
                val x = (i % cols) * tileW
                val y = (i / cols) * tileH
                gridCanvas.drawBitmap(srcBmp, x.toFloat(), y.toFloat(), null)
            }
            
            val out = ByteArrayOutputStream()
            gridResult.compress(Bitmap.CompressFormat.JPEG, 90, out)
            return chain.proceed(request).newBuilder()
                .body(out.toByteArray().toResponseBody("image/jpeg".toMediaType()))
                .build()
        }

        // دمج طولي بسيط إذا لم يكن هناك تشفير شبكي
        var currentY = 0f
        bitmaps.forEach { canvas.drawBitmap(it, 0f, currentY, null); currentY += it.height }
        
        val out = ByteArrayOutputStream()
        resultBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return chain.proceed(request).newBuilder()
            .body(out.toByteArray().toResponseBody("image/jpeg".toMediaType()))
            .build()
    }

    override fun imageUrlParse(response: Response): String = ""
    override fun getFilterList() = FilterList()
    private fun getPrefBaseUrl(): String = preferences.getString("overrideBaseUrl", "https://procomic.pro")!!
    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}
