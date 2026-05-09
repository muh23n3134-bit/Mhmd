package eu.kanade.tachiyomi.extension.ar.mangapro

import android.widget.Toast
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
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ProChan :
    HttpSource(),
    ConfigurableSource {

    override val name = "ProChan"

    override val lang = "ar"

    override val supportsLatest = true

    override val baseUrl by lazy { getPrefBaseUrl() }

    private val preferences by getPreferencesLazy()

    private val apiBaseUrl = "https://procomic.pro/api/public"

    override val versionId = 6

    private val apiHeaders: Headers by lazy {
        headersBuilder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Origin", baseUrl)
            .add("Referer", "$baseUrl/")
            .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .build()
    }

    override val client = super.client.newBuilder()
        .addInterceptor(::tokenInterceptor)
        .rateLimit(1)
        .build()

    private var storedToken: String? = null

    private fun tokenInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.method == "POST" && request.header("X-CSRF-TOKEN") == null) {
            val newRequest = request.newBuilder()
            val token = getToken()
            val response = chain.proceed(
                newRequest
                    .addHeader("X-CSRF-TOKEN", token)
                    .build(),
            )

            if (response.code == 419) {
                response.close()
                storedToken = null
                val newToken = getToken()
                return chain.proceed(
                    newRequest
                        .addHeader("X-CSRF-TOKEN", newToken)
                        .build(),
                )
            }

            return response
        }

        val response = chain.proceed(request)

        if (response.header("Content-Type")?.contains("text/html") != true) {
            return response
        }

        storedToken = Jsoup.parse(response.peekBody(Long.MAX_VALUE).string())
            .selectFirst("head meta[name*=csrf-token]")
            ?.attr("content")

        return response
    }

    private fun getToken(): String {
        if (storedToken.isNullOrEmpty()) {
            val request = GET(baseUrl, headers)
            client.newCall(request).execute().close()
        }
        return storedToken!!
    }

    private fun String.getMangaId(): String = this.removePrefix("/chapters/").substringBefore("/")

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiBaseUrl/series/search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "latest")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, apiHeaders)
    }

    override fun popularMangaParse(response: Response) = latestUpdatesParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiBaseUrl/series/search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "latest_chapter")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, apiHeaders)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val data = response.parseAs<MetaData<BrowseManga>>()
        val mangas = data.data.map { manga ->
            SManga.create().apply {
                url = manga.slug
                title = manga.title
                thumbnail_url = manga.coverImage
            }
        }
        return MangasPage(mangas, data.meta.hasNextPage())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$apiBaseUrl/series/search".toHttpUrl().newBuilder()
                .addQueryParameter("search", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, apiHeaders)
        }
        return popularMangaRequest(page)
    }

    override fun searchMangaParse(response: Response) = latestUpdatesParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = "$apiBaseUrl/series/".toHttpUrl().newBuilder()
            .addPathSegment(manga.url)
            .build()
        return GET(url, apiHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<Series>().series.let { manga ->
        SManga.create().apply {
            title = manga.title
            description = manga.description
            thumbnail_url = manga.metadata.coverImage
            author = manga.metadata.author.joinToString()
            genre = manga.metadata.genres.joinToString()
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"

    override fun chapterListRequest(manga: SManga): Request {
        val url = "$baseUrl/series/${manga.url}".toHttpUrl()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.extractNextJs<InitialChapters>() ?: return emptyList()
        return data.initialChapters.map { chapter ->
            SChapter.create().apply {
                url = chapter.id.toString()
                name = "الفصل ${chapter.number}"
                date_upload = apiDateFormat.tryParse(chapter.createdAt)
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter/${chapter.url}"

    override fun pageListRequest(chapter: SChapter): Request {
        val url = "$baseUrl/chapter/${chapter.url}".toHttpUrl()
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val imageData = response.body.string().extractNextJsRsc<Images>() ?: return emptyList()
        return imageData.images.mapIndexed { i, url ->
            Page(i, imageUrl = url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used.")

    companion object {
        internal val apiDateFormat by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
        private const val RESTART_APP = "Restart the app to apply the new URL"
        private const val BASE_URL_PREF_TITLE = "Override BaseUrl"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "For temporary uses. Updating the extension will erase this setting."
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val DEFAULT_BASE_URL = "https://procomic.pro"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(DEFAULT_BASE_URL)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $DEFAULT_BASE_URL"

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, DEFAULT_BASE_URL)!!

    init {
        preferences.getString(DEFAULT_BASE_URL_PREF, null).let { prefDefaultBaseUrl ->
            if (prefDefaultBaseUrl != DEFAULT_BASE_URL) {
                preferences.edit()
                    .putString(BASE_URL_PREF, DEFAULT_BASE_URL)
                    .putString(DEFAULT_BASE_URL_PREF, DEFAULT_BASE_URL)
                    .apply()
            }
        }
    }
    }
