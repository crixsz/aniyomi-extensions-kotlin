package eu.kanade.tachiyomi.animeextension.en.watchhentai

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
        if (link != null) {
            anime.setUrlWithoutDomain(link.attr("href"))
        }
        val img = element.selectFirst("div.poster img")
        val title = img?.attr("title") ?: img?.attr("alt") ?: ""
        val thumbnail = img?.attr("data-src") ?: img?.attr("src")

        android.util.Log.e("WatchHentai", "Element: ${element.className()}")
        android.util.Log.e("WatchHentai", "Link: ${link?.attr("href")}")
        android.util.Log.e("WatchHentai", "Img title attr: ${img?.attr("title")}")
        android.util.Log.e("WatchHentai", "Img alt attr: ${img?.attr("alt")}")
        android.util.Log.e("WatchHentai", "Final title: $title")

        anime.title = title
        anime.thumbnail_url = thumbnail
        android.util.Log.e("WatchHentai", "Parsed anime: ${anime.title} - ${anime.url}")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.arrow_pag"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }
        android.util.Log.e("WatchHentai", "Found ${animes.size} anime elements")
        val hasNextPage = document.select(popularAnimeNextPageSelector()).first() != null
        android.util.Log.e("WatchHentai", "Has next page: $hasNextPage")
        return AnimesPage(animes, hasNextPage)
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()

        try {
            // Read the raw HTML first (response body can only be read once)
            val html = response.body.string()

            // Debug: Log a snippet of HTML around the actual iframe tag
            val iframeTagIndex = html.indexOf("<iframe")
            if (iframeTagIndex > 0) {
                val start = maxOf(0, iframeTagIndex)
                val end = minOf(html.length, iframeTagIndex + 600)
                android.util.Log.e("WatchHentai", "HTML snippet of iframe tag: ${html.substring(start, end)}")
            } else {
                android.util.Log.e("WatchHentai", "No <iframe tag found in HTML")
            }

            // Look for the iframe with data-litespeed-src (LiteSpeed lazy loading)
            // The attribute is data-litespeed-src, not data-src
            val patterns = listOf(
                // LiteSpeed lazy loading attribute
                Regex("""data-litespeed-src=['"](https://watchhentai\.net/(?:jwplayer|plyr)/\?[^'"]+)['"]"""),
                // Fallback to standard data-src
                Regex("""data-src=['"](https://watchhentai\.net/(?:jwplayer|plyr)/\?[^'"]+)['"]"""),
                // Even more flexible
                Regex("""data-(?:litespeed-)?src=['"](https://watchhentai\.net/(?:jwplayer|plyr)/\?[^'"]+)['"]"""),
            )

            var iframeSrc: String? = null
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    iframeSrc = match.groupValues[1]
                    android.util.Log.e("WatchHentai", "Matched with pattern: ${pattern.pattern}")
                    break
                }
            }

            if (iframeSrc == null) {
                android.util.Log.e("WatchHentai", "Could not find iframe data-litespeed-src in HTML")
                return emptyList()
            }

            android.util.Log.e("WatchHentai", "Iframe src: $iframeSrc")

            // Parse the iframe URL to extract video source and quality
            // Example: https://watchhentai.net/jwplayer/?source=https%3A%2F%2Fhstorage.xyz%2Ffiles%2FM%2Fmy-mother%2Fmy-mother-1.mp4&id=9084&type=mp4&quality=480p,720p
            val sourcePattern = Regex("""[?&]source=([^&]+)""")
            val qualityPattern = Regex("""[?&]quality=([^&]+)""")

            val sourceMatch = sourcePattern.find(iframeSrc)
            val qualityMatch = qualityPattern.find(iframeSrc)

            if (sourceMatch != null) {
                val baseVideoUrl = java.net.URLDecoder.decode(sourceMatch.groupValues[1], "UTF-8")
                android.util.Log.e("WatchHentai", "Base video URL: $baseVideoUrl")

                val qualities = qualityMatch?.groupValues?.get(1)?.split(",") ?: listOf("default")
                android.util.Log.e("WatchHentai", "Qualities: $qualities")

                // Add headers with Referer
                val videoHeaders = Headers.Builder()
                    .add("Referer", "https://watchhentai.net/")
                    .build()

                // Generate video URLs for each quality
                for (quality in qualities) {
                    val qualityTrimmed = quality.trim()

                    // Generate quality-specific URL by replacing the base URL pattern
                    // Example: my-mother-1.mp4 -> my-mother-1_480p.mp4
                    val videoUrl = if (qualityTrimmed != "default" && baseVideoUrl.endsWith(".mp4")) {
                        baseVideoUrl.replace(".mp4", "_$qualityTrimmed.mp4")
                    } else {
                        baseVideoUrl
                    }

                    videoList.add(Video(videoUrl, qualityTrimmed, videoUrl, headers = videoHeaders))
                    android.util.Log.e("WatchHentai", "Added video: $qualityTrimmed - $videoUrl")
                }
            } else {
                android.util.Log.e("WatchHentai", "Could not extract source from iframe URL")
            }
        } catch (e: Exception) {
            android.util.Log.e("WatchHentai", "Error extracting video: ${e.message}")
            e.printStackTrace()
        }

        return videoList
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

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("div.details div.title a")!!
        anime.setUrlWithoutDomain(link.attr("href"))
        anime.title = link.text()

        val img = element.selectFirst("div.image div.thumbnail img")
        anime.thumbnail_url = img?.attr("data-src") ?: img?.attr("src")

        return anime
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (page == 1) {
            "$baseUrl/?s=$query"
        } else {
            "$baseUrl/page/$page/?s=$query"
        }
        return GET(url, headers)
    }

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
        val episode = SEpisode.create()
        val link = element.selectFirst("div.episodiotitle a")!!

        val href = link.attr("href")
        episode.setUrlWithoutDomain(href)
        episode.name = link.text()

        val episodeNumber = episodeSlugRegex.find(href)?.groupValues?.get(1)?.toFloatOrNull()
            ?: trailingNumberRegex.find(episode.name)?.groupValues?.get(1)?.toFloatOrNull()
            ?: 1F
        episode.episode_number = episodeNumber

        // Extract thumbnail if available
        val thumbnail = element.selectFirst("div.imagen img")?.attr("data-src")
            ?: element.selectFirst("div.imagen img")?.attr("src")
        episode.scanlator = thumbnail // Store thumbnail in scanlator field for now

        // Parse date
        val dateText = element.selectFirst("span.date")?.text()
        episode.date_upload = parseDate(dateText)

        android.util.Log.e("WatchHentai", "Parsed episode: ${episode.name} - ${episode.url}")
        return episode
    }

    private fun parseEpisodeList(document: Document): List<SEpisode> {
        return document.select(episodeListSelector())
            .map { episodeFromElement(it) }
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
    }
}
