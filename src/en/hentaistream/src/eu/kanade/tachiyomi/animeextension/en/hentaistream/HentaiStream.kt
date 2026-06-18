package eu.kanade.tachiyomi.animeextension.en.hentaistream

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class HentaiStream : ParsedAnimeHttpSource() {

    override val name = "HentaiStream"

    override val baseUrl = "https://tube.hentaistream.com"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", baseUrl)
        .set("Connection", "close")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page"

        Log.e(TAG, "popularAnimeRequest page=$page url=$url")
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = latestUpdatesParse(response)

    override fun popularAnimeSelector(): String = latestUpdatesSelector()

    override fun popularAnimeFromElement(element: Element): SAnime = latestUpdatesFromElement(element)

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page"

        Log.e(TAG, "latestUpdatesRequest page=$page url=$url")
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        Log.e(TAG, "latestUpdatesParse responseUrl=${response.request.url} code=${response.code}")
        val document = response.asJsoup()
        val animeList = document
            .select(latestUpdatesSelector())
            .map(::latestUpdatesFromElement)
            .distinctBy { it.url }

        return AnimesPage(animeList, document.hasNextPage())
    }

    override fun latestUpdatesSelector(): String = "#lastesteps_id > div.post"

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        val titleElement = element.selectFirst("p.posttitle a")!!
        val episodeTitle = titleElement.text()

        setUrlWithoutDomain(episodeTitle.toSeriesUrl(titleElement.absUrl("href")))
        title = episodeTitle.substringBeforeEpisode()
        thumbnail_url = element.selectFirst("div.postimg img")?.absUrl("src")
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = (if (page == 1) baseUrl else "$baseUrl/page/$page").toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .build()

        Log.e(TAG, "searchAnimeRequest page=$page query='$query' url=$url")

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        Log.e(TAG, "searchAnimeParse responseUrl=${response.request.url} code=${response.code}")

        val document = response.asJsoup()
        val elements = document.select(searchAnimeSelector())
        Log.e(TAG, "searchAnimeParse selector='${searchAnimeSelector()}' matched=${elements.size}")

        val animeList = elements
            .mapNotNull(::searchAnimeFromElementOrNull)
            .distinctBy { it.url }

        Log.e(TAG, "searchAnimeParse finalCount=${animeList.size}")

        return AnimesPage(animeList, document.hasNextPage())
    }

    override fun searchAnimeSelector(): String = "div.post"

    override fun searchAnimeFromElement(element: Element): SAnime {
        return searchAnimeFromElementOrNull(element) ?: SAnime.create()
    }

    override fun searchAnimeNextPageSelector(): String? = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        Log.e(TAG, "animeDetailsParse location=${document.location()} title='${document.title()}'")
        title = document.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.removePrefix("Watch All ")
            ?.removePrefix("Watch ")
            ?.removeSuffix(" Episodes")
            ?: document.title().substringBefore(" Free Hentai")
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        description = document.selectFirst("meta[property=og:description]")?.attr("content")
        genre = document.select("#cat_genres a, p.posttags a[rel=tag]").eachText().distinct().joinToString()
        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        Log.e(TAG, "episodeListParse url=${response.request.url} code=${response.code}")
        val document = response.asJsoup()
        val episodeElements = document.select("div.post")
            .filter { it.selectFirst("p.posttitle a[href]") != null }
        if (episodeElements.isNotEmpty()) {
            return episodeElements
                .map(::episodeFromElement)
                .sortedBy { it.episode_number }
        }

        val title = document.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.removePrefix("Watch ")
            ?: document.title().substringBefore(" Free Hentai")

        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = title
                episode_number = EPISODE_REGEX.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: 1F
                date_upload = document.getUploadDate()
            },
        )
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val titleElement = element.selectFirst("p.posttitle a")!!
        val title = titleElement.text()

        setUrlWithoutDomain(titleElement.attr("href"))
        name = title
        episode_number = EPISODE_REGEX.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: 1F
        date_upload = element.selectFirst("p.dtcreated")?.text()?.parseDateAdded() ?: 0L
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        Log.e(TAG, "videoListParse url=${response.request.url} code=${response.code}")
        val document = response.asJsoup()
        val frameUrl = document.selectFirst("iframe[src*=/frames/]")?.absUrl("src") ?: return emptyList()
        val frameHeaders = headers.newBuilder()
            .set("Referer", response.request.url.toString())
            .build()
        val frameDocument = client.newCall(GET(frameUrl, frameHeaders)).execute().asJsoup()
        val videoUrl = frameDocument.selectFirst("video source[src]")?.absUrl("src") ?: return emptyList()

        return listOf(Video(videoUrl, "HentaiStream", videoUrl, headers = frameHeaders))
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    private fun Document.getUploadDate(): Long {
        val uploadDate = select("script[type=application/ld+json]")
            .firstNotNullOfOrNull { JSON_UPLOAD_DATE_REGEX.find(it.data())?.groupValues?.get(1) }
            ?: return 0L

        return runCatching { DATE_FORMAT.parse(uploadDate)?.time }.getOrNull() ?: 0L
    }

    private fun Document.hasNextPage(): Boolean {
        return select("#wp_page_numbers a").any { it.text() == ">" }
    }

    private fun String.substringBeforeEpisode(): String = substringBeforeLast(" Episode").ifBlank { this }

    private fun String.toSeriesUrl(episodeUrl: String): String {
        val episodeSlug = episodeUrl.substringAfter(baseUrl).trim('/').substringBefore('?')
        val seriesSlug = episodeSlug.replace(Regex("-episode-\\d+$", RegexOption.IGNORE_CASE), "")
        return "/hentaidvd/$seriesSlug"
    }

    private fun String.parseDateAdded(): Long {
        val date = removePrefix("Added:").trim()
        return DATE_ADDED_FORMATS.firstNotNullOfOrNull { format ->
            runCatching { format.parse(date)?.time }.getOrNull()
        } ?: 0L
    }

    private fun searchAnimeFromElementOrNull(element: Element): SAnime? {
        val titleElement = element.selectFirst("p.posttitle a[href]")
            ?: return null

        return SAnime.create().apply {
            setUrlWithoutDomain(titleElement.attr("href"))
            title = titleElement.text()
            thumbnail_url = element.selectFirst("div.postimg img")?.absUrl("src")
        }
    }

    companion object {
        private const val TAG = "HentaiStreamDebug"
        private val EPISODE_REGEX = Regex("Episode\\s+(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        private val JSON_UPLOAD_DATE_REGEX = Regex("\"uploadDate\"\\s*:\\s*\"([^\"]+)\"")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        private val DATE_ADDED_FORMATS = listOf(
            SimpleDateFormat("MMM d'th', yyyy", Locale.US),
            SimpleDateFormat("MMM d'st', yyyy", Locale.US),
            SimpleDateFormat("MMM d'nd', yyyy", Locale.US),
            SimpleDateFormat("MMM d'rd', yyyy", Locale.US),
        )
    }
}
