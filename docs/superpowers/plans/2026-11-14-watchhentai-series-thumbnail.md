# WatchHentai General Series Thumbnail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure WatchHentai uses the general series poster for anime entries in the Latest Updates feed and on the series details page rather than episode screenshot thumbnails.

**Architecture:** In `WatchHentai.kt`, fix the Jsoup attribute extraction bug in `animeDetailsParse` to fall back to `src` when `data-src` is blank or absent. Implement a cached helper function to fetch and parse the series hero poster, and invoke it during `latestUpdatesParse` for each deduplicated series entry while retaining the episode screenshot as a graceful fallback.

**Tech Stack:** Kotlin, Jsoup, OkHttp 3, Gradle / Android Gradle Plugin.

**Spec:** `docs/superpowers/specs/2026-11-14-watchhentai-series-thumbnail-design.md`

## Global Constraints
- Target file: `src/en/watchhentai/src/eu/kanade/tachiyomi/animeextension/en/watchhentai/WatchHentai.kt`
- Version file: `src/en/watchhentai/build.gradle`
- Must pass `lintKotlinMain` (ktlint) without style or formatting violations.
- Must not crash or block the entire feed if an individual series page request fails or times out.

## Review Focus
1. `data-no-lazy="1"` images on series page where only `src` exists: extract `src` properly without returning empty string `""`.
2. Failed series page HTTP request in `latestUpdatesParse`: catch exceptions and keep the original episode thumbnail as fallback.
3. Repeated series entries across multiple pages or reloads: cache resolved posters in memory by series path to prevent redundant network requests.
4. Relative vs absolute URLs for series posters: ensure thumbnail URLs starting with `/` or without schema are normalized or handled consistently.
5. Deduplication order: ensure `distinctBy { it.url }` runs before series page fetching so only unique series URLs trigger HTTP requests.

---

### Task 1: Fix `animeDetailsParse` and Add Shared Poster Extraction Helper

**Files:**
- Modify: `src/en/watchhentai/src/eu/kanade/tachiyomi/animeextension/en/watchhentai/WatchHentai.kt:357-363`

**Interfaces:**
- Consumes: `Document` from Jsoup
- Produces: `extractPosterFromDoc(document: Document): String?` helper method for extracting the series hero poster.

- [ ] **Step 1: Define `extractPosterFromDoc(document: Document): String?` helper**

In `WatchHentai.kt`, add:
```kotlin
private fun extractPosterFromDoc(document: Document): String? {
    val img = document.selectFirst("div.summary_image img")
        ?: document.selectFirst("div.sheader div.poster img")
        ?: document.selectFirst("div.poster img")
    return img?.attr("data-src")?.takeIf { it.isNotBlank() }
        ?: img?.attr("src")?.takeIf { it.isNotBlank() }
}
```

- [ ] **Step 2: Update `animeDetailsParse` to use the helper**

In `animeDetailsParse`:
```kotlin
val thumbnailElement = document.selectFirst("div.summary_image img")
    ?: document.selectFirst("div.sheader div.poster img")
    ?: document.selectFirst("div.poster img")
android.util.Log.e("WatchHentai", "Thumbnail element: $thumbnailElement")
anime.thumbnail_url = extractPosterFromDoc(document)
```

- [ ] **Step 3: Run Kotlin compilation and linter**

Run: `./gradlew.bat :src:en:watchhentai:compileDebugKotlin :src:en:watchhentai:lintKotlinMain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/en/watchhentai/src/eu/kanade/tachiyomi/animeextension/en/watchhentai/WatchHentai.kt
git commit -m "fix(watchhentai): fix series poster extraction in animeDetailsParse"
```

---

### Task 2: Implement Series Poster Fetching and Caching in `latestUpdatesParse`

**Files:**
- Modify: `src/en/watchhentai/src/eu/kanade/tachiyomi/animeextension/en/watchhentai/WatchHentai.kt:575-593`

**Interfaces:**
- Consumes: `SAnime.url`, `extractPosterFromDoc(document: Document)`, OkHttp `client`
- Produces: `fetchSeriesPoster(seriesUrl: String): String?` and updated `latestUpdatesParse(response: Response): AnimesPage`

- [ ] **Step 1: Add poster cache and `fetchSeriesPoster` helper**

In `WatchHentai.kt`:
```kotlin
private val seriesPosterCache = java.util.concurrent.ConcurrentHashMap<String, String>()

private fun fetchSeriesPoster(seriesUrl: String): String? {
    if (seriesUrl.isBlank()) return null
    seriesPosterCache[seriesUrl]?.let { return it }

    val fullUrl = if (seriesUrl.startsWith("http")) seriesUrl else "$baseUrl$seriesUrl"
    return try {
        client.newCall(GET(fullUrl, headers)).execute().use { response ->
            if (!response.isSuccessful) return null
            val document = response.asJsoup()
            extractPosterFromDoc(document)?.also { posterUrl ->
                seriesPosterCache[seriesUrl] = posterUrl
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("WatchHentai", "Failed fetching series poster for $seriesUrl: ${e.message}")
        null
    }
}
```

- [ ] **Step 2: Update `latestUpdatesParse` to enrich anime entries with series poster**

In `latestUpdatesParse`:
After `distinctBy { it.url }`:
```kotlin
animes.forEach { anime ->
    if (anime.url.startsWith("/series/")) {
        val seriesPoster = fetchSeriesPoster(anime.url)
        if (!seriesPoster.isNullOrBlank()) {
            anime.thumbnail_url = seriesPoster
        }
    }
}
```

- [ ] **Step 3: Run Kotlin compilation and linter**

Run: `./gradlew.bat :src:en:watchhentai:compileDebugKotlin :src:en:watchhentai:lintKotlinMain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/en/watchhentai/src/eu/kanade/tachiyomi/animeextension/en/watchhentai/WatchHentai.kt
git commit -m "feat(watchhentai): fetch general series poster in latest updates feed"
```

---

### Task 3: Bump Extension Version and Run End-to-End Verification

**Files:**
- Modify: `src/en/watchhentai/build.gradle:4`

- [ ] **Step 1: Bump `extVersionCode`**

In `src/en/watchhentai/build.gradle`:
Change `extVersionCode = 21` to `extVersionCode = 22`.

- [ ] **Step 2: Run full build and ktlint**

Run: `./gradlew.bat :src:en:watchhentai:assembleDebug :src:en:watchhentai:lintKotlinMain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Verification of HTML extraction with test snippet**

Verify that `extractPosterFromDoc` extracts the expected poster URL (`https://watchhentai.net/uploads/2026/the-lonely-snow-widow-and-the-cursed-ring/poster.jpg`) using a standalone script on `series.html`.

- [ ] **Step 4: Commit**

```bash
git add src/en/watchhentai/build.gradle
git commit -m "chore(watchhentai): bump extVersionCode to 22"
```
