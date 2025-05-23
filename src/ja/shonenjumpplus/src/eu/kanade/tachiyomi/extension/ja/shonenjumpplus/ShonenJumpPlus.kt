package eu.kanade.tachiyomi.extension.ja.shonenjumpplus

import android.content.SharedPreferences
import android.widget.Toast
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.*
import java.util.UUID
import java.util.concurrent.TimeUnit

class ShonenJumpPlus : ConfigurableSource, HttpSource() {
    override val name = "Shonen Jump+"
    override val baseUrl = "https://shonenjumpplus.com"
    private val apiBase = "$baseUrl/api/v1"
    override val lang = "ja"
    override val supportsLatest = true

    private val deviceId = UUID.randomUUID().toString().substring(0, 16)
    private var bearerToken: String? = null
    private var userAccountId: String? = null
    private var tokenExpiry: Long = 0

    private val json = Json { ignoreUnknownKeys = true }

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.url.toString().contains("cdn-ak-img")) {
            return@Interceptor chain.proceed(request)
        }

        if (bearerToken == null || System.currentTimeMillis() > tokenExpiry) {
            if (!fetchBearerToken()) {
                return@Interceptor chain.proceed(request)
            }
        }

        chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $bearerToken")
                .build(),
        )
    }

    private val preferences: SharedPreferences by lazy {
        getPreferencesLazy().value
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(authInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val bareClient: OkHttpClient = network.client.newBuilder()
        .build()

    private fun fetchBearerToken(): Boolean {
        val url = "$apiBase/user_account/access_token".toHttpUrl()
        val request = Request.Builder()
            .url(url)
            .headers(baseHeaders)
            .post("".toRequestBody())
            .build()

        try {
            val response = bareClient.newCall(request).execute()
            val bodyString = response.body.string()

            if (response.isSuccessful) {
                val jsonResponse = json.parseToJsonElement(bodyString).jsonObject
                bearerToken = jsonResponse["access_token"]?.jsonPrimitive?.content
                userAccountId = jsonResponse["user_account_id"]?.jsonPrimitive?.content
                tokenExpiry = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)
                return true
            } else {
                return false
            }
        } catch (e: Exception) {
            return false
        }
    }

    private val baseHeaders by lazy {
        Headers.Builder()
            .add("Origin", baseUrl)
            .add("Referer", baseUrl)
            .add("X-Giga-Device-Id", deviceId)
            .add("User-Agent", "ShonenJumpPlus-Android/4.0.18")
            .build()
    }

    override fun headersBuilder(): Headers.Builder = baseHeaders.newBuilder()

    // Popular Manga
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/series", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }
        return MangasPage(mangas, false)
    }

    private fun popularMangaSelector(): String = "ul.series-list li a"

    private fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst("h2.series-list-title")!!.text()
        thumbnail_url = element.selectFirst("div.series-list-thumb img")!!
            .attr("data-src")
        url = element.attr("href").substringAfter(baseUrl)
    }

    // Latest Updates
    private val dayOfWeek: String by lazy {
        Calendar.getInstance()
            .getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US)!!
            .lowercase(Locale.US)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(latestUpdatesSelector()).map { element ->
            popularMangaFromElement(element)
        }
        return MangasPage(mangas, false)
    }

    private fun latestUpdatesSelector(): String =
        "h2.series-list-date-week.$dayOfWeek + ul.series-list li a"

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        return if (query.isNotEmpty()) {
            client.newCall(searchMangaRequest(page, query, filters))
                .asObservable()
                .map(::searchMangaParse)
        } else {
            val collectionPath = (filters[0] as? CollectionFilter)?.selected?.path ?: ""
            val path = if (collectionPath.isBlank()) "" else "/$collectionPath"
            client.newCall(GET("$baseUrl/series$path", headers))
                .asObservable()
                .map(::popularMangaParse)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(searchMangaSelector()).map { element ->
            searchMangaFromElement(element)
        }
        return MangasPage(mangas, false)
    }

    private fun searchMangaSelector(): String = "ul.search-series-list li, ul.series-list li"

    private fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst("div.title-box p.series-title")!!.text()
        thumbnail_url = element.selectFirst("div.thmb-container a img")!!.attr("src")
        url = element.selectFirst("div.thmb-container a")!!.attr("href").substringAfter(baseUrl)
    }

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        val infoElement = document.selectFirst("section.series-information div.series-header")!!

        title = infoElement.selectFirst("h1.series-header-title")!!.text()
        author = infoElement.selectFirst("h2.series-header-author")!!.text()
        artist = author
        description = infoElement.selectFirst("p.series-header-description")!!.text()
        thumbnail_url = infoElement.selectFirst("div.series-header-image-wrapper img")!!
            .attr("data-src")
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return client.newCall(chapterListRequest(manga))
            .asObservable()
            .map(::chapterListParse)
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    @Serializable
    private data class GraphQLResponse(
        val data: Data? = null,
    )

    @Serializable
    private data class Data(
        val series: Series? = null,
    )

    @Serializable
    private data class Series(
        val episodes: Episodes? = null,
    )

    @Serializable
    private data class Episodes(
        val edges: List<Edge> = emptyList(),
    )

    @Serializable
    private data class Edge(
        val node: Node? = null,
    )

    @Serializable
    private data class Node(
        @SerialName("databaseId") val id: String? = null,
        val title: String? = null,
        val publishedAt: String? = null,
    )

    // Chapter List
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val seriesId = document.selectFirst("script.js-valve")
            ?.attr("data-giga_series")
            ?: throw Exception("Missing series ID in manga page")

        val operationName = "SeriesDetailEpisodeList"
        val query = """
        query SeriesDetailEpisodeList(
            ${'$'}id: String!
            ${'$'}episodeSort: ReadableProductSorting = "NUMBER_DESC"
        ) {
            series(databaseId: ${'$'}id) {
                episodes: readableProducts(types: [EPISODE], first: 1500, sort: ${'$'}episodeSort) {
                    edges {
                        node {
                            databaseId
                            title
                            publishedAt
                        }
                    }
                }
            }
        }
        """.trimIndent()

        val variables = buildJsonObject {
            put("id", seriesId)
        }

        val payload = buildJsonObject {
            put("operationName", operationName)
            put("variables", variables)
            put("query", query)
        }

        val request = Request.Builder()
            .url("$apiBase/graphql?opname=$operationName")
            .headers(graphQLHeaders(operationName))
            .post(json.encodeToString(payload).toRequestBody())
            .build()

        return try {
            val responseBody = client.newCall(request).execute().body.string()
            val result = json.decodeFromString<GraphQLResponse>(responseBody)

            result.data?.series?.episodes?.edges
                ?.mapNotNull { edge ->
                    edge.node?.let { node ->
                        SChapter.create().apply {
                            name = node.title ?: ""
                            date_upload = node.publishedAt?.toDate() ?: 0L
                            url = "/episode/${node.id}"
                        }
                    }
                } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.fromCallable {
            val response = client.newCall(pageListRequest(chapter)).execute()
            val responseBody = response.body.string()
            response.close()

            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            val episodeData = jsonResponse["data"]?.jsonObject?.get("episode")?.jsonObject
                ?: throw Exception("Invalid episode data")

            val isFree = episodeData["purchaseInfo"]?.jsonObject
                ?.get("purchasableViaOnetimeFree")?.jsonPrimitive?.booleanOrNull ?: false

            if (isFree) {
                val episodeId = episodeData["id"]?.jsonPrimitive?.content
                    ?: throw Exception("Missing episode ID")

                if (!consumeOnetimeFree(episodeId)) {
                    throw Exception("Failed to consume free chapter")
                }

                val newResponse = client.newCall(pageListRequest(chapter)).execute()
                val newResponseBody = newResponse.body.string()
                newResponse.close()
                pageListParseFromString(newResponseBody)
            } else {
                pageListParseFromString(responseBody)
            }
        }.onErrorResumeNext { e ->
            Observable.error(Exception("Error fetching pages: ${e.message}"))
        }
    }

    private fun pageListParseFromString(responseBody: String): List<Page> {
        val result = json.decodeFromString<PageImageResponse>(responseBody)
        val pageImages = result.data?.episode?.pageImages?.edges?.mapNotNull { it.node?.src }
            ?: throw Exception("No page images found")
        val token = result.data.episode.token ?: throw Exception("Missing image auth token")
        return pageImages.mapIndexed { index, url -> Page(index, "", "$url?token=$token") }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val episodeId = chapter.url.substringAfterLast("/")

        val operationName = "EpisodeViewerConditionallyCacheable"
        val query = """
    query EpisodeViewerConditionallyCacheable(${'$'}episodeID: String!) {
        episode(databaseId: ${'$'}episodeID) {
            id
            pageImages {
                edges {
                    node {
                        src
                    }
                }
            }
            pageImageToken
            purchaseInfo {
                __typename
                purchasableViaOnetimeFree
            }
        }
    }
        """.trimIndent()

        val variables = buildJsonObject {
            put("episodeID", episodeId)
        }

        val payload = buildJsonObject {
            put("operationName", operationName)
            put("variables", variables)
            put("query", query)
        }

        return Request.Builder()
            .url("$apiBase/graphql?opname=$operationName")
            .headers(graphQLHeaders(operationName))
            .post(json.encodeToString(payload).toRequestBody())
            .build()
    }

    @Serializable
    private data class PageImageResponse(
        val data: PageImageData? = null,
    )

    @Serializable
    private data class PageImageData(
        val episode: EpisodeData? = null,
    )

    @Serializable
    private data class EpisodeData(
        val pageImages: PageImages? = null,
        val databaseId: String? = null,
        @SerialName("pageImageToken") val token: String? = null,
        val purchaseInfo: PurchaseInfo? = null,
    )

    @Serializable
    private data class PurchaseInfo(
        val purchasableViaOnetimeFree: Boolean? = null,
    )

    @Serializable
    private data class PageImages(
        val edges: List<PageImageEdge> = emptyList(),
    )

    @Serializable
    private data class PageImageEdge(
        val node: PageImageNode? = null,
    )

    @Serializable
    private data class PageImageNode(
        val src: String? = null,
    )

    override fun pageListParse(response: Response): List<Page> {
        val responseBody = response.body.string()
        val result = json.decodeFromString<PageImageResponse>(responseBody)

        val pageImages = result.data?.episode?.pageImages?.edges
            ?.mapNotNull { it.node?.src }
            ?: throw Exception("Failed to get page images")

        val token = result.data.episode.token
            ?: throw Exception("Failed to get image auth token")

        return pageImages.mapIndexed { index, imageUrl ->
            Page(index, "", "$imageUrl?token=$token")
        }
    }

    private fun consumeOnetimeFree(episodeId: String): Boolean {
        val operationName = "ConsumeOnetimeFree"
        val query = """
mutation ConsumeOnetimeFree(${'$'}input: ConsumeOnetimeFreeInput!) {
    consumeOnetimeFree(input: ${'$'}input) {
        isSuccess
        readableProduct {
            databaseId
            id
            accessibility
            purchaseInfo {
                __typename
                ...PurchaseInfo
            }
        }
    }
}
fragment PurchaseInfo on PurchaseInfo {
    isFree
    hasPurchased
    hasPurchasedViaTicket
    purchasable
    purchasableViaTicket
    purchasableViaPaidPoint
    purchasableViaOnetimeFree
    unitPrice
    rentable
    rentalEndAt
    hasRented
    rentableByPaidPointOnly
    rentalTermMin
}
        """.trimIndent()

        val variables = buildJsonObject {
            put(
                "input",
                buildJsonObject {
                    put("id", episodeId)
                },
            )
        }

        val payload = buildJsonObject {
            put("operationName", operationName)
            put("variables", variables)
            put("query", query)
        }

        val request = Request.Builder()
            .url("$apiBase/graphql?opname=$operationName")
            .headers(graphQLHeaders(operationName))
            .post(json.encodeToString(payload).toRequestBody())
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonResponse = json.parseToJsonElement(response.body.string()).jsonObject
                jsonResponse["data"]?.jsonObject
                    ?.get("consumeOnetimeFree")?.jsonObject
                    ?.get("isSuccess")?.jsonPrimitive?.booleanOrNull == true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun graphQLHeaders(operationName: String): Headers {
        return headers.newBuilder()
            .set("Accept", "application/json")
            .set("X-APOLLO-OPERATION-NAME", operationName)
            .set("Content-Type", "application/json")
            .build()
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request {
        val originUrl = page.imageUrl!!.toHttpUrlOrNull() ?: throw Exception("Invalid image URL")
        val token = originUrl.queryParameter("token")

        val upscaleEnabled = preferences.getBoolean(UPSCALE_API_ENABLED_PREF, UPSCALE_API_ENABLED_PREF_DEFAULT)
        val apiTemplate = preferences.getString(UPSCALE_API_URL_PREF, UPSCALE_API_URL_DEFAULT) ?: UPSCALE_API_URL_DEFAULT

        val finalUrl = if (upscaleEnabled && apiTemplate.contains("{url}")) {
            apiTemplate.replace("{url}", originUrl.toString())
        } else {
            originUrl.toString()
        }

        val newHeaders = headers.newBuilder().apply {
            if (token != null) {
                add("X-Giga-Page-Image-Auth", token)
            }
        }.build()

        return GET(finalUrl, newHeaders)
    }

    override fun getFilterList(): FilterList = FilterList(
        CollectionFilter(
            listOf(
                Collection("ジャンプ＋連載一覧", ""),
                Collection("ジャンプ＋読切シリーズ", "oneshot"),
                Collection("連載終了作品", "finished"),
            ),
        ),
    )

    data class Collection(val name: String, val path: String) {
        override fun toString(): String = name
    }

    private class CollectionFilter(val collections: List<Collection>) :
        Filter.Select<Collection>("コレクション", collections.toTypedArray()) {
        val selected: Collection get() = collections[state]
    }

    private fun String.toDate(): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .parse(this)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // Preferences
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val upscaleApiPreference =
            androidx.preference.SwitchPreferenceCompat(screen.context).apply {
                key = UPSCALE_API_ENABLED_PREF
                title = UPSCALE_API_ENABLED_PREF_TITLE
                summary = UPSCALE_API_ENABLED_PREF_SUMMARY
                setDefaultValue(UPSCALE_API_ENABLED_PREF_DEFAULT)
                setOnPreferenceChangeListener { _, _ ->
                    Toast.makeText(screen.context, TOAST_RESTART, Toast.LENGTH_LONG).show()
                    true
                }
            }
        val upscaleUrlPreference = androidx.preference.EditTextPreference(screen.context).apply {
            key = UPSCALE_API_URL_PREF
            title = UPSCALE_API_URL_TITLE
            summary = UPSCALE_API_URL_SUMMARY
            dialogTitle = UPSCALE_API_URL_TITLE
            setDefaultValue(UPSCALE_API_URL_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue as String
                when {
                    newUrl.isEmpty() -> {
                        Toast.makeText(screen.context, "API URL cannot be empty", Toast.LENGTH_SHORT).show()
                        false
                    }
                    !newUrl.contains("{url}") -> {
                        Toast.makeText(
                            screen.context,
                            "Must include {url} parameter placeholder",
                            Toast.LENGTH_SHORT,
                        ).show()
                        false
                    }
                    else -> {
                        Toast.makeText(screen.context, TOAST_RESTART, Toast.LENGTH_LONG).show()
                        true
                    }
                }
            }
        }
        screen.addPreference(upscaleApiPreference)
        screen.addPreference(upscaleUrlPreference)
    }

    companion object {
        private const val TOAST_RESTART = "Please restart Tachiyomi to apply changes"

        private const val UPSCALE_API_ENABLED_PREF = "upscaleApiEnabled"
        private const val UPSCALE_API_ENABLED_PREF_DEFAULT = false
        private const val UPSCALE_API_ENABLED_PREF_TITLE = "Enable Upscaling"
        private const val UPSCALE_API_ENABLED_PREF_SUMMARY =
            "Use upscale API to improve image quality (experimental)"

        private const val UPSCALE_API_URL_PREF = "upscaleApiUrlPreference"
        private const val UPSCALE_API_URL_DEFAULT =
            "https://api.example.com/upscale/?model=auto&format=webp&url={url}"
        private const val UPSCALE_API_URL_TITLE = "Upscale API URL"
        private const val UPSCALE_API_URL_SUMMARY =
            "Format: https://api.example.com/upscale/?model=auto&format=webp&url={url}\nMust include {url} parameter placeholder"
    }
}
