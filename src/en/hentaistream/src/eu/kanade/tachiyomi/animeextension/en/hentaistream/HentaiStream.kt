package eu.kanade.tachiyomi.animeextension.en.hentaistream

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
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

        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = latestUpdatesParse(response)

    override fun popularAnimeSelector(): String = latestUpdatesSelector()

    override fun popularAnimeFromElement(element: Element): SAnime = latestUpdatesFromElement(element)

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page"

        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document
            .select(latestUpdatesSelector())
            .map(::latestUpdatesFromElement)
            .distinctBy { it.url }

        return AnimesPage(animeList, document.hasNextPage())
    }

    override fun latestUpdatesSelector(): String = "#lastesteps_id > div.post"

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        val titleElement = element.selectFirst("p.posttitle a") ?: return@apply
        val episodeTitle = titleElement.text()

        setUrlWithoutDomain(episodeTitle.toSeriesUrl(titleElement.absUrl("href")))
        title = episodeTitle.substringBeforeEpisode()
        thumbnail_url = element.selectFirst("div.postimg img")?.absUrl("src")
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val hasSelectedGenre = filters.filterIsInstance<GenreFilter>().any { it.state != 0 }
        if (!hasSelectedGenre && query.isNotBlank()) {
            val genrePage = client.newCall(searchGenreRequest(page, query.toGenreValue()))
                .awaitSuccess()
                .let(::searchAnimeParse)
            if (genrePage.animes.isNotEmpty()) return genrePage
        }

        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genre = filters.filterIsInstance<GenreFilter>()
            .firstOrNull { it.state != 0 }
            ?.toUriPart()

        if (genre != null) return searchGenreRequest(page, genre)

        val url = (if (page == 1) baseUrl else "$baseUrl/page/$page").toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .build()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        if (response.request.url.encodedPath.startsWith("/genres")) {
            return AnimesPage(document.select(genreAnimeSelector()).map(::genreAnimeFromElement), false)
        }

        val elements = document.select(searchAnimeSelector())

        val animeList = elements
            .mapNotNull(::searchAnimeFromElementOrNull)
            .distinctBy { it.url }

        return AnimesPage(animeList, document.hasNextPage())
    }

    override fun searchAnimeSelector(): String = "div.post"

    override fun searchAnimeFromElement(element: Element): SAnime {
        return searchAnimeFromElementOrNull(element) ?: SAnime.create()
    }

    override fun searchAnimeNextPageSelector(): String? = null

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search tries matching tag/genre first"),
        GenreFilter(),
    )

    private fun searchGenreRequest(page: Int, genre: String): Request {
        val url = (if (page == 1) "$baseUrl/genres" else "$baseUrl/genres/page/$page").toHttpUrl().newBuilder()
            .addQueryParameter("genre", genre)
            .build()

        return GET(url, headers)
    }

    private fun genreAnimeSelector(): String = "#cat_genres2 .listupdate"

    private fun genreAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val titleElement = element.selectFirst("a.cat_title[href]") ?: return@apply
        val descriptionElement = element.parent()?.selectFirst("div[id^=genresDesc]")

        setUrlWithoutDomain(titleElement.absUrl("href"))
        title = titleElement.text()
        thumbnail_url = descriptionElement?.selectFirst("img[src]")?.absUrl("src")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
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
        val titleElement = element.selectFirst("p.posttitle a") ?: return@apply
        val title = titleElement.text()

        setUrlWithoutDomain(titleElement.attr("href"))
        name = title
        episode_number = EPISODE_REGEX.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: 1F
        date_upload = element.selectFirst("p.dtcreated")?.text()?.parseDateAdded() ?: 0L
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
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

    private fun String.toGenreValue(): String = trim().replace(Regex("\\s+"), "_")

    private fun String.parseDateAdded(): Long {
        val date = removePrefix("Added:").trim()
        return DATE_ADDED_FORMATS.firstNotNullOfOrNull { format ->
            runCatching { format.parse(date)?.time }.getOrNull()
        } ?: 0L
    }

    private fun searchAnimeFromElementOrNull(element: Element): SAnime? {
        val titleElement = element.selectFirst("p.posttitle a[href]")
            ?: return null
        val detailsElement = titleElement.attr("rev")
            .takeIf(String::isNotBlank)
            ?.let { element.selectFirst("div#$it") }
        val seriesTitleElement = detailsElement?.selectFirst("h3.title a[href]")

        return SAnime.create().apply {
            setUrlWithoutDomain(seriesTitleElement?.absUrl("href") ?: titleElement.absUrl("href"))
            title = seriesTitleElement?.text() ?: titleElement.text()
            thumbnail_url = detailsElement?.selectFirst("img[src]")?.absUrl("src")
                ?: element.selectFirst("div.postimg img")?.absUrl("src")
        }
    }

    companion object {
        private val EPISODE_REGEX = Regex("Episode\\s+(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        private val JSON_UPLOAD_DATE_REGEX = Regex("\"uploadDate\"\\s*:\\s*\"([^\"]+)\"")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        private val DATE_ADDED_FORMATS = listOf(
            SimpleDateFormat("MMM d'th', yyyy", Locale.US),
            SimpleDateFormat("MMM d'st', yyyy", Locale.US),
            SimpleDateFormat("MMM d'nd', yyyy", Locale.US),
            SimpleDateFormat("MMM d'rd', yyyy", Locale.US),
        )
        private val GENRES = arrayOf(
            "<select>" to "",
            "Absurdist Humour" to "Absurdist_Humour",
            "Action" to "Action",
            "Adventure" to "Adventure",
            "Ahegao" to "Ahegao",
            "Anal" to "Anal",
            "Anime" to "Anime",
            "BDSM" to "BDSM",
            "Big Breasts" to "Big_Breasts",
            "Bondage" to "Bondage",
            "Breasts" to "Breasts",
            "Censored" to "Censored",
            "Comedy" to "Comedy",
            "Cream Pie" to "Cream_Pie",
            "Cunnilingus" to "Cunnilingus",
            "Dark-Skinned Girl" to "Dark-Skinned_Girl",
            "Deflowering" to "Deflowering",
            "Demon" to "Demon",
            "Demons" to "Demons",
            "Doggy Style" to "Doggy_Style",
            "Double Penetration" to "Double_Penetration",
            "Ecchi" to "Ecchi",
            "English Dubbed" to "English_Dubbed",
            "Fantasy" to "Fantasy",
            "Fellatio" to "Fellatio",
            "Female Student" to "Female_Student",
            "Female Teacher" to "Female_Teacher",
            "Fingering" to "Fingering",
            "Foot Fetish" to "Foot_Fetish",
            "Foursome" to "Foursome",
            "French Kiss" to "French_Kiss",
            "Futa x Female" to "Futa_x_Female",
            "Futanari" to "Futanari",
            "Gang Bang" to "Gang_Bang",
            "Gangbang" to "Gangbang",
            "Group Sex" to "Group_Sex",
            "Harem" to "Harem",
            "Handjob" to "Handjob",
            "High School" to "High_School",
            "Horror" to "Horror",
            "Huge Breasts" to "Huge_Breasts",
            "Impregnation" to "Impregnation",
            "Incest" to "Incest",
            "Internal Shots" to "Internal_Shots",
            "Josei" to "Josei",
            "Large Breasts" to "Large_Breasts",
            "Loli" to "Loli",
            "Lolicon" to "Lolicon",
            "Maid" to "Maid",
            "Manga" to "Manga",
            "Masturbation" to "Masturbation",
            "Netorare" to "Netorare",
            "Nudity" to "Nudity",
            "Nurse" to "Nurse",
            "Oral" to "Oral",
            "Orgy" to "Orgy",
            "Paizuri" to "Paizuri",
            "Pantyjob" to "Pantyjob",
            "Parody" to "Parody",
            "Pornography" to "Pornography",
            "POV" to "POV",
            "Public Sex" to "Public_Sex",
            "Rape" to "Rape",
            "Romance" to "Romance",
            "School" to "School",
            "School Life" to "School_Life",
            "Sci-Fi" to "Sci-Fi",
            "Seinen" to "Seinen",
            "Sex" to "Sex",
            "Sex Toys" to "Sex_Toys",
            "Short Episodes" to "Short_Episodes",
            "Shota" to "Shota",
            "Shotacon" to "Shotacon",
            "Shoujo" to "Shoujo",
            "Shoujo Ai" to "Shoujo_Ai",
            "Small Breasts" to "Small_Breasts",
            "Speculative Fiction" to "Speculative_Fiction",
            "Squirting" to "Squirting",
            "Tentacle" to "Tentacle",
            "Tentacles" to "Tentacles",
            "Threesome" to "Threesome",
            "Trap" to "Trap",
            "Uncensored" to "Uncensored",
            "Virgin" to "Virgin",
            "Voyeurism" to "Voyeurism",
            "Yaoi" to "Yaoi",
            "Yuri" to "Yuri",
        )
    }

    private class GenreFilter : UriPartFilter("Genre", GENRES)

    private open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart(): String = vals[state].second
    }
}
