package eu.kanade.tachiyomi.animeextension.en.hentaihavenxx

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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

class HentaiHavenXX : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "HentaiHavenXX"

    override val baseUrl = "https://hentaihaven.xxx"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", baseUrl)
            .add("Referer", "$baseUrl/")
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "div.item-thumb"
    override fun popularAnimeSelector(): String = "a[href^=\"/watch/\"]:has(img)"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/page/$page/?m_orderby=rating")
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/?m_orderby=rating"
        } else {
            "$baseUrl/page/$page/?m_orderby=rating"
        }
        return GET(url, headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a").attr("title")
        anime.thumbnail_url = element.select("img").attr("src")
        anime.setUrlWithoutDomain(element.attr("href"))
        val img = element.selectFirst("img")
        anime.title = img?.attr("alt")?.ifEmpty { null } ?: element.text()
        anime.thumbnail_url = img?.attr("src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.nextpostslink"
    override fun popularAnimeNextPageSelector(): String = "a[href*=\"/page/\"]"

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val iframe = document.selectFirst("div.player_logic_item iframe")?.attr("src")
            ?: return emptyList()
        val animes = document.select(popularAnimeSelector())
            .mapNotNull {
                try {
                    popularAnimeFromElement(it)
                } catch (e: Exception) {
                    null
                }
            }
            .distinctBy { it.url }

        val iframeUrl = if (iframe.startsWith("//")) "https:$iframe" else iframe
        val iframeResponse = client.newCall(GET(iframeUrl, headers)).execute()
        val iframeBody = iframeResponse.body.string()
        val hasNextPage = animes.size >= 20
        return AnimesPage(animes, hasNextPage)
    }

        val token = org.jsoup.Jsoup.parse(iframeBody)
            .select("meta[name=x-secure-token]")
            .attr("content")
    // Latest Updates

        if (token.isEmpty()) return emptyList()
    override fun latestUpdatesSelector(): String = popularAnimeSelector()

        val decoded = decodeToken(token)
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/?m_orderby=latest"
        } else {
            "$baseUrl/page/$page/?m_orderby=latest"
        }
        return GET(url, headers)
    }

        val en = decoded.substringAfter("en\":\"").substringBefore('"')
        val iv = decoded.substringAfter("iv\":\"").substringBefore('"')
        val uri = decoded.substringAfter("uri\":\"").substringBefore('"')
    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

        val apiUrl = if (uri.startsWith("http")) "${uri}api.php" else "https://hentaihaven.xxx/wp-content/plugins/player-logic/api.php"
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

        val body = FormBody.Builder()
            .add("action", "zarat_get_data_player_ajax")
            .add("a", en)
            .add("b", iv)
            .build()
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

        val apiResponse = client.newCall(POST(apiUrl, headers, body)).execute()
        val apiBody = apiResponse.body.string()
    // Search

        val videoList = mutableListOf<Video>()
        try {
            val sourcesJson = apiBody.substringAfter("sources\":[").substringBefore("]")
            android.util.Log.e("HentaiHavenXX", "Sources JSON: $sourcesJson")
            val sourceObjects = sourcesJson.split("},")
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/api/manga/?search=$cleanQuery&page=$page&per_page=24&live=1&locale=en", headers)
    }

            val srcRegex = Regex("""["']src["']\s*:\s*["']([^"']+)["']""")
            val labelRegex = Regex("""["']label["']\s*:\s*["']([^"']+)["']""")
    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val animes = mutableListOf<SAnime>()
        val json = JSONObject(body)
        val data = json.optJSONArray("data")
        if (data != null) {
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val slug = item.optString("slug")
                val titleObj = item.optJSONObject("title")
                val title = titleObj?.optString("rendered") ?: item.optString("title")
                val meta = item.optJSONObject("meta")
                var thumb = meta?.optString("vraven_remote_thumbnail") ?: ""
                if (thumb.isNotEmpty() && !thumb.startsWith("http")) {
                    thumb = "https://img.hentaihaven.xxx/$thumb"
                }
                val anime = SAnime.create().apply {
                    this.title = Parser.unescapeEntities(title, false)
                    this.setUrlWithoutDomain("/watch/$slug/")
                    this.thumbnail_url = thumb.ifEmpty { null }
                }
                animes.add(anime)
            }
        }
        val totalPages = json.optInt("totalPages", 1)
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return AnimesPage(animes, page < totalPages)
    }

            for (obj in sourceObjects) {
                val urlMatch = srcRegex.find(obj)
                val labelMatch = labelRegex.find(obj)
    override fun searchAnimeSelector() = throw UnsupportedOperationException()
    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException()

                if (urlMatch != null) {
                    val url = urlMatch.groupValues[1].replace("\\/", "/")
                    val label = labelMatch?.groupValues?.get(1) ?: "Video"
    // Details

                    if (url.contains(".m3u8")) {
                        try {
                            val playlist = client.newCall(GET(url, headers)).execute().body.string()
                            android.util.Log.e("HentaiHavenXX", "Playlist for $url:\n$playlist")
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val title = document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Unknown"
        anime.title = title

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
        val thumbnail = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img[alt]")?.attr("src")
        anime.thumbnail_url = thumbnail

                                    val videoUrl = it.substringAfter("\n").substringBefore("\n").substringBefore("\r").trim()
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("p")?.text()
        anime.description = description

                                    val absVideoUrl = if (videoUrl.startsWith("http")) videoUrl else url.substringBeforeLast("/") + "/" + videoUrl
        val genres = document.select("a[href^=\"/series/\"]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()
        anime.genre = genres

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
        anime.status = SAnime.UNKNOWN
        return anime
    }

    // Episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        val currentPath = response.request.url.encodedPath.trimEnd('/')
        val seriesSlug = currentPath.substringAfter("/watch/").substringBefore('/')

        val epLinks = document.select("a[href*=/watch/$seriesSlug/episode-]")
            .map { it.attr("href") }
            .distinct()

        if (epLinks.isNotEmpty()) {
            epLinks.forEach { href ->
                val episode = SEpisode.create()
                episode.setUrlWithoutDomain(href)
                val epNum = Regex("""episode-(\d+)""").find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: 1F
                episode.episode_number = epNum
                episode.name = "Episode ${epNum.toInt()}"
                episodes.add(episode)
            }
        } else {
            val episode = SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.encodedPath)
                name = "Episode 1"
                episode_number = 1F
            }
            episodes.add(episode)
        }

        return episodes.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val html = response.body.string()
        val videoList = mutableListOf<Video>()

        val slugMatch = Regex("""\\?"slug\\?":\\?"([a-f0-9\-]{10,})\\?"""").find(html)
            ?: Regex("""["']slug["']\s*:\s*["']([a-f0-9\-]{10,})["']""").find(html)
        val streamId = slugMatch?.groupValues?.get(1)

        val videoHeaders = Headers.Builder()
            .add("Referer", "$baseUrl/")
            .build()

        if (streamId != null) {
            val masterUrl = "https://octopusmanifest.org/$streamId/playlist.m3u8"
            try {
                val playlistRes = client.newCall(GET(masterUrl, videoHeaders)).execute()
                if (playlistRes.isSuccessful) {
                    val playlist = playlistRes.body.string()
                    val separator = "#EXT-X-STREAM-INF:"
                    if (playlist.contains(separator)) {
                        playlist.substringAfter(separator).split(separator).forEach { entry ->
                            val resolution = entry.substringAfter("RESOLUTION=")
                                .substringAfter("x")
                                .substringBefore(",")
                                .substringBefore("\n")
                                .substringBefore("\r")
                                .trim()

                            val relUrl = entry.substringAfter("\n").substringBefore("\n").substringBefore("\r").trim()
                            if (relUrl.isNotEmpty()) {
                                val streamUrl = if (relUrl.startsWith("http")) relUrl else "https://octopusmanifest.org/$streamId/$relUrl"
                                val label = if (resolution.isNotEmpty()) "HLS: ${resolution}p" else "HLS: Auto"
                                videoList.add(Video(streamUrl, label, streamUrl, headers = videoHeaders))
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("HentaiHavenXX", "Error parsing playlist: ${e.message}")
                            videoList.add(Video(url, "HLS: $label", url))
                        }
                    } else if (url.contains(".mp4")) {
                        videoList.add(Video(url, "MP4: $label", url))
                    } else {
                        videoList.add(Video(url, label, url))
                        videoList.add(Video(masterUrl, "HLS: Auto", masterUrl, headers = videoHeaders))
                    }
                }
            } catch (e: Exception) {
                videoList.add(Video(masterUrl, "HLS: Auto", masterUrl, headers = videoHeaders))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (videoList.isEmpty()) {
            val permalinkMatch = Regex("""\\?"permalink\\?":\\?"([^"\\]+)\\?"""").find(html)
            val posterMatch = Regex("""\\?"poster\\?":\\?"([^"\\]+)\\?"""").find(html)
            if (streamId != null && permalinkMatch != null) {
                try {
                    val payload = JSONObject().apply {
                        put("slug", streamId)
                        put("permalink", permalinkMatch.groupValues[1])
                        put("poster", posterMatch?.groupValues?.get(1) ?: "")
                    }
                    val reqBody = payload.toString().toRequestBody("application/json".toMediaTypeOrNull())
                    val apiReq = Request.Builder()
                        .url("$baseUrl/api/stream/")
                        .headers(headers)
                        .post(reqBody)
                        .build()
                    val apiRes = client.newCall(apiReq).execute()
                    if (apiRes.isSuccessful) {
                        val apiData = JSONObject(apiRes.body.string())
                        val dataObj = apiData.optJSONObject("data")
                        val sources = dataObj?.optJSONArray("sources")
                        if (sources != null) {
                            for (i in 0 until sources.length()) {
                                val srcObj = sources.getJSONObject(i)
                                val src = srcObj.optString("src")
                                val label = srcObj.optString("label", "Auto")
                                if (src.isNotEmpty()) {
                                    videoList.add(Video(src, "HLS: $label", src, headers = videoHeaders))
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
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
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

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
