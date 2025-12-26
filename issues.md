# Hanime Extension Investigation Report
**Date:** 2025-12-26
**Status:** 🛑 DEADLOCK (Requires App-Level Changes or Deep Reverse Engineering)

## 🎯 Objective
Fix video playback for Guest and Authenticated users on Hanime.tv service in Aniyomi.

## 🔍 Root Cause Analysis
Hanime.tv has fundamentally changed its video delivery architecture to prevent direct scraping by extensions.

1.  **Dynamic Token Generation**: The valid `.m3u8` URL (hosted on `highwinds-cdn.com`) is no longer present in the API response or static HTML. It is constructed at runtime by the video player's JavaScript, likely using a cryptographic token generated via `postMessage` communication with the parent page.
2.  **Broken Placeholders**: The API and HTML now provide a placeholder URL `https://streamable.cloud/hls/stream.m3u8`. This URL returns network errors/404s even when accessed with valid User-Agent, Referer, and Session Cookies.
3.  **Obfuscated Data**: For logged-in users, the `__NUXT__` data payload in the HTML has been converted from standard JSON to a self-executing JavaScript function ([(function(){...}())](file:///c:/Users/pikai/Projects/aniyomi-extensions-kotlin/src/en/hanime/src/eu/kanade/tachiyomi/animeextension/en/hanime/Hanime.kt#216-217)), breaking standard JSON parsers.

## 🧪 Strategies Attempted & Results

| Strategy | Description | Result |
| :--- | :--- | :--- |
| **1. Guest API** | Standard extraction using `/api/v8/video`. | ❌ **Failed.** Returns broken `streamable.cloud` placeholder. |
| **2. Auth API** | Calling API with valid Session Cookie. | ❌ **Failed.** Still returns broken `streamable.cloud` placeholder. Confirmed API is deprecated for direct stream access. |
| **3. Regex HTML** | Scanning Authenticated HTML for `.m3u8` links. | ❌ **Failed.** Logged page content dump confirmed NO valid `highwinds` URL exists in the source code. |
| **4. Header Spoofing** | Accessing placeholder URL with `Referer: hanime.tv`. | ❌ **Failed.** URL remains inaccessible. |
| **5. Player URL** | Returning `https://player.hanime.tv/...` as video. | ❌ **Failed.** Aniyomi Player (MPV) cannot parse the generic HTML page as a video file ("Unrecognized file format"). |

## 🛠️ Current Code State (Best Effort)
The codebase has been left in the **Authenticated Regex** state ([fetchVideoListPremium](file:///c:/Users/pikai/Projects/aniyomi-extensions-kotlin/src/en/hanime/src/eu/kanade/tachiyomi/animeextension/en/hanime/Hanime.kt#135-147)):
*   **Authentication Required:** Forces user to login via WebView (best chance standard flow).
*   **Regex Extraction:** Uses Regex instead of JSON parsing to avoid crashing on the JS function data.
*   **Headers:** Adds `Referer` and `Origin` headers to any found URLs.
*   *Note:* Currently returns "No Videos Found" because the URLs are hidden, but this is the most robust implementation should the site revert its changes.

## 🚀 Recommendations for Future Work
Since the extension cannot execute JavaScript or spawn background WebViews:
1.  **App-Level WebView Interception:** Aniyomi app needs a feature to allow extensions to request a URL be loaded in a hidden WebView, and then intercept the network traffic to capture the `highwinds` URL (similar to IDM).
2.  **Reverse Engineering:** Decompiling the `app.js` and `_nuxt` bundles to replicate the token generation algorithm in Kotlin. This is high-effort and high-maintenance (breaks on every site update).
