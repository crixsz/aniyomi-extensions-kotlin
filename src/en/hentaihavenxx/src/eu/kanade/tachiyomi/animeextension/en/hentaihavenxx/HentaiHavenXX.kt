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
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
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
            .add("Referer", "$baseUrl/")
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "a[href^=\"/watch/\"]:has(img)"

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
        anime.setUrlWithoutDomain(element.attr("href"))
        val img = element.selectFirst("img")
        anime.title = img?.attr("alt")?.ifEmpty { null } ?: element.text()
        anime.thumbnail_url = img?.attr("src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a[href*=\"/page/\"]"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .mapNotNull {
                try {
                    popularAnimeFromElement(it)
                } catch (e: Exception) {
                    null
                }
            }
            .distinctBy { it.url }

        val hasNextPage = animes.size >= 20
        return AnimesPage(animes, hasNextPage)
    }

    // Latest Updates

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/?m_orderby=latest"
        } else {
            "$baseUrl/page/$page/?m_orderby=latest"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // Search

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/api/manga/?search=$cleanQuery&page=$page&per_page=24&live=1&locale=en", headers)
    }

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

    override fun searchAnimeSelector() = throw UnsupportedOperationException()
    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException()

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val title = document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Unknown"
        anime.title = title

        val thumbnail = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img[alt]")?.attr("src")
        anime.thumbnail_url = thumbnail

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("p")?.text()
        anime.description = description

        val genres = document.select("a[href^=\"/series/\"]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()
        anime.genre = genres

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

        val videoHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
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
                        }
                    } else {
                        videoList.add(Video(masterUrl, "HLS: Auto", masterUrl, headers = videoHeaders))
                    }
                }
            } catch (_: Exception) {
                videoList.add(Video(masterUrl, "HLS: Auto", masterUrl, headers = videoHeaders))
            }
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
