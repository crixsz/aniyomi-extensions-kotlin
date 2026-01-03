package eu.kanade.tachiyomi.animeextension.en.hentaihavenxx

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HentaiHavenXX : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "HentaiHavenXX"

    override val baseUrl = "https://hentaihaven.xxx"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", baseUrl)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "div.item-thumb"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/page/$page/?m_orderby=rating")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a").attr("title")
        anime.thumbnail_url = element.select("img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.nextpostslink"

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("div.player_logic_item iframe")?.attr("src")
            ?: return emptyList()

        val iframeUrl = if (iframe.startsWith("//")) "https:$iframe" else iframe
        val iframeResponse = client.newCall(GET(iframeUrl, headers)).execute()
        val iframeBody = iframeResponse.body.string()

        val token = org.jsoup.Jsoup.parse(iframeBody)
            .select("meta[name=x-secure-token]")
            .attr("content")

        if (token.isEmpty()) return emptyList()

        val decoded = decodeToken(token)

        val en = decoded.substringAfter("en\":\"").substringBefore('"')
        val iv = decoded.substringAfter("iv\":\"").substringBefore('"')
        val uri = decoded.substringAfter("uri\":\"").substringBefore('"')

        val apiUrl = if (uri.startsWith("http")) "${uri}api.php" else "https://hentaihaven.xxx/wp-content/plugins/player-logic/api.php"

        val body = FormBody.Builder()
            .add("action", "zarat_get_data_player_ajax")
            .add("a", en)
            .add("b", iv)
            .build()

        val apiResponse = client.newCall(POST(apiUrl, headers, body)).execute()
        val apiBody = apiResponse.body.string()

        val videoList = mutableListOf<Video>()
        try {
            val sourcesJson = apiBody.substringAfter("sources\":[").substringBefore("]")
            android.util.Log.e("HentaiHavenXX", "Sources JSON: $sourcesJson")
            val sourceObjects = sourcesJson.split("},")

            val srcRegex = Regex("""["']src["']\s*:\s*["']([^"']+)["']""")
            val labelRegex = Regex("""["']label["']\s*:\s*["']([^"']+)["']""")

            for (obj in sourceObjects) {
                val urlMatch = srcRegex.find(obj)
                val labelMatch = labelRegex.find(obj)

                if (urlMatch != null) {
                    val url = urlMatch.groupValues[1].replace("\\/", "/")
                    val label = labelMatch?.groupValues?.get(1) ?: "Video"

                    if (url.contains(".m3u8")) {
                        try {
                            val playlist = client.newCall(GET(url, headers)).execute().body.string()
                            android.util.Log.e("HentaiHavenXX", "Playlist for $url:\n$playlist")

                            val separator = "#EXT-X-STREAM-INF:"
                            if (playlist.contains(separator)) {
                                var qualityAdded = false
                                playlist.substringAfter(separator).split(separator).forEach {
                                    val resolution = it.substringAfter("RESOLUTION=")
                                        .substringAfter("x")
                                        .substringBefore(",")
                                        .substringBefore("\n")
                                        .substringBefore("\r")
                                        .trim()

                                    val videoUrl = it.substringAfter("\n").substringBefore("\n").substringBefore("\r").trim()

                                    val absVideoUrl = if (videoUrl.startsWith("http")) videoUrl else url.substringBeforeLast("/") + "/" + videoUrl

                                    if (resolution.isNotEmpty() && resolution.all { char -> char.isDigit() }) {
                                        videoList.add(Video(absVideoUrl, "HLS: ${resolution}p", absVideoUrl))
                                        qualityAdded = true
                                    } else {
                                        // If resolution parse fails but we have a url, maybe add it as "HLS" or skip?
                                        // Let's try to add with a generic name if valid url
                                    }
                                }
                                if (!qualityAdded) {
                                    videoList.add(Video(url, "HLS: $label", url))
                                }
                            } else {
                                android.util.Log.e("HentaiHavenXX", "No stream inf separator found")
                                videoList.add(Video(url, "HLS: $label", url))
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("HentaiHavenXX", "Error parsing playlist: ${e.message}")
                            videoList.add(Video(url, "HLS: $label", url))
                        }
                    } else if (url.contains(".mp4")) {
                        videoList.add(Video(url, "MP4: $label", url))
                    } else {
                        videoList.add(Video(url, label, url))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return videoList
    }

    private fun decodeToken(token: String): String {
        var t = token.replace("sha512-", "")
        t = rot13(t)
        t = String(android.util.Base64.decode(t, android.util.Base64.DEFAULT))
        t = rot13(t)
        t = String(android.util.Base64.decode(t, android.util.Base64.DEFAULT))
        t = rot13(t)
        t = String(android.util.Base64.decode(t, android.util.Base64.DEFAULT))
        return t
    }

    private fun rot13(input: String): String {
        val sb = StringBuilder()
        for (c in input) {
            sb.append(
                when {
                    c in 'a'..'m' -> c + 13
                    c in 'n'..'z' -> c - 13
                    c in 'A'..'M' -> c + 13
                    c in 'N'..'Z' -> c - 13
                    else -> c
                },
            )
        }
        return sb.toString()
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

    override fun searchAnimeSelector(): String = ".c-tabs-item__content"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select(".c-image-hover a").attr("href"))
        anime.title = element.select(".post-title h3").text()
        anime.thumbnail_url = element.select(".c-image-hover img").attr("src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/page/$page/?s=$query&post_type=wp-manga")
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("div.post-title h1")?.text() ?: "Unknown"
        anime.thumbnail_url = document.selectFirst("div.summary_image img")?.attr("src")
        anime.description = document.select("div.description-summary div.summary__content").text()
        anime.genre = document.select("div.genres-content a").joinToString { it.text() }
        anime.status = SAnime.UNKNOWN
        return anime
    }

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response)
    }

    override fun episodeListSelector() = "li.wp-manga-chapter"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val urlElement = element.selectFirst("a")!!

        episode.setUrlWithoutDomain(urlElement.attr("href"))

        // Extract name carefully to avoid including thumbnail or date text
        val titleDiv = urlElement.select("div").last() // Usually "Episode X" is in the second/last div
        episode.name = titleDiv?.text() ?: urlElement.text()

        // Date parsing (basic attempt at relative time or absolute text)
        // Since we don't have easy access to relative path parsers here without more imports/utils, we'll try to parse if standard or leave 0
        // The HTML shows "3 minutes ago", usually handled by specialized parsers.
        // We will default to System.currentTimeMillis() if "ago" is present, or 0L
        val dateText = element.select("span.chapter-release-date i").text()
        if (dateText.contains("ago")) {
            episode.date_upload = System.currentTimeMillis() // Rough approximation for now
        } else {
            episode.date_upload = 0L
        }

        return episode
    }

    // Latest

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/?m_orderby=latest")

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

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
