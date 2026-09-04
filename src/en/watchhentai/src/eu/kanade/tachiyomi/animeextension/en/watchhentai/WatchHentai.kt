package eu.kanade.tachiyomi.animeextension.en.watchhentai

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WatchHentai : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "WatchHentai"

    override val baseUrl = "https://watchhentai.net"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", baseUrl)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "article.item"

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/trending/"
        } else {
            "$baseUrl/trending/page/$page/"
        }
        android.util.Log.e("WatchHentai", "Popular request URL: $url")
        return GET(url, headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("div.poster a")
            ?: element.selectFirst("div.data h3 a")
            ?: element.selectFirst("a")
        if (link != null) {
            anime.setUrlWithoutDomain(link.attr("href"))
        }
        val img = element.selectFirst("div.poster img")
        val title = img?.attr("title") ?: img?.attr("alt") ?: ""
        val thumbnail = img?.attr("data-src") ?: img?.attr("src")
        val img = element.selectFirst("div.poster img") ?: element.selectFirst("img")
        val title = element.selectFirst("div.data h3 a")?.text()?.ifEmpty { null }
            ?: img?.attr("title")?.ifEmpty { null }
            ?: img?.attr("alt")?.ifEmpty { null }
            ?: ""
        val thumbnail = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

        android.util.Log.e("WatchHentai", "Element: ${element.className()}")
        android.util.Log.e("WatchHentai", "Link: ${link?.attr("href")}")
        android.util.Log.e("WatchHentai", "Img title attr: ${img?.attr("title")}")
        android.util.Log.e("WatchHentai", "Img alt attr: ${img?.attr("alt")}")
        android.util.Log.e("WatchHentai", "Final title: $title")

        anime.title = title
        anime.title = org.jsoup.parser.Parser.unescapeEntities(title, false)
        anime.thumbnail_url = thumbnail
        android.util.Log.e("WatchHentai", "Parsed anime: ${anime.title} - ${anime.url}")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.arrow_pag"
    override fun popularAnimeNextPageSelector(): String =
        "a.arrow_pag, div.resppages a:has(span.fa-chevron-right), div.pagination__pages span.current + a"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }
        android.util.Log.e("WatchHentai", "Found ${animes.size} anime elements")
        val animes = document.select(popularAnimeSelector())
            .filter { !it.hasClass("w_item_b") }
            .mapNotNull { element ->
                try {
                    popularAnimeFromElement(element)
                } catch (e: Exception) {
                    null
                }
            }
            .distinctBy { it.url }
        val hasNextPage = document.select(popularAnimeNextPageSelector()).first() != null
        android.util.Log.e("WatchHentai", "Has next page: $hasNextPage")
        return AnimesPage(animes, hasNextPage)
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()

        try {
            val html = response.body.string()
            val doc = Jsoup.parse(html, response.request.url.toString())

            // Find player iframe URL or meta contentUrl
            val iframeElement = doc.selectFirst("iframe#search_iframe")
                ?: doc.selectFirst("iframe[data-primary-player-url]")
                ?: doc.selectFirst("div.fakeplayer iframe")
                ?: doc.selectFirst("iframe")

            val playerUrl = iframeElement?.attr("data-primary-player-url")?.ifEmpty { null }
                ?: iframeElement?.attr("data-alternate-player-url")?.ifEmpty { null }
                ?: iframeElement?.attr("data-litespeed-src")?.ifEmpty { null }
                ?: iframeElement?.attr("data-src")?.ifEmpty { null }
                ?: iframeElement?.attr("src")?.ifEmpty { null }
                ?: doc.selectFirst("meta[itemprop=contentUrl]")?.attr("content")?.ifEmpty { null }
                ?: Regex("""data-primary-player-url=['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""data-alternate-player-url=['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""data-(?:litespeed-)?src=['"](https?://[^'"]+)['"]""").find(html)?.groupValues?.get(1)

            android.util.Log.e("WatchHentai", "Detected player URL: $playerUrl")

            if (playerUrl != null) {
                val absolutePlayerUrl = if (playerUrl.startsWith("http")) playerUrl else "$baseUrl$playerUrl"

                if (absolutePlayerUrl.contains("/player/") || absolutePlayerUrl.contains("/player-alt/")) {
                    // Fetch player page
                    val playerResponse = client.newCall(GET(absolutePlayerUrl, headers)).execute()
                    val playerHtml = playerResponse.body.string()

                    val videoHeaders = headersBuilder()
                        .set("Referer", "$baseUrl/")
                        .build()

                    val seenUrls = mutableSetOf<String>()

                    // 1. Check whJwSources = [{ file: "...", label: "1080p" }, ...]
                    val jwSourcesMatch = Regex("""whJwSources\s*=\s*(\[[^;]+\]);""").find(playerHtml)
                    if (jwSourcesMatch != null) {
                        val sourcesJson = jwSourcesMatch.groupValues[1]
                        val itemRegex = Regex("""\{[^{}]*\}""")
                        val fileRegex = Regex(""""file"\s*:\s*"([^"]+)"""")
                        val labelRegex = Regex(""""label"\s*:\s*"([^"]+)"""")

                        for (item in itemRegex.findAll(sourcesJson)) {
                            val itemStr = item.value
                            val fileEnc = fileRegex.find(itemStr)?.groupValues?.get(1)
                            if (!fileEnc.isNullOrEmpty()) {
                                try {
                                    val decodedUrl = whDecodeMediaUrl(fileEnc)
                                    val label = labelRegex.find(itemStr)?.groupValues?.get(1) ?: "Default"
                                    if (decodedUrl.isNotBlank() && seenUrls.add(decodedUrl)) {
                                        videoList.add(Video(decodedUrl, label, decodedUrl, headers = videoHeaders))
                                        android.util.Log.e("WatchHentai", "Added video from whJwSources: $label - $decodedUrl")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("WatchHentai", "Failed decoding source: ${e.message}")
                                }
                            }
                        }
                    }

                    // 2. Check var jw = { "file": "..." }
                    if (videoList.isEmpty()) {
                        val jwFileMatch = Regex("""var\s+jw\s*=\s*\{.*?"file"\s*:\s*"([^"]+)"""").find(playerHtml)
                        if (jwFileMatch != null) {
                            try {
                                val decodedUrl = whDecodeMediaUrl(jwFileMatch.groupValues[1])
                                if (decodedUrl.isNotBlank() && seenUrls.add(decodedUrl)) {
                                    videoList.add(Video(decodedUrl, "Default", decodedUrl, headers = videoHeaders))
                                    android.util.Log.e("WatchHentai", "Added video from jw.file: $decodedUrl")
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("WatchHentai", "Failed decoding jw.file: ${e.message}")
                            }
                        }
                    }

                    // 3. Check JSON-LD in player page
                    if (videoList.isEmpty()) {
                        val contentUrlMatch = Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(playerHtml)
                        if (contentUrlMatch != null) {
                            val contentUrl = contentUrlMatch.groupValues[1]
                            if (contentUrl.isNotBlank() && seenUrls.add(contentUrl)) {
                                videoList.add(Video(contentUrl, "Default", contentUrl, headers = videoHeaders))
                                android.util.Log.e("WatchHentai", "Added video from json-ld: $contentUrl")
                            }
                        }
                    }
                } else if (absolutePlayerUrl.contains("source=")) {
                    // Legacy query parameter extraction
                    val sourcePattern = Regex("""[?&]source=([^&]+)""")
                    val qualityPattern = Regex("""[?&]quality=([^&]+)""")

                    val sourceMatch = sourcePattern.find(absolutePlayerUrl)
                    val qualityMatch = qualityPattern.find(absolutePlayerUrl)

                    if (sourceMatch != null) {
                        val baseVideoUrl = java.net.URLDecoder.decode(sourceMatch.groupValues[1], "UTF-8")
                        val qualities = qualityMatch?.groupValues?.get(1)?.split(",") ?: listOf("default")
                        val videoHeaders = Headers.Builder()
                            .add("Referer", "$baseUrl/")
                            .build()

                        for (quality in qualities) {
                            val qualityTrimmed = quality.trim()
                            val videoUrl = if (qualityTrimmed != "default" && baseVideoUrl.endsWith(".mp4")) {
                                baseVideoUrl.replace(".mp4", "_$qualityTrimmed.mp4")
                            } else {
                                baseVideoUrl
                            }
                            videoList.add(Video(videoUrl, qualityTrimmed, videoUrl, headers = videoHeaders))
                            android.util.Log.e("WatchHentai", "Added video: $qualityTrimmed - $videoUrl")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WatchHentai", "Error extracting video: ${e.message}")
            e.printStackTrace()
        }

        return videoList
    }

    private fun whDecodeMediaUrl(encoded: String): String {
        var s = encoded.replace("-", "+").replace("_", "/")
        while (s.length % 4 != 0) {
            s += "="
        }
        val rawBytes = Base64.decode(s, Base64.DEFAULT)
        val k = 13
        val intermediateBytes = ByteArray(rawBytes.size)
        for (i in rawBytes.indices) {
            val b = rawBytes[i].toInt() and 0xFF
            val xorKey = (k + i % 17) and 0xFF
            intermediateBytes[i] = (b xor xorKey).toByte()
        }
        val reversedStr = String(intermediateBytes, Charsets.ISO_8859_1).reversed()
        val finalBytes = Base64.decode(reversedStr, Base64.DEFAULT)
        return String(finalBytes, Charsets.UTF_8)
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // Search

    override fun searchAnimeSelector(): String = "div.result-item article"
    override fun searchAnimeSelector(): String = "div.search-page div.items article.item, div#archive-content article.item, article.item"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("div.details div.title a")!!
        anime.setUrlWithoutDomain(link.attr("href"))
        anime.title = link.text()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

        val img = element.selectFirst("div.image div.thumbnail img")
        anime.thumbnail_url = img?.attr("data-src") ?: img?.attr("src")

        return anime
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val path = response.request.url.encodedPath
        return if (path.startsWith("/genre/") || path.startsWith("/studio/") || path.startsWith("/trending/")) {
            popularAnimeParse(response)
        } else {
            super.searchAnimeParse(response)
        }
    }
    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val selectedStudioSlug = filterList
            .filterIsInstance<StudioFilter>()
            .flatMap { it.state }
            .firstOrNull { it.state }
            ?.slug
            .orEmpty()
        val selectedGenreSlug = filterList
            .filterIsInstance<GenreFilter>()
            .flatMap { it.state }
            .firstOrNull { it.state }
            ?.slug
            .orEmpty()
        val cleanQuery = query.trim().replace(" ", "+")

        val url = when {
            cleanQuery.isNotEmpty() -> {
                if (page == 1) "$baseUrl/?s=$cleanQuery" else "$baseUrl/page/$page/?s=$cleanQuery"
            }
            selectedGenreSlug.isNotEmpty() -> {
                if (page == 1) "$baseUrl/genre/$selectedGenreSlug/" else "$baseUrl/genre/$selectedGenreSlug/page/$page/"
            }
            selectedStudioSlug.isNotEmpty() -> {
                if (page == 1) "$baseUrl/studio/$selectedStudioSlug/" else "$baseUrl/studio/$selectedStudioSlug/page/$page/"
            }
            else -> {
                if (page == 1) "$baseUrl/trending/" else "$baseUrl/trending/page/$page/"
            }
        }

        return GET(url, headers)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Ignored if using Text Search"),
        GenreFilter(),
        StudioFilter(),
    )

    private class GenreFilter : AnimeFilter.Group<Genre>(
        "Genre",
        GENRE_ENTRIES.map { Genre(it.first, it.second) },
    )

    private class Genre(name: String, val slug: String) : AnimeFilter.CheckBox(name)

    private class StudioFilter : AnimeFilter.Group<Studio>(
        "Producer",
        STUDIO_ENTRIES.map { Studio(it.first, it.second) },
    )

    private class Studio(name: String, val slug: String) : AnimeFilter.CheckBox(name)

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        val titleElement = document.selectFirst("div.post-title h1")
            ?: document.selectFirst("div.sheader div.data h1")
            ?: document.selectFirst("h1")
        android.util.Log.e("WatchHentai", "Title element: $titleElement")
        anime.title = titleElement?.text() ?: "Unknown"

        val thumbnailElement = document.selectFirst("div.summary_image img")
            ?: document.selectFirst("div.sheader div.poster img")
            ?: document.selectFirst("div.poster img")
        android.util.Log.e("WatchHentai", "Thumbnail element: $thumbnailElement")
        anime.thumbnail_url = thumbnailElement?.attr("data-src")
            ?: thumbnailElement?.attr("src")

        val descElement = document.selectFirst("div.wp-content p")
        android.util.Log.e("WatchHentai", "Description element: $descElement")
        anime.description = descElement?.text()
            ?: document.select("div.description-summary div.summary__content").text()

        val genreElements = document.select("div.sgeneros a")
        android.util.Log.e("WatchHentai", "Genre elements count: ${genreElements.size}")
        anime.genre = genreElements.joinToString { it.text() }

        anime.status = SAnime.UNKNOWN

        anime.author = document.select("div.custom_fields:has(b.variante:contains(Studio)) span.valor").text()

        android.util.Log.e("WatchHentai", "Anime details: ${anime.title}, genres: ${anime.genre}, author: ${anime.author}")
        return anime
    }

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        android.util.Log.e("WatchHentai", "Episode list parse URL: ${response.request.url}")
        val episodes = parseEpisodeList(document)
        android.util.Log.e("WatchHentai", "Episode list size from page: ${episodes.size}")
        if (episodes.isEmpty()) return episodes

        val path = response.request.url.encodedPath
        android.util.Log.e("WatchHentai", "Episode list path: $path")
        if (!path.startsWith("/series/")) return episodes

        val refreshed = fetchEpisodeListFromSeriesPage(response.request.url.toString(), episodes.size)
        android.util.Log.e("WatchHentai", "Fallback from refreshed series page: ${refreshed?.size ?: 0}")
        return refreshed ?: episodes
    }

    override fun episodeListSelector() = "ul.episodios li"

    override fun episodeFromElement(element: Element): SEpisode {
        val link = element.selectFirst("a.tv-ep-card-link")
            ?: element.selectFirst("div.episodiotitle a")
            ?: element.selectFirst("a")
            ?: throw Exception("No episode link found")

        val episode = SEpisode.create()
        val href = link.attr("href")
        episode.setUrlWithoutDomain(href)

        val title = element.selectFirst("div.episodiotitle span.eptitle")?.text()
            ?: element.selectFirst("div.episodiotitle")?.ownText()?.takeIf { it.isNotBlank() }
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: link.text()
        episode.name = title

        val episodeNumber = episodeSlugRegex.find(href)?.groupValues?.get(1)?.toFloatOrNull()
            ?: trailingNumberRegex.find(episode.name)?.groupValues?.get(1)?.toFloatOrNull()
            ?: 1F
        episode.episode_number = episodeNumber

        // Extract thumbnail if available
        val img = element.selectFirst("div.imagen img")
        val thumbnail = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")
        episode.scanlator = thumbnail // Store thumbnail in scanlator field

        // Parse date
        val dateText = element.selectFirst("span.date")?.text()
        episode.date_upload = parseDate(dateText)

        android.util.Log.e("WatchHentai", "Parsed episode: ${episode.name} - ${episode.url}")
        return episode
    }

    private fun parseEpisodeList(document: Document): List<SEpisode> {
        return document.select(episodeListSelector())
            .mapNotNull {
                try {
                    episodeFromElement(it)
                } catch (e: Exception) {
                    android.util.Log.e("WatchHentai", "Error parsing episode element: ${e.message}")
                    null
                }
            }
            .reversed()
    }

    private val episodeSlugRegex = Regex("-episode-(\\d+)")
    private val trailingNumberRegex = Regex("(\\d+)(?!.*\\d)")

    private fun fetchEpisodeListFromSeriesPage(seriesUrl: String, currentSize: Int): List<SEpisode>? {
        val absoluteUrl = if (seriesUrl.startsWith("http")) seriesUrl else baseUrl + seriesUrl
        val refreshedUrl = try {
            val httpUrl = absoluteUrl.toHttpUrl()
            httpUrl.newBuilder()
                .setQueryParameter("_ts", System.currentTimeMillis().toString())
                .build()
                .toString()
        } catch (_: Exception) {
            absoluteUrl
        }
        return try {
            client.newCall(GET(refreshedUrl, headers)).execute().use { response ->
                if (!response.isSuccessful) return null
                val document = response.asJsoup()
                val episodes = parseEpisodeList(document)
                android.util.Log.e("WatchHentai", "Episode list size from refreshed series page: ${episodes.size} ($refreshedUrl)")
                if (episodes.size > currentSize) episodes else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        return try {
            val format = java.text.SimpleDateFormat("MMM. dd, yyyy", java.util.Locale.ENGLISH)
            format.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // Latest

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/videos/"
        } else {
            "$baseUrl/videos/page/$page/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        // Extract series name from span.serie
        val seriesName = element.selectFirst("span.serie")?.text() ?: ""
        anime.title = seriesName

        // Extract episode URL and convert to series URL
        val episodeUrl = element.selectFirst("div.data a")?.attr("href") ?: ""

        // Convert episode URL to series URL
        // Example: /videos/sawaranaide-kotesashi-kun-episode-12-id-01/ -> /series/sawaranaide-kotesashi-kun-id-01/
        val seriesUrl = if (episodeUrl.contains("/videos/")) {
            episodeUrl.replace("/videos/", "/series/")
                .replace(Regex("-episode-\\d+"), "")
        } else {
            episodeUrl
        }

        anime.setUrlWithoutDomain(seriesUrl)

        // Extract thumbnail
        val img = element.selectFirst("div.poster img")
        anime.thumbnail_url = img?.attr("data-src") ?: img?.attr("src")

        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue("720p")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val GENRE_ENTRIES = arrayOf(
            Pair("3D", "3d"),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Ahegao", "ahegao"),
            Pair("Anal", "anal"),
            Pair("Animal Ears", "animal-ears"),
            Pair("Animation", "animation"),
            Pair("BDSM", "bdsm"),
            Pair("Beastiality", "beastiality"),
            Pair("Big Boobs", "big-boobs"),
            Pair("Blackmail", "blackmail"),
            Pair("Blowjob", "blowjob"),
            Pair("Bondage", "bondage"),
            Pair("Brainwashed", "brainwashed"),
            Pair("Bukakke", "bukakke"),
            Pair("Cat Girl", "cat-girl"),
            Pair("Censored", "censored"),
            Pair("Comedy", "comedy"),
            Pair("Cosplay", "cosplay"),
            Pair("Creampie", "creampie"),
            Pair("Dark Skin", "dark-skin"),
            Pair("DeepThroat", "deepthroat"),
            Pair("Demons", "demons"),
            Pair("Doctor", "doctor"),
            Pair("Double Penatration", "double-penatration"),
            Pair("Drama", "drama"),
            Pair("Dubbed Hentai", "dubbed"),
            Pair("Ecchi", "ecchi"),
            Pair("Elf", "elf"),
            Pair("Eroge", "eroge"),
            Pair("Facesitting", "facesitting"),
            Pair("Facial", "facial"),
            Pair("Family", "family"),
            Pair("Fantasy", "fantasy"),
            Pair("Female Doctor", "female-doctor"),
            Pair("Female Teacher", "female-teacher"),
            Pair("Femdom", "femdom"),
            Pair("Footjob", "footjob"),
            Pair("Futanari", "futanari"),
            Pair("Gangbang", "gangbang"),
            Pair("Gore", "gore"),
            Pair("Gyaru", "gyaru"),
            Pair("Harem", "harem"),
            Pair("Historical", "historical"),
            Pair("Horny Slut", "horny-slut"),
            Pair("Housewife", "housewife"),
            Pair("Humiliation", "humiliation"),
            Pair("Incest", "incest"),
            Pair("Inflation", "inflation"),
            Pair("Internal Cumshot", "internal-cumshot"),
            Pair("Lactation", "lactation"),
            Pair("Large Breasts", "large-breasts"),
            Pair("Lolicon", "lolicon"),
            Pair("Magical Girls", "magical-girls"),
            Pair("Maid", "maid"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Megane", "megane"),
            Pair("MILF", "milf"),
            Pair("Mind Break", "mind-break"),
            Pair("Molestation", "molestation"),
            Pair("NTR", "ntr"),
            Pair("Nuns", "nuns"),
            Pair("Nurses", "nurses"),
            Pair("Office Ladies", "office-ladies"),
            Pair("Police", "police"),
            Pair("POV", "pov"),
            Pair("Pregnant", "pregnant"),
            Pair("Princess", "princess"),
            Pair("Public Sex", "public-sex"),
            Pair("Rape", "rape"),
            Pair("Reality", "reality"),
            Pair("Rim job", "rim-job"),
            Pair("Romance", "romance"),
            Pair("Scat", "scat"),
            Pair("School Girls", "school-girls"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Shimapan", "shimapan"),
            Pair("Short", "short"),
            Pair("Shoutacon", "shoutacon"),
            Pair("Slaves", "slaves"),
            Pair("Soap", "soap"),
            Pair("Sports", "sports"),
            Pair("Squirting", "squirting"),
            Pair("Stocking", "stocking"),
            Pair("Strap-on", "strap-on"),
            Pair("Strapped On", "strapped-on"),
            Pair("Succubus", "succubus"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Swimsuit", "swimsuit"),
            Pair("Tentacles", "tentacles"),
            Pair("Three some", "three-some"),
            Pair("Tits Fuck", "tits-fuck"),
            Pair("Torture", "torture"),
            Pair("Toys", "toys"),
            Pair("Train Molestation", "train-molestation"),
            Pair("Tsundere", "tsundere"),
            Pair("Uncensored", "uncensored"),
            Pair("Urination", "urination"),
            Pair("Vampire", "vampire"),
            Pair("Vanilla", "vanilla"),
            Pair("Virgins", "virgins"),
            Pair("Widow", "widow"),
            Pair("X-Ray", "x-ray"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )
        private val STUDIO_ENTRIES = arrayOf(
            Pair("<select>", ""),
            Pair("@ OZ", "oz"),
            Pair("2011", "2011"),
            Pair("Actas", "actas"),
            Pair("Ai Yume Mai", "ai-yume-mai"),
            Pair("AIC", "aic"),
            Pair("AIC ASTA", "aic-asta"),
            Pair("Aiko", "aiko"),
            Pair("Aiti St.", "aiti-st"),
            Pair("Alice Production", "alice-production"),
            Pair("Amarcord", "amarcord"),
            Pair("Ameliatie", "ameliatie"),
            Pair("An DerCen", "an-dercen"),
            Pair("animate Film", "animate-film"),
            Pair("Anime Antenna Iinkai", "anime-antenna-iinkai"),
            Pair("Antechinus", "antechinus"),
            Pair("APPP", "appp"),
            Pair("Arms", "arms"),
            Pair("Asahi Production", "asahi-production"),
            Pair("AT-2", "at-2"),
            Pair("Atti Production", "atti-production"),
            Pair("AXsiZ", "axsiz"),
            Pair("Big Wing", "big-wing"),
            Pair("Blue bread", "blue-bread"),
            Pair("Blue Cat", "blue-cat"),
            Pair("Blue Eyes", "blue-eyes"),
            Pair("BOMB! CUTE! BOMB!", "bomb-cute-bomb"),
            Pair("BreakBottle", "breakbottle"),
            Pair("C and R", "c-and-r"),
            Pair("C&S Production", "cs-production"),
            Pair("Cafe de Jeilhouse", "cafe-de-jeilhouse"),
            Pair("Chaos Project", "chaos-project"),
            Pair("Chimu Jika Hatsuden", "chimu-jika-hatsuden"),
            Pair("Circle Tribute", "circle-tribute"),
            Pair("Collaboration Works", "collaboration-works"),
            Pair("Concept Films", "concept-films"),
            Pair("Critical Mass Video", "critical-mass-video"),
            Pair("Dangun Pictures", "dangun-pictures"),
            Pair("Datama Film", "datama-film"),
            Pair("demodemon", "demodemon"),
            Pair("Dollhouse", "dollhouse"),
            Pair("Dream Entertainment", "dream-entertainment"),
            Pair("Echo", "echo"),
            Pair("Enzo Animation", "enzo-animation"),
            Pair("Eshoya Honpo", "eshoya-honpo"),
            Pair("Ether Kitten", "ether-kitten"),
            Pair("Executive Decision", "executive-decision"),
            Pair("Ezo Kobo", "ezo-kobo"),
            Pair("Flavors Soft", "flavors-soft"),
            Pair("Front Line", "front-line"),
            Pair("fruit", "fruit"),
            Pair("G.P Entertainment", "g-p-entertainment"),
            Pair("GAV Video", "gav-video"),
            Pair("G-Lam", "g-lam"),
            Pair("GoHands", "gohands"),
            Pair("gomasioken", "gomasioken"),
            Pair("Gunners", "gunners"),
            Pair("Himajin Planning", "himajin-planning"),
            Pair("Hoods Entertainment", "hoods-entertainment"),
            Pair("Horannabi", "horannabi"),
            Pair("HydraFXX", "hydrafxx"),
            Pair("IKK Room", "ikk-room"),
            Pair("Image House", "image-house"),
            Pair("Imagin", "imagin"),
            Pair("Inugoya", "inugoya"),
            Pair("IRONBELL", "ironbell"),
            Pair("Ishibashi Planning", "ishibashi-planning"),
            Pair("ITONAMI", "itonami"),
            Pair("Ivory Tower", "ivory-tower"),
            Pair("J.C.Staff", "j-c-staff"),
            Pair("J.T.P.P.", "j-t-p-p"),
            Pair("Jared999D", "jared999d"),
            Pair("Jumondo", "jumondo"),
            Pair("Kami Kukan", "kami-kukan"),
            Pair("Kantou Douga Kai", "kantou-douga-kai"),
            Pair("Kazuki Production", "kazuki-production"),
            Pair("KENZsoft", "kenzsoft"),
            Pair("KKC Animation Production", "kkc-animation-production"),
            Pair("Knack Productions", "knack-productions"),
            Pair("Kuril", "kuril"),
            Pair("Kyushu Network Animation", "kyushu-network-animation"),
            Pair("Langmaor", "langmaor"),
            Pair("Lead", "lead"),
            Pair("Lilix", "lilix"),
            Pair("Lune Pictures", "lune-pictures"),
            Pair("Maboroshi Koubou", "maboroshi-koubou"),
            Pair("Magic Bus", "magic-bus"),
            Pair("Majin", "majin"),
            Pair("Mary Jane", "mary-jane"),
            Pair("MB planning", "mb-planning"),
            Pair("Mediabank", "mediabank"),
            Pair("Melissa", "melissa"),
            Pair("Meltdown", "meltdown"),
            Pair("Mikimoto Production", "mikimoto-production"),
            Pair("MoMo Production", "momo-production"),
            Pair("Momoi Planning", "momoi-planning"),
            Pair("Mook Animation", "mook-animation"),
            Pair("Mouse", "mouse"),
            Pair("Mousou Senka", "mousou-senka"),
            Pair("MS Pictures", "ms-pictures"),
            Pair("New Generation", "new-generation"),
            Pair("Nihikime no Dozeu", "nihikime-no-dozeu"),
            Pair("Nur", "nur"),
            Pair("Office AO", "office-ao"),
            Pair("Office No. 8", "office-no-8"),
            Pair("Office Take Off", "office-take-off"),
            Pair("Office Takeout", "office-takeout"),
            Pair("Okumaza", "okumaza"),
            Pair("OLM", "olm"),
            Pair("OMB! CUTE! BOMB!", "omb-cute-bomb"),
            Pair("ORADA COMPANY", "orada-company"),
            Pair("Oz Inc.", "oz-inc"),
            Pair("P core", "p-core"),
            Pair("Peak Hunt", "peak-hunt"),
            Pair("Phoenix Entertainment", "phoenix-entertainment-id-1"),
            Pair("Piko Studio", "piko-studio"),
            Pair("Pink Cat", "pink-cat"),
            Pair("Pink Pineapple", "pink-pineapple"),
            Pair("Poly Animation", "poly-animation"),
            Pair("PoRO", "poro"),
            Pair("Potato House", "potato-house"),
            Pair("PP Project", "pp-project"),
            Pair("Production D.M.H", "production-d-m-h"),
            Pair("Puppetmaster", "puppetmaster"),
            Pair("Queen Bee", "queen-bee"),
            Pair("Rabbit Gate", "rabbit-gate"),
            Pair("Ryuu M's", "ryuu-ms"),
            Pair("sakamotoJ", "sakamotoj"),
            Pair("Schoolzone", "schoolzone"),
            Pair("seismic", "seismic"),
            Pair("SELFISH", "selfish"),
            Pair("Seven", "seven"),
            Pair("Shindeban Film", "shindeban-film"),
            Pair("Shinjukuza", "shinjukuza"),
            Pair("Shinkuukan", "shinkuukan"),
            Pair("Shion", "shion"),
            Pair("Showten", "showten"),
            Pair("Shura", "shura"),
            Pair("Silver", "silver"),
            Pair("Sonsan Kikaku", "sonsan-kikaku"),
            Pair("Space-X", "space-x"),
            Pair("Staple Entertainment", "staple-entertainment"),
            Pair("STARGATE3D", "stargate3d"),
            Pair("Studio 1st", "studio-1st"),
            Pair("Studio 9 Maiami", "studio-9-maiami"),
            Pair("Studio B&M", "studio-bm"),
            Pair("Studio Boogie Nights", "studio-boogie-nights"),
            Pair("Studio CA", "studio-ca"),
            Pair("Studio Dolphin Night", "studio-dolphin-night"),
            Pair("Studio Eromatick", "studio-eromatick-id-1"),
            Pair("Studio Fantasia", "studio-fantasia"),
            Pair("Studio FOW", "studio-fow"),
            Pair("Studio G-1Neo", "studio-g-1neo"),
            Pair("Studio Gadget", "studio-gadget"),
            Pair("Studio Gokumi", "studio-gokumi"),
            Pair("Studio Hokiboshi", "studio-hokiboshi"),
            Pair("Studio Jam", "studio-jam"),
            Pair("Studio Kikan", "studio-kikan"),
            Pair("Studio Kyuuma", "studio-kyuuma"),
            Pair("Studio LEO", "studio-leo"),
            Pair("Studio March", "studio-march"),
            Pair("Studio Matrix", "studio-matrix"),
            Pair("Studio Mausu", "studio-mausu"),
            Pair("Studio OX", "studio-ox"),
            Pair("Studio Prokion", "studio-prokion"),
            Pair("Studio Sign", "studio-sign"),
            Pair("Studio Soul", "studio-soul"),
            Pair("Studio Ten", "studio-ten"),
            Pair("Studio Ten Carat", "studio-ten-carat"),
            Pair("Studio Zealot", "studio-zealot"),
            Pair("Sugar Boy", "sugar-boy"),
            Pair("Suiseisha", "suiseisha"),
            Pair("Sumomo Film", "sumomo-film"),
            Pair("Sunny Side Up", "sunny-side-up"),
            Pair("Suzuki Mirano", "suzuki-mirano"),
            Pair("T Japan", "t-japan-id-3"),
            Pair("Teatro Nishi Tokyo Studio", "teatro-nishi-tokyo-studio"),
            Pair("Torudaya", "torudaya"),
            Pair("T-Rex", "t-rex"),
            Pair("Triple X", "triple-x"),
            Pair("Tryforce", "tryforce"),
            Pair("Umemaro-3D", "umemaro-3d"),
            Pair("Y.O.U.C", "y-o-u-c-id-1"),
            Pair("Zyc", "zyc"),
        )
    }
}
