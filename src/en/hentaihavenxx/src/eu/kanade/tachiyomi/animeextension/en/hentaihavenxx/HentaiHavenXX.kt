package eu.kanade.tachiyomi.animeextension.en.hentaihavenxx

import android.util.Log
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        Log.d(TAG, "episodeListParse: ${response.request.url}")
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        val currentPath = response.request.url.encodedPath.trimEnd('/')
        val seriesSlug = currentPath.substringAfter("/watch/").substringBefore('/')

        val epLinks = document.select("a[href*=/watch/$seriesSlug/episode-]")
            .map { it.attr("href") }
            .distinct()

        Log.d(TAG, "episodeListParse: seriesSlug=$seriesSlug, found ${epLinks.size} episode link(s): $epLinks")

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
            Log.d(TAG, "episodeListParse: no episode links found, falling back to single episode for $currentPath")
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

        Log.d(TAG, "videoListParse: episode page ${response.request.url}")
        Log.d(TAG, "videoListParse: episode page body (${html.length} chars):\n$html")

        val videoHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .build()

        // The episode page embeds the master playlist URL directly in its payload.
        val embeddedMasterUrl = Regex("""indexableSource\\?":\\?"(https?://[^"\\]+\.m3u8)""").find(html)?.groupValues?.get(1)
            ?: Regex("""contentUrl\\?":\\?"(https?://[^"\\]+\.m3u8)""").find(html)?.groupValues?.get(1)
        // Slug is not hex-only (e.g. "creamy-pie-1-720p-v1x"), so match a broad charset.
        val slug = Regex("""\\?"slug\\?":\\?"([A-Za-z0-9\-_]{5,})\\?"""").find(html)?.groupValues?.get(1)

        val masterUrl = embeddedMasterUrl
            ?: slug?.let { "https://master-lengs.org/api/v3/hh/$it/master.m3u8" }

        Log.d(TAG, "videoListParse: slug=$slug, masterUrl=$masterUrl")

        if (masterUrl != null) {
            Log.d(TAG, "videoListParse: requesting master playlist $masterUrl")
            try {
                val playlistRes = client.newCall(GET(masterUrl, videoHeaders)).execute()
                val playlist = playlistRes.body.string()
                Log.d(TAG, "videoListParse: master playlist response code=${playlistRes.code} (${playlist.length} chars):\n$playlist")
                if (playlistRes.isSuccessful) {
                    val masterHttpUrl = masterUrl.toHttpUrlOrNull()
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
                                val streamUrl = masterHttpUrl?.resolve(relUrl)?.toString() ?: relUrl
                                val label = if (resolution.isNotEmpty()) "HLS: ${resolution}p" else "HLS: Auto"
                                Log.d(TAG, "videoListParse: adding master playlist variant $label -> $streamUrl")
                                videoList.add(Video(streamUrl, label, streamUrl, headers = videoHeaders))
                            }
                        }
                    } else {
                        Log.d(TAG, "videoListParse: no variants found, adding master URL as HLS: Auto")
                        videoList.add(Video(masterUrl, "HLS: Auto", masterUrl, headers = videoHeaders))
                    }
                } else {
                    Log.w(TAG, "videoListParse: master playlist request failed with code ${playlistRes.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "videoListParse: error fetching master playlist $masterUrl", e)
                videoList.add(Video(masterUrl, "HLS: Auto", masterUrl, headers = videoHeaders))
            }
        }

        if (videoList.isEmpty()) {
            Log.d(TAG, "videoListParse: falling back to API stream extraction")
            val permalinkMatch = Regex("""\\?"permalink\\?":\\?"([^"\\]+)\\?"""").find(html)
            val posterMatch = Regex("""\\?"poster\\?":\\?"([^"\\]+)\\?"""").find(html)
            Log.d(TAG, "videoListParse: permalink=${permalinkMatch?.groupValues?.get(1)}, poster=${posterMatch?.groupValues?.get(1)}")
            if (slug != null && permalinkMatch != null) {
                try {
                    val payload = JSONObject().apply {
                        put("slug", slug)
                        put("permalink", permalinkMatch.groupValues[1])
                        put("poster", posterMatch?.groupValues?.get(1) ?: "")
                    }
                    val reqBody = payload.toString().toRequestBody("application/json".toMediaTypeOrNull())
                    val apiReq = Request.Builder()
                        .url("$baseUrl/api/stream/")
                        .headers(headers)
                        .post(reqBody)
                        .build()
                    Log.d(TAG, "videoListParse: posting to ${apiReq.url} with body $payload")
                    val apiRes = client.newCall(apiReq).execute()
                    val apiBody = apiRes.body.string()
                    Log.d(TAG, "videoListParse: API stream response code=${apiRes.code} (${apiBody.length} chars):\n$apiBody")
                    if (apiRes.isSuccessful) {
                        val apiData = JSONObject(apiBody)
                        val dataObj = apiData.optJSONObject("data")
                        val sources = dataObj?.optJSONArray("sources")
                        if (sources != null) {
                            for (i in 0 until sources.length()) {
                                val srcObj = sources.getJSONObject(i)
                                val src = srcObj.optString("src")
                                val label = srcObj.optString("label", "Auto")
                                if (src.isNotEmpty()) {
                                    Log.d(TAG, "videoListParse: adding API source HLS: $label -> $src")
                                    videoList.add(Video(src, "HLS: $label", src, headers = videoHeaders))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "videoListParse: error during API stream extraction", e)
                }
            } else {
                Log.w(TAG, "videoListParse: cannot use API fallback (slug=$slug, permalink=${permalinkMatch != null})")
            }
        }

        Log.d(TAG, "videoListParse: resolved ${videoList.size} video(s): ${videoList.map { "${it.quality} -> ${it.url}" }}")

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
        private const val TAG = "HentaiHavenXX"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
