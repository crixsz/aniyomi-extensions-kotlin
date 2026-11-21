package eu.kanade.tachiyomi.animeextension.en.oppaistream

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.en.oppaistream.dto.AnilistResponseDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState.Companion.STATE_EXCLUDE
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState.Companion.STATE_INCLUDE
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.getPreferencesLazy
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder.encode

class OppaiStream : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Oppai Stream"

    override val lang = "en"

    override val baseUrl = "https://oppai.stream"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = searchRequest(page, "", order = "views")

    override fun popularAnimeParse(response: Response) = searchAnimeParse(response)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = searchRequest(page, "", order = "recent")

    override fun latestUpdatesParse(response: Response) = searchAnimeParse(response)

    // =============================== Search ===============================
    override fun getFilterList() = FILTERS

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genres = mutableListOf<String>()
        val blacklist = mutableListOf<String>()
        val studios = mutableListOf<String>()
        var order = "recent"

        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> order = filter.selectedValue()
                is GenreListFilter -> {
                    filter.state.forEach { genre ->
                        when (genre.state) {
                            STATE_INCLUDE -> genres.add(genre.value)
                            STATE_EXCLUDE -> blacklist.add(genre.value)
                        }
                    }
                }
                is StudioListFilter -> studios.addAll(filter.state.filter { it.state }.map { it.value })
                else -> {}
            }
        }

        return searchRequest(page, query, order, genres, blacklist, studios)
    }

    private fun searchRequest(
        page: Int,
        query: String,
        order: String,
        genres: List<String> = emptyList(),
        blacklist: List<String> = emptyList(),
        studios: List<String> = emptyList(),
    ): Request {
        val body = FormBody.Builder()
            .add("text", query.trim())
            .add("tags", genres.joinToString(","))
            .add("blacklist", blacklist.joinToString(","))
            .add("studios", studios.joinToString(","))
            .add("order", order)
            .add("limit", SEARCH_LIMIT.toString())
            .add("page", page.toString())
            .add("classes", "smaller-episode-gap")
            .add("character_filters", "{}")
            .build()

        return POST("$baseUrl/api/search.php", headers, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("a.episode")
        val anime = elements.mapNotNull(::searchAnimeFromElement).distinctBy { it.url }
        val hasNextPage = elements.size >= SEARCH_LIMIT
        return AnimesPage(anime, hasNextPage)
    }

    private fun searchAnimeFromElement(element: Element): SAnime? {
        val title = element.selectFirst(".episode-title")?.text() ?: return null
        return SAnime.create().apply {
            thumbnail_url = element.selectFirst("img.cover")?.let {
                it.attr("abs:src").ifEmpty { it.attr("data-src") }
            }
            this.title = title
            setUrlWithoutDomain(element.attr("abs:href"))
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val document = response.asJsoup()

        val name = document.selectFirst("h1#episode-title")?.text()
            ?.replace(EPISODE_SUFFIX_REGEX, "")
            ?.trim()
            .orEmpty()
        title = name
        description = document.selectFirst("article#episode-description")?.text()
            ?.removePrefix("Episode Overview")
            ?.replace(DESCRIPTION_FOOTER_REGEX, "")
            ?.trim()
        genre = document.select("div.wrap-genres > a[href*=/category/]").joinToString { it.text() }

        val studios = document.select("div.wrap-genres a[href*=/studio/]").eachText()
        artist = studios.joinToString()

        // Fetch from Anilist when "Anilist Cover" is selected in settings
        val useAnilistCover = preferences.getBoolean(PREF_ANILIST_COVER_KEY, PREF_ANILIST_COVER_DEFAULT)
        val thumbnailUrl = if (useAnilistCover) {
            val newTitle = name.replace(Regex("[^a-zA-Z0-9\\s!.:\"]"), " ")
            runCatching { fetchThumbnailUrlByTitle(newTitle) }.getOrNull()
        } else {
            null // Use default cover (episode preview)
        }

        // Match local studios with anilist studios to increase the accuracy of the poster
        val matchedStudio = thumbnailUrl?.second?.find { it in studios }

        thumbnail_url = matchedStudio?.let { thumbnailUrl.first }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val seriesUrl = extractSeriesUrl(document)
        val seriesDocument = if (seriesUrl != null) {
            client.newCall(GET(seriesUrl, headers)).execute().asJsoup()
        } else {
            document
        }

        return seriesDocument.select("div.wrap-episodes a.episode")
            .mapNotNull(::episodeFromElement)
            .distinctBy { it.url }
            .sortedBy { it.episode_number }
    }

    private fun extractSeriesUrl(document: Document): String? {
        val json = document.selectFirst("script[type=application/ld+json]")
            ?.data()
            ?.replace("\\/", "/")
            ?: return null
        return SERIES_URL_REGEX.find(json)?.value
    }

    private fun episodeFromElement(element: Element): SEpisode? {
        val url = element.attr("abs:href").ifEmpty { element.attr("href") }
        if (url.isBlank()) return null

        val number = element.attr("epid").ifBlank {
            element.selectFirst(".episode-number")?.text().orEmpty()
        }.ifBlank { "1" }

        return SEpisode.create().apply {
            setUrlWithoutDomain(url)
            name = "Episode $number"
            episode_number = number.toFloatOrNull() ?: 1F
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(var folder)")?.data()

        val folder = script?.jsVar("folder")
        val storageBase = script?.jsVar("storageBase") ?: DEFAULT_STORAGE_BASE
        val ep = script?.jsVar("ep") ?: "1"
        val res = script?.jsVar("res").orEmpty()
        val resList = script?.jsVarList("resList").orEmpty()
        val layout = script?.jsNumber("layout") ?: 1

        val subtitles = document.select("video#episode track").mapNotNull {
            val url = it.attr("abs:src").ifEmpty { it.attr("src") }
            if (url.isBlank()) null else Track(url, it.attr("label"))
        }

        // Fall back to the JSON-LD contentUrl when the player variables are missing.
        val jsonLdUrl = document.selectFirst("script[type=application/ld+json]")
            ?.data()
            ?.replace("\\/", "/")
            ?.let { CONTENT_URL_REGEX.find(it)?.groupValues?.get(1) }

        if (folder == null) {
            return listOfNotNull(jsonLdUrl?.let { Video(it, "1080p", it, headers, subtitles) })
        }

        val encodedBase = storageBase + "/" + folder.split("/").joinToString("/") {
            encode(it, "UTF-8").replace("+", "%20").replace("%7E", "~")
        }
        val resolutions = resList.ifEmpty {
            res.split(',').mapNotNull { it.substringBefore('-').takeIf(String::isNotBlank) }
        }

        // New layout serves DASH manifests per resolution.
        if (layout == 2) {
            val paddedEpisode = ep.toIntOrNull()?.toString()?.padStart(2, '0') ?: ep.padStart(2, '0')
            return resolutions.map { resolution ->
                val url = "$encodedBase/${resolutionFolder(resolution)}/dash$paddedEpisode/manifest.mpd"
                Video(url, qualityLabel(resolution), url, headers, subtitles)
            }
        }

        // Old layout serves a single progressive file.
        val formats = res.split(',')
            .mapNotNull { part ->
                val pieces = part.split('-', limit = 2)
                if (pieces.size == 2) {
                    pieces[0] to when (pieces[1]) {
                        "2" -> "webm"
                        else -> "mp4"
                    }
                } else {
                    null
                }
            }
            .toMap()

        val bestResolution = resolutions.maxByOrNull(::resolutionRank)
            ?: res.substringBefore('-')
        val format = formats[bestResolution] ?: formats.values.firstOrNull() ?: "mp4"
        val videoUrl = "$encodedBase/ep.$format"

        return listOf(Video(videoUrl, qualityLabel(bestResolution), videoUrl, headers, subtitles))
    }

    private fun resolutionFolder(resolution: String): String = when (resolution.lowercase()) {
        "4k" -> "4K"
        else -> "${resolution}p"
    }

    private fun qualityLabel(resolution: String): String = when (resolution.lowercase()) {
        "4k" -> "2160p"
        else -> "${resolution}p"
    }

    private fun resolutionRank(resolution: String): Int = when (resolution.lowercase()) {
        "4k" -> 2160
        else -> resolution.toIntOrNull() ?: 0
    }

    private fun String.jsVar(name: String): String? =
        Regex("""var\s+$name\s*=\s*"([^"]*)"""").find(this)?.groupValues?.get(1)
            ?.replace("\\/", "/")

    private fun String.jsNumber(name: String): Int? =
        Regex("""var\s+$name\s*=\s*(\d+)""").find(this)?.groupValues?.get(1)?.toIntOrNull()

    private fun String.jsVarList(name: String): List<String> =
        Regex("""var\s+$name\s*=\s*\[([^\]]*)]""").find(this)?.groupValues?.get(1)
            ?.split(',')
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ANILIST_COVER_KEY
            title = PREF_ANILIST_COVER_TITLE
            summary = PREF_ANILIST_COVER_SUMMARY
            setDefaultValue(PREF_ANILIST_COVER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_COVER_QUALITY_KEY
            title = PREF_COVER_QUALITY_TITLE
            entries = PREF_COVER_QUALITY_ENTRIES
            entryValues = PREF_COVER_QUALITY_VALUES
            setDefaultValue(PREF_COVER_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    // Function to fetch thumbnail URL using AniList GraphQL API
    // Only use in animeDetailsParse.
    private fun fetchThumbnailUrlByTitle(title: String): Pair<String, List<String>>? {
        val query = """
            query {
                Media(search: "$title", type: ANIME, isAdult: true) {
                    coverImage {
                        extraLarge
                        large
                    }
                    studios {
                        nodes {
                            name
                        }
                    }
                }
            }
        """.trimIndent()

        val requestBody = FormBody.Builder()
            .add("query", query)
            .build()

        val request = POST("https://graphql.anilist.co", body = requestBody)

        val response = client.newCall(request).execute()

        return parseThumbnailUrlFromObject(response.parseAs<AnilistResponseDto>())
    }

    private fun parseThumbnailUrlFromObject(obj: AnilistResponseDto): Pair<String, List<String>>? {
        val media = obj.data.media ?: return null

        val coverURL = when (preferences.getString(PREF_COVER_QUALITY_KEY, PREF_COVER_QUALITY_DEFAULT)) {
            "extraLarge" -> media.coverImage.extraLarge
            else -> media.coverImage.large
        }

        val studiosList = media.studios.names

        return Pair(coverURL, studiosList)
    }

    companion object {
        private const val SEARCH_LIMIT = 16
        private const val DEFAULT_STORAGE_BASE = "https://s1.myspacecat.pictures"

        private val EPISODE_SUFFIX_REGEX = Regex("""\s+Episode\s+\d+\s*$""")
        private val DESCRIPTION_FOOTER_REGEX = Regex("""\s*Watch .* on Oppai\.Stream for FREE!\s*$""")
        private val SERIES_URL_REGEX = Regex("""https://oppai\.stream/series/[^"]+""")
        private val CONTENT_URL_REGEX = Regex("\"contentUrl\":\"([^\"]+)\"")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private const val PREF_ANILIST_COVER_KEY = "preferred_anilist_cover"
        private const val PREF_ANILIST_COVER_TITLE = "Use Anilist as cover source - Beta"
        private const val PREF_ANILIST_COVER_DEFAULT = true
        private const val PREF_ANILIST_COVER_SUMMARY = "This feature is experimental. " +
            "It enables fetching covers from Anilist. If you see the default cover " +
            "after switching to AniList cover, try clearing the cache in " +
            "Settings > Advanced > Clear Anime Database > Oppai Steam. It only fetch Anilist covers in anime details page."

        private const val PREF_COVER_QUALITY_KEY = "preferred_cover_quality"
        private const val PREF_COVER_QUALITY_TITLE = "Preferred Anilist cover quality - Beta"
        private const val PREF_COVER_QUALITY_DEFAULT = "extraLarge"
        private val PREF_COVER_QUALITY_ENTRIES = arrayOf("Extra Large", "Large")
        private val PREF_COVER_QUALITY_VALUES = arrayOf("extraLarge", "large")
    }
}
