package eu.kanade.tachiyomi.animeextension.en.hentaigasm

import android.net.Uri
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class Hentaigasm : ParsedAnimeHttpSource() {

    override val name = "Hentaigasm"

    override val baseUrl = "https://hentaigasm.com"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/?orderby=views"
        } else {
            "$baseUrl/page/$page/?orderby=views"
        }
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = LISTING_ITEM_SELECTOR

    override fun popularAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    override fun popularAnimeNextPageSelector(): String = NEXT_PAGE_SELECTOR

    override fun popularAnimeParse(response: Response): AnimesPage = listingParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/hentai/$LATEST_CATEGORY/"
        } else {
            "$baseUrl/hentai/$LATEST_CATEGORY/page/$page/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String = LISTING_ITEM_SELECTOR

    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = NEXT_PAGE_SELECTOR

    override fun latestUpdatesParse(response: Response): AnimesPage = listingParse(response)

    // =============================== Search ===============================

    override fun searchAnimeSelector(): String = LISTING_ITEM_SELECTOR

    override fun searchAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = NEXT_PAGE_SELECTOR

    override fun searchAnimeParse(response: Response): AnimesPage = listingParse(response)

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return latestUpdatesRequest(page)

        val encoded = URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
        val url = if (page == 1) {
            "$baseUrl/?s=$encoded"
        } else {
            "$baseUrl/page/$page/?s=$encoded"
        }
        return GET(url, headers)
    }

    // ================================ Shared listing parsing ================================

    private fun listingParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(LISTING_ITEM_SELECTOR)
            .mapNotNull { element ->
                runCatching { animeFromElement(element) }.getOrNull()
            }
            .filter { it.url.isNotBlank() }
            // The site lists individual episodes, so several entries can point to the same
            // series. Keep one entry per series (matters most for search results).
            .distinctBy { it.url }

        return AnimesPage(animes, document.selectFirst(NEXT_PAGE_SELECTOR) != null)
    }

    private fun animeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("div.data h2.title a")
            ?: element.selectFirst("h2.title a")
            ?: element.selectFirst("div.thumb a.clip-link")

        val postUrl = link?.attr("href").orEmpty()
        val seriesUrl = seriesPathFromElement(element, postUrl)
        setUrlWithoutDomain(seriesUrl)
        title = titleFromSlug(seriesUrl.trimEnd('/').substringAfterLast('/'))
        thumbnail_url = element.selectFirst("div.thumb img")?.let { image ->
            image.attr("data-src").ifBlank { image.attr("src") }
        }?.ifBlank { null }
    }

    /**
     * Episode items expose their categories as `category-*` classes. The most specific one is
     * usually last (the first may be a generic category like `new-hentai-release` or a studio).
     * When there is no usable category, fall back to the episode page itself, which then acts as
     * a single-episode series.
     */
    private fun seriesPathFromElement(element: Element, postUrl: String): String {
        val categories = element.attr("class")
            .split(' ')
            .filter { it.startsWith("category-") }
            .map { it.removePrefix("category-") }
            .filterNot { it in GENERIC_CATEGORIES }

        val slug = categories.lastOrNull() ?: return postUrl.toPath()
        return "/hentai/$slug/"
    }

    private fun String.toPath(): String = substringAfter(baseUrl, this).ifBlank { this }

    // ================================ Details ================================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        if (document.selectFirst("div#details") != null) {
            parsePostDetails(document, this)
        } else {
            parseSeriesDetails(document, this)
        }
    }

    private fun parseSeriesDetails(document: Document, anime: SAnime) {
        val loopTitle = document.selectFirst("h1.loop-title em")?.text()
            ?: document.selectFirst("h1.loop-title")?.text()
        anime.title = loopTitle?.removeSuffix(" Hentai Episodes")?.trim()
            ?: document.selectFirst("h1#site-title a")?.text().orEmpty()

        val episodes = document.select(LISTING_ITEM_SELECTOR)
        anime.thumbnail_url = episodes.firstOrNull()
            ?.selectFirst("div.thumb img")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.ifBlank { null }

        anime.genre = episodes
            .flatMap { it.attr("class").split(' ') }
            .filter { it.startsWith("tag-") }
            .map { titleFromSlug(it.removePrefix("tag-")) }
            .distinct()
            .joinToString(", ")

        anime.description = document.selectFirst("div.loop-desc")?.text()?.ifBlank { null }
            ?: episodes.firstOrNull()?.selectFirst("p.desc")?.text()?.cleanDescription()
        anime.status = SAnime.COMPLETED
    }

    private fun parsePostDetails(document: Document, anime: SAnime) {
        anime.title = document.selectFirst("h1#title")?.text()
            ?: document.title().substringBefore(" New Hentai")

        anime.thumbnail_url = document.selectFirst("link[rel=preload][as=image]")?.attr("href")
            ?.ifBlank { null }
            ?: document.selectFirst("div.entry-content img")?.absUrl("src")

        anime.description = document.select("div.entry-content p")
            .map { it.text().cleanDescription() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n\n")
            .ifBlank { null }

        anime.genre = document.select("#extras a[href*=/genre/]")
            .map { it.text() }
            .distinct()
            .joinToString(", ")

        anime.status = SAnime.COMPLETED
    }

    // =============================== Episodes ===============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val items = document.select(LISTING_ITEM_SELECTOR)

        if (items.isEmpty()) {
            val title = document.selectFirst("h1#title")?.text()
                ?: document.title().substringBefore(" New Hentai")
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(response.request.url.toString().toPath())
                    name = title
                    episode_number = episodeNumber(title) ?: 1F
                    date_upload = parseDate(document.selectFirst("div#extras h4")?.text())
                },
            )
        }

        return items.mapNotNull { element ->
            runCatching { episodeFromElement(element) }.getOrNull()
        }
            .distinctBy { it.url }
            .sortedBy { it.episode_number }
    }

    override fun episodeListSelector(): String = LISTING_ITEM_SELECTOR

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val link = element.selectFirst("div.data h2.title a")
            ?: element.selectFirst("h2.title a")
            ?: element.selectFirst("div.thumb a.clip-link")
            ?: throw Exception("No episode link")

        name = link.text()
        val href = link.attr("href")
        setUrlWithoutDomain(href.toPath())
        episode_number = episodeNumber(name)
            ?: URL_EPISODE_REGEX.find(href)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: 1F
        date_upload = parseRelativeDate(element.selectFirst("span.time")?.text())
    }

    private fun episodeNumber(title: String): Float? = NUMBER_REGEX.findAll(title)
        .lastOrNull()
        ?.groupValues
        ?.getOrNull(1)
        ?.toFloatOrNull()

    // =============================== Video ===============================

    override fun videoListParse(response: Response): List<Video> {
        val pageUrl = response.request.url.toString()
        val html = response.body.string()
        val document = Jsoup.parse(html, pageUrl)
        val videoHeaders = headers.newBuilder()
            .set("Referer", pageUrl)
            .build()

        val candidates = mutableListOf<String>()

        // Main stream, defined inline for the JW Player instance.
        FILE_REGEX.findAll(html).forEach { candidates.add(it.groupValues[1]) }

        // Download button and any direct sources in the page.
        document.select("a[download][href], a[href$=.mp4], source[src$=.mp4], video[src$=.mp4]")
            .forEach { element ->
                val url = element.absUrl("href").ifBlank {
                    element.absUrl("src").ifBlank { element.attr("href") }
                }
                if (url.isNotBlank()) candidates.add(url)
            }

        val videos = candidates
            .asSequence()
            .map { it.trim() }
            .filter { it.startsWith("http") }
            .map { it.normalizeUrl() }
            .distinct()
            .mapIndexed { index, url ->
                val label = when {
                    index == 0 -> "Hentaigasm"
                    url.contains("hgasm3") -> "Hentaigasm (Download)"
                    else -> "Hentaigasm ${index + 1}"
                }
                Video(url, label, url, headers = videoHeaders)
            }
            .toMutableList()

        if (videos.isEmpty()) {
            val iframeUrl = document.selectFirst("div.entry-content iframe[src]")?.absUrl("src")
                ?: document.selectFirst("iframe[src]")?.absUrl("src")
            if (!iframeUrl.isNullOrBlank()) {
                runCatching {
                    client.newCall(GET(iframeUrl, videoHeaders)).execute().use { iframeResponse ->
                        val iframeHtml = iframeResponse.body.string()
                        val iframeDoc = Jsoup.parse(iframeHtml, iframeUrl)
                        val urls = (
                            FILE_REGEX.findAll(iframeHtml).map { it.groupValues[1] } +
                                MP4_REGEX.findAll(iframeHtml).map { it.value } +
                                iframeDoc.select("source[src$=.mp4], video[src$=.mp4]")
                                    .map { it.absUrl("src") }
                            )
                            .filter { it.startsWith("http") }
                            .map { it.normalizeUrl() }
                            .distinct()

                        urls.forEachIndexed { index, url ->
                            videos.add(
                                Video(
                                    url,
                                    if (index == 0) "Hentaigasm" else "Hentaigasm ${index + 1}",
                                    url,
                                    headers = videoHeaders,
                                ),
                            )
                        }
                    }
                }
            }
        }

        return videos
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // =============================== Helpers ===============================

    private fun String.normalizeUrl(): String {
        val schemeEnd = indexOf("://")
        if (schemeEnd < 0) return this
        val pathStart = indexOf('/', schemeEnd + 3)
        if (pathStart < 0) return this
        return substring(0, pathStart) + Uri.encode(substring(pathStart), "/")
    }

    private fun String.cleanDescription(): String = replace(SHORTCODE_REGEX, "").trim()

    private fun titleFromSlug(slug: String): String = slug
        .split('-')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            when (word.lowercase()) {
                "3d" -> "3D"
                "2d" -> "2D"
                "vr" -> "VR"
                else -> word.replaceFirstChar { it.uppercase() }
            }
        }

    private fun parseDate(text: String?): Long {
        val value = text?.trim() ?: return 0L
        if (value.isEmpty()) return 0L
        runCatching { ABSOLUTE_DATE_FORMAT.parse(value)?.time }.getOrNull()?.let { return it }
        return parseRelativeDate(value)
    }

    private fun parseRelativeDate(text: String?): Long {
        val value = text?.trim()?.lowercase() ?: return 0L
        if (value.isEmpty()) return 0L
        if (value == "just now") return System.currentTimeMillis()

        val match = RELATIVE_DATE_REGEX.find(value) ?: return 0L
        val amount = match.groupValues[1].toLongOrNull() ?: return 0L
        val unitMillis = when (match.groupValues[2]) {
            "minute" -> 60_000L
            "hour" -> 3_600_000L
            "day" -> 86_400_000L
            "week" -> 604_800_000L
            "month" -> 2_592_000_000L
            "year" -> 31_536_000_000L
            else -> return 0L
        }
        return System.currentTimeMillis() - (amount * unitMillis)
    }

    companion object {
        private const val LATEST_CATEGORY = "new-hentai-release"
        private const val LISTING_ITEM_SELECTOR = "div.loop-content div.item-post"
        private const val NEXT_PAGE_SELECTOR = "a.nextpostslink"

        // Categories used by the site for site-wide feeds rather than a specific series.
        private val GENERIC_CATEGORIES = setOf("new-hentai-release")

        private val FILE_REGEX = Regex("""file\s*:\s*["']([^"']+)["']""")
        private val MP4_REGEX = Regex("""https?://[^\s"'<>]+\.mp4""")
        private val SHORTCODE_REGEX = Regex("""\[[a-z]+]""", RegexOption.IGNORE_CASE)
        private val NUMBER_REGEX = Regex("""(?:^|\s)(\d+(?:\.\d+)?)(?=\s|$)""")
        private val URL_EPISODE_REGEX = Regex("""-(\d+(?:\.\d+)?)-(?:subbed|raw|dubbed)""", RegexOption.IGNORE_CASE)
        private val RELATIVE_DATE_REGEX = Regex("""(\d+)\s+(minute|hour|day|week|month|year)s?""")
        private val ABSOLUTE_DATE_FORMAT = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
    }
}
