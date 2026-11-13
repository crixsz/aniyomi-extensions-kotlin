# Design Doc: WatchHentai General Series Thumbnail Support

## Intent and Why
Currently in the WatchHentai extension, anime entries retrieved from the latest updates feed (`/videos/`) display episode screenshot thumbnails (e.g., `https://watchhentai.net/uploads/2026/.../4/1_thumb.jpg`) rather than the general series poster (e.g., `https://watchhentai.net/uploads/2026/.../poster.jpg`). Furthermore, on the series details page, `animeDetailsParse` fails to extract the series poster because Jsoup's `attr("data-src")` returns an empty string `""` (instead of `null`) when absent, preventing the fallback to `attr("src")`.

The goal is to ensure WatchHentai uses the general series poster for anime both in the Latest Updates feed and on the series details page.

## Goals, Non-Goals, and Anti-Goals
- **Goals**:
  1. Fix `animeDetailsParse` so that it correctly retrieves the series poster (using `data-src` if non-blank, otherwise falling back to `src`).
  2. Enhance `latestUpdatesParse` to resolve the general series poster for each distinct anime entry by fetching the series page.
  3. Gracefully fall back to the episode thumbnail if the series page request fails or does not contain a poster image.
  4. Bump `extVersionCode` in `src/en/watchhentai/build.gradle`.
- **Non-Goals**:
  - Changing episode thumbnails in the episode list itself (`SEpisode.scanlator` should continue storing individual episode screenshots).
  - Modifying the Popular/Trending feed or Search feed, which already extract `poster.jpg`.
- **Anti-Goals**:
  - Breaking feed deduplication or introducing blocking network timeouts that fail the entire page if one series page times out.

## Approaches Considered
- **Approach 1 (Selected)**: Keep `/videos/` for the latest episode releases feed. In `latestUpdatesParse`, after deduplicating items by series URL, resolve the series poster by requesting the series page. Also fix `animeDetailsParse` hero image selector to properly fall back to `src`.
  - *Trade-off*: Accurately reflects new episode releases while showing the official series poster for each series card, with a minor overhead of fetching series pages for unique items on the page.
- **Approach 2 (Discarded)**: Switch `latestUpdatesRequest` to `$baseUrl/series/`.
  - *Trade-off*: Fast with no extra requests, but `/series/` sorts by series creation date rather than episode release date, so ongoing series with new episodes do not surface at the top.

## Technical Details

### 1. Fixing `animeDetailsParse`
In `animeDetailsParse`:
```kotlin
val thumbnailElement = document.selectFirst("div.summary_image img")
    ?: document.selectFirst("div.sheader div.poster img")
    ?: document.selectFirst("div.poster img")
anime.thumbnail_url = thumbnailElement?.attr("data-src")?.takeIf { it.isNotBlank() }
    ?: thumbnailElement?.attr("src")?.takeIf { it.isNotBlank() }
```

### 2. Fetching General Poster in `latestUpdatesParse`
In `latestUpdatesParse`:
- Extract distinct `SAnime` items as currently done.
- For each unique `anime` whose URL points to `/series/`:
  - Fetch the series page using `client.newCall(GET(baseUrl + anime.url, headers)).execute()`.
  - Parse the hero poster (`div.sheader div.poster img` or `div.poster img`) using `data-src` / `src`.
  - If a valid thumbnail is found, assign it to `anime.thumbnail_url`; otherwise retain the episode thumbnail as fallback.
  - Handle exceptions gracefully so individual series fetch failures don't abort the page.
- Optional cache (e.g., in-memory `LruCache` or simple concurrent map) to avoid re-fetching series posters across multiple pages or repeated visits within the same session.

### 3. Extension Version
Increment `extVersionCode` from 21 to 22 in `src/en/watchhentai/build.gradle`.
