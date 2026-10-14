package eu.kanade.tachiyomi.animeextension.en.hentaimama

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class HentaiMama : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "HentaiMama"

    override val baseUrl = "https://hentaimama.io"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", baseUrl)
            .add("X-Requested-With", "XMLHttpRequest")
    }

    // ============================ Popular ============================

    override fun popularAnimeSelector(): String = "article.series-card"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/advance-search/page/$page/?submit=Submit&filter=weekly")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("h3.sc-title a").attr("href"))
        anime.title = element.select("h3.sc-title a").text()
        anime.thumbnail_url = element.select("a.sc-poster img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination.dt-pg a.dt-pg-next"

    // ============================ Latest ============================

    override fun latestUpdatesSelector(): String = "article.series-card"

    override fun latestUpdatesRequest(page: Int): Request {
        // /hentai-series/ defaults to A-Z ordering; ?filter=recent gives newest first.
        val url = if (page == 1) {
            "$baseUrl/hentai-series/?filter=recent"
        } else {
            "$baseUrl/hentai-series/page/$page/?filter=recent"
        }
        return GET(url)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("h3.sc-title a").attr("href"))
        anime.title = element.select("h3.sc-title a").text()
        anime.thumbnail_url = element.select("a.sc-poster img").attr("src")
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = "div.pagination.dt-pg a.dt-pg-next"

    // ============================ Search ============================

    private var filterSearch = false

    override fun searchAnimeSelector(): String = "article.series-card"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("h3.sc-title a").attr("href"))
        anime.title = element.select("h3.sc-title a").text()
        anime.thumbnail_url = element.select("a.sc-poster img").attr("src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.pagination.dt-pg a.dt-pg-next"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotEmpty()) {
            filterSearch = false
            val encoded = URLEncoder.encode(query, "UTF-8")
            GET("$baseUrl/page/$page/?s=$encoded")
        } else {
            filterSearch = true
            GET("$baseUrl/advance-search/page/$page/?${getSearchParameters(filters)}")
        }
    }

    // ============================ Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1.dsc-title")?.text().orEmpty()
        anime.thumbnail_url = document.selectFirst(".dsc-poster img")?.attr("src")
        anime.genre = document.select(".dsc-genres a")
            .joinToString(", ") { it.text() }

        // Description is in .dsc-desc p
        anime.description = document.selectFirst(".dsc-desc p")?.text()

        // Status from chip
        anime.status = when {
            document.selectFirst(".dsc-chip.is-completed") != null -> SAnime.COMPLETED
            else -> SAnime.ONGOING
        }

        // Studio from .dsc-stat containing "Studio" span
        anime.author = document.select(".dsc-stats .dsc-stat")
            .firstOrNull { it.select("span").text().equals("Studio", ignoreCase = true) }
            ?.select("b a")
            ?.joinToString(", ") { it.text() }

        return anime
    }

    // ============================ Episodes ============================

    override fun episodeListSelector(): String = "a.dt-se-item"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val dateText = element.select("span.dt-se-date").text()
        val epNumText = element.select("b.dt-se-num").text()
        val epNumPattern = Regex("(\\d+)")
        val epNumMatch = epNumPattern.find(epNumText)

        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.select("b.dt-se-title").text()
        episode.date_upload = runCatching {
            SimpleDateFormat("MMM dd, yyyy", Locale.US).parse(dateText)?.time
        }.getOrNull() ?: 0L
        episode.episode_number = epNumMatch?.groups?.get(1)?.value?.toFloatOrNull() ?: 1F

        return episode
    }

    // ============================ Videos ============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        Log.d(TAG, "videoListParse: episode page ${response.request.url}")

        // Extract episode ID from body class "postid-XXX"
        val episodeId = Regex("postid-(\\d+)").find(document.body().className())
            ?.groups?.get(1)?.value
        if (episodeId == null) {
            Log.w(TAG, "videoListParse: no postid-XXX in body class '${document.body().className()}'")
            return emptyList()
        }

        // Server tabs, e.g. <a class="options" href="#option-5">mi-1</a>. The trailing
        // number is the server index used by the get_player_contents ajax call, and it is
        // NOT always 1..n (e.g. 5, 3, 1), so read it from the href instead of assuming order.
        val tabs = document.select("ul.idTabs.sourceslist.dt-mi-tabs li a.options")
        val optionIndexes = tabs
            .mapNotNull { OPTION_HREF_REGEX.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .ifEmpty { (1..tabs.size.coerceAtLeast(1)).toList() }
        Log.d(TAG, "videoListParse: episodeId=$episodeId, tabs=[${tabs.joinToString { it.text().trim() }}], optionIndexes=$optionIndexes")

        val videoList = mutableListOf<Video>()

        optionIndexes.forEachIndexed { position, optionIndex ->
            val mirror = position + 1
            runCatching {
                val iframeSrc = fetchPlayerIframeSrc(episodeId, optionIndex) ?: return@runCatching
                Log.d(TAG, "videoListParse: mirror $mirror (option $optionIndex) iframe src=$iframeSrc")

                // Visit embed URL to extract the media source
                val embedResponse = client.newCall(GET(iframeSrc)).execute()
                val embedHtml = embedResponse.asJsoup().outerHtml()
                Log.d(TAG, "videoListParse: mirror $mirror embed page code=${embedResponse.code} (${embedHtml.length} chars):\n$embedHtml")

                val videoUrl = extractVideoUrl(embedHtml)

                if (!videoUrl.isNullOrBlank()) {
                    Log.d(TAG, "videoListParse: mirror $mirror -> $videoUrl")
                    videoList.add(Video(videoUrl, "Mirror $mirror", videoUrl))
                } else {
                    Log.w(TAG, "videoListParse: mirror $mirror no media url found")
                }
            }.onFailure {
                Log.e(TAG, "videoListParse: mirror $mirror failed", it)
            }
        }

        Log.d(TAG, "videoListParse: resolved ${videoList.size} video(s): ${videoList.map { "${it.quality} -> ${it.url}" }}")
        return videoList
    }

    private fun fetchPlayerIframeSrc(episodeId: String, optionIndex: Int): String? {
        val body = FormBody.Builder()
            .add("action", "get_player_contents")
            .add("a", episodeId)
            .add("i", optionIndex.toString())
            .build()

        val response = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", body = body)).execute()
        return try {
            val json = response.body?.string().orEmpty()
            Log.d(TAG, "fetchPlayerIframeSrc: option $optionIndex code=${response.code} (${json.length} chars):\n$json")
            // Response is a JSON array of iframe HTML strings (one per mirror)
            // e.g. ["<iframe src=\"...\">", "<iframe src=\"...\">"]
            val iframeHtml = extractIframeFromJsonArray(json, optionIndex - 1)
            Log.d(TAG, "fetchPlayerIframeSrc: option $optionIndex iframeHtml=$iframeHtml")
            // Extract src attribute from the iframe HTML and decode HTML entities
            val src = IFRAME_SRC_REGEX.find(iframeHtml)?.groups?.get(1)?.value
                ?.replace("&amp;", "&")
                ?.replace("&#038;", "&")
                ?.replace("&#8211;", "–")
            Log.d(TAG, "fetchPlayerIframeSrc: option $optionIndex src=$src")
            src
        } finally {
            response.close()
        }
    }

    /**
     * Extracts the Nth string element from a flat JSON array of strings
     * without pulling in a JSON library dependency. Handles escaped quotes.
     */
    private fun extractIframeFromJsonArray(json: String, index: Int): String {
        val elements = mutableListOf<String>()
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (c == '"') {
                val sb = StringBuilder()
                i++
                while (i < json.length) {
                    val ch = json[i]
                    if (ch == '\\' && i + 1 < json.length) {
                        val next = json[i + 1]
                        sb.append(
                            when (next) {
                                'n' -> '\n'
                                't' -> '\t'
                                'r' -> '\r'
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                else -> next
                            },
                        )
                        i += 2
                    } else if (ch == '"') {
                        i++
                        break
                    } else {
                        sb.append(ch)
                        i++
                    }
                }
                elements.add(sb.toString())
            } else {
                i++
            }
        }
        return elements.getOrNull(index).orEmpty()
    }

    private fun extractVideoUrl(html: String): String? {
        // Prefer the player's source list: sources: [ { file: "https:\/\/...mp4", type: "mp4"|"hls" }, ... ]
        // Handles both `"file": "..."` and `file: "..."` and escaped slashes.
        val sourcesBlock = SOURCES_BLOCK_REGEX.find(html)?.groupValues?.get(1)
        if (sourcesBlock != null) {
            SOURCE_FILE_REGEX.find(sourcesBlock)?.groupValues?.get(1)
                ?.let { return it.toMediaUrl() }
        }

        // Fallback: a top-level file: "....mp4/.m3u8" declaration
        return MEDIA_FILE_REGEX.find(html)?.groupValues?.get(1)?.toMediaUrl()
    }

    private fun String.toMediaUrl(): String? {
        // Unescape JS-escaped slashes and only accept absolute http(s) media URLs.
        val url = replace("\\/", "/").trim()
        return url.takeIf { it.startsWith("http", ignoreCase = true) }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null) ?: return this
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

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================ Settings ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue("Mirror 2")
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

    // ============================ Filters ============================

    internal class Genre(val id: String) : AnimeFilter.CheckBox(id)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Genre", genres)
    private fun getGenres() = listOf(
        Genre("3D"),
        Genre("Action"),
        Genre("Adventure"),
        Genre("Ahegao"),
        Genre("Anal"),
        Genre("Animal Girls"),
        Genre("BDSM"),
        Genre("Blackmail"),
        Genre("Blonde"),
        Genre("Blowjob"),
        Genre("Bondage"),
        Genre("Brainwashed"),
        Genre("Cat Girl"),
        Genre("Comedy"),
        Genre("Condom"),
        Genre("Cosplay"),
        Genre("Creampie"),
        Genre("Cross-dressing"),
        Genre("Cute & Funny"),
        Genre("Dark Skin"),
        Genre("DeepThroat"),
        Genre("Demons"),
        Genre("Doctor"),
        Genre("Domination"),
        Genre("Double Penetration"),
        Genre("Drama"),
        Genre("Dubbed"),
        Genre("Ecchi"),
        Genre("Elf"),
        Genre("Facesitting"),
        Genre("Facial"),
        Genre("Family"),
        Genre("Fantasy"),
        Genre("Female Doctor"),
        Genre("Female Teacher"),
        Genre("Femdom"),
        Genre("Filmed"),
        Genre("Footjob"),
        Genre("Furry"),
        Genre("Futanari"),
        Genre("Gangbang"),
        Genre("Gyaru"),
        Genre("Hand Job"),
        Genre("Harem"),
        Genre("Historical"),
        Genre("Horny Slut"),
        Genre("Horror"),
        Genre("Housewife"),
        Genre("Humiliation"),
        Genre("Incest"),
        Genre("Inflation"),
        Genre("Internal Cumshot"),
        Genre("Lactation"),
        Genre("Large Breasts"),
        Genre("Magical Girls"),
        Genre("Maid"),
        Genre("Martial Arts"),
        Genre("Masturbation"),
        Genre("Megane"),
        Genre("MILF"),
        Genre("Mind Break"),
        Genre("Molestation"),
        Genre("Monsters"),
        Genre("Nekomimi"),
        Genre("Nipple Fuck"),
        Genre("Non-Japanese"),
        Genre("NTR"),
        Genre("Nuns"),
        Genre("Nurses"),
        Genre("Office Ladies"),
        Genre("Orc/Goblin"),
        Genre("Orgy"),
        Genre("Police"),
        Genre("POV"),
        Genre("Pregnant"),
        Genre("Princess"),
        Genre("Public Sex"),
        Genre("Rape"),
        Genre("Rim job"),
        Genre("Romance"),
        Genre("Scat"),
        Genre("School Girls"),
        Genre("Sci-Fi"),
        Genre("Sci-Fi & Fantasy"),
        Genre("Shimapan"),
        Genre("Short"),
        Genre("Shoutacon"),
        Genre("Small Breasts"),
        Genre("Sports"),
        Genre("Squirting"),
        Genre("Step Daughter"),
        Genre("Step Mother"),
        Genre("Step Sister"),
        Genre("Stocking"),
        Genre("Strap-on"),
        Genre("Succubus"),
        Genre("Super Power"),
        Genre("Supernatural"),
        Genre("Swimsuit"),
        Genre("Tentacles"),
        Genre("Three some"),
        Genre("Tits Fuck"),
        Genre("Toys"),
        Genre("Train Molestation"),
        Genre("Trap"),
        Genre("Tsundere"),
        Genre("Ugly Bastard"),
        Genre("Uncensored"),
        Genre("Urination"),
        Genre("Vampire"),
        Genre("Vanilla"),
        Genre("Virgins"),
        Genre("Watersports"),
        Genre("Widow"),
        Genre("Womb Tattoo"),
        Genre("X-Ray"),
        Genre("Yuri"),
    )

    internal class Year(val id: String) : AnimeFilter.CheckBox(id)
    private class YearList(years: List<Year>) : AnimeFilter.Group<Year>("Year", years)
    private fun getYears() = listOf(
        Year("2026"),
        Year("2025"),
        Year("2024"),
        Year("2023"),
        Year("2022"),
        Year("2021"),
        Year("2020"),
        Year("2019"),
        Year("2018"),
        Year("2017"),
        Year("2016"),
        Year("2015"),
        Year("2014"),
        Year("2013"),
        Year("2012"),
        Year("2011"),
        Year("2010"),
        Year("2009"),
        Year("2008"),
        Year("2007"),
        Year("2006"),
        Year("2005"),
        Year("2004"),
        Year("2003"),
        Year("2002"),
        Year("2001"),
        Year("2000"),
        Year("1999"),
        Year("1998"),
        Year("1997"),
        Year("1996"),
        Year("1995"),
        Year("1994"),
        Year("1993"),
        Year("1992"),
        Year("1991"),
        Year("1987"),
    )

    internal class Producer(val id: String) : AnimeFilter.CheckBox(id)
    private class ProducerList(producers: List<Producer>) : AnimeFilter.Group<Producer>("Producer", producers)
    private fun getProducer() = listOf(
        Producer("8bit"),
        Producer("Actas"),
        Producer("Active"),
        Producer("AIC"),
        Producer("AIC A.S.T.A."),
        Producer("Alice Soft"),
        Producer("An DerCen"),
        Producer("Angelfish"),
        Producer("Animac"),
        Producer("AniMan"),
        Producer("Animax"),
        Producer("AnimeFesta"),
        Producer("Antechinus"),
        Producer("APPP"),
        Producer("Armor"),
        Producer("Arms"),
        Producer("Asahi Production"),
        Producer("AT-2"),
        Producer("Blue Eyes"),
        Producer("BOMB! CUTE! BOMB!"),
        Producer("BOOTLEG"),
        Producer("Bunnywalker"),
        Producer("Central Park Media"),
        Producer("CherryLips"),
        Producer("ChiChinoya"),
        Producer("Chippai"),
        Producer("ChuChu"),
        Producer("Circle Tribute"),
        Producer("CLOCKUP"),
        Producer("Collaboration Works"),
        Producer("Comic Media"),
        Producer("Cosmic Ray"),
        Producer("Cosmo"),
        Producer("Cotton Doll"),
        Producer("Cranberry"),
        Producer("D3"),
        Producer("Daiei"),
        Producer("Digital Works"),
        Producer("Discovery"),
        Producer("Dream Force"),
        Producer("Dubbed"),
        Producer("Easy Film"),
        Producer("Echo"),
        Producer("EDGE"),
        Producer("Filmlink International"),
        Producer("Five Ways"),
        Producer("Front Line"),
        Producer("Frontier Works"),
        Producer("Godoy"),
        Producer("Gold Bear"),
        Producer("Green Bunny"),
        Producer("Himajin Planning"),
        Producer("Hokiboshi"),
        Producer("Hoods Entertainment"),
        Producer("Horipro"),
        Producer("Hot Bear"),
        Producer("HydraFXX"),
        Producer("Innocent Grey"),
        Producer("Jam"),
        Producer("JapanAnime"),
        Producer("Juicymango"),
        Producer("King Bee"),
        Producer("Kitty Films"),
        Producer("Kitty Media"),
        Producer("Knack Productions"),
        Producer("KSS"),
        Producer("Lemon Heart"),
        Producer("Lune Pictures"),
        Producer("Majin"),
        Producer("Marvelous Entertainment"),
        Producer("Mary Jane"),
        Producer("Media"),
        Producer("Media Blasters"),
        Producer("Milkshake"),
        Producer("Mitsu"),
        Producer("Moonstone Cherry"),
        Producer("Mousou Senka"),
        Producer("MS Pictures"),
        Producer("Nag"),
        Producer("Nihikime no Dozeu"),
        Producer("No Future"),
        Producer("Nur"),
        Producer("NuTech Digital"),
        Producer("Obtain Future"),
        Producer("Office Take Off"),
        Producer("OLE-M"),
        Producer("Oriental Light and Magic"),
        Producer("Oz"),
        Producer("Pashmina"),
        Producer("Pink Pineapple"),
        Producer("Pixy"),
        Producer("PoRO"),
        Producer("Production I.G"),
        Producer("Queen Bee"),
        Producer("Rojiura Jack"),
        Producer("Sakura Purin Animation"),
        Producer("Schoolzone"),
        Producer("Selfish"),
        Producer("Seven"),
        Producer("Shelf"),
        Producer("Shinkuukan"),
        Producer("Shinyusha"),
        Producer("Shouten"),
        Producer("Silky's"),
        Producer("Sodeno19"),
        Producer("Soft Garage"),
        Producer("SoftCel Pictures"),
        Producer("SPEED"),
        Producer("Studio 9 Maiami"),
        Producer("Studio Eromatick"),
        Producer("Studio Fantasia"),
        Producer("Studio Jack"),
        Producer("Studio Kyuuma"),
        Producer("Studio Matrix"),
        Producer("Studio Sign"),
        Producer("Studio Tulip"),
        Producer("Studio Unicorn"),
        Producer("Suzuki Mirano"),
        Producer("T-Rex"),
        Producer("The Right Stuf International"),
        Producer("Toho Company"),
        Producer("Top-Marschal"),
        Producer("Toranoana"),
        Producer("Torudaya"),
        Producer("Toshiba Entertainment"),
        Producer("Triangle Bitter"),
        Producer("Triple X"),
        Producer("Umemaro3D"),
        Producer("Union Cho"),
        Producer("Valkyria"),
        Producer("White Bear"),
        Producer("Y.O.U.C"),
        Producer("ZIZ Entertainment"),
        Producer("Zyc"),
    )

    private data class Order(val name: String, val id: String)
    private class OrderList(orders: Array<String>) : AnimeFilter.Select<String>("Order", orders)
    private val orderName = getOrder().map { it.name }.toTypedArray()
    private fun getOrder() = listOf(
        Order("Weekly Views", "weekly"),
        Order("Monthly Views", "monthly"),
        Order("Alltime Views", "alltime"),
        Order("A-Z", "alphabet"),
        Order("Rating", "rating"),
    )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        val params = StringBuilder()
        var genreIdx = 0
        var yearIdx = 0
        var studioIdx = 0
        var sortBy = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreList -> filter.state.forEach { g ->
                    if (g.state) {
                        params.append("&genres_filter[").append(genreIdx++).append("]=").append(g.id)
                    }
                }
                is YearList -> filter.state.forEach { y ->
                    if (y.state) {
                        params.append("&years_filter[").append(yearIdx++).append("]=").append(y.id)
                    }
                }
                is ProducerList -> filter.state.forEach { p ->
                    if (p.state) {
                        params.append("&studios_filter[").append(studioIdx++).append("]=").append(p.id)
                    }
                }
                is OrderList -> sortBy = getOrder()[filter.state].id
                else -> {}
            }
        }

        params.append("&submit=Submit&filter=").append(sortBy)
        return params.toString()
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Ignored if using Text Search"),
        AnimeFilter.Separator(),
        OrderList(orderName),
        GenreList(getGenres()),
        YearList(getYears()),
        ProducerList(getProducer()),
    )

    companion object {
        private const val TAG = "HentaiMama"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("Mirror 1", "Mirror 2", "Mirror 3", "Mirror 4")

        // Server tab href, e.g. "#option-5" -> 5
        private val OPTION_HREF_REGEX = Regex("""#option-(\d+)""", RegexOption.IGNORE_CASE)

        // Player source list block: sources: [ ... ]
        private val SOURCES_BLOCK_REGEX = Regex(
            """sources\s*:\s*\[(.*?)]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        // Any file entry inside a source list, e.g. "file": "https:\/\/..." or file: "..."
        private val SOURCE_FILE_REGEX = Regex(
            """["']?file["']?\s*:\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )

        // Fallback for a top-level jwplayer file URL (mp4 or m3u8), escaped slashes allowed
        private val MEDIA_FILE_REGEX = Regex(
            """["']?file["']?\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""",
            RegexOption.IGNORE_CASE,
        )

        // Matches <iframe src="..."> within iframe HTML
        private val IFRAME_SRC_REGEX = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    }
}
