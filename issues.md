# Hanime Extension Investigation Report
**Date:** 2026-04-17
**Status:** DEADLOCK (Requires App-Level Changes or WASM Reverse Engineering)

## Objective
Fix video playback for Guest and Authenticated users on Hanime.tv in Aniyomi.

## Root Cause Analysis
Hanime.tv has changed its delivery architecture so the extension can no longer obtain a usable stream from static HTML or the old API alone.

1. **Broken Placeholder in Static Data**: The page and old API paths still expose `https://streamable.cloud/hls/stream.m3u8`, but that URL is not playable and returns failures.
2. **Real Stream Only Appears in Browser Runtime**: The actual playable `.m3u8` is revealed only after the site JS initializes the player flow and requests a signed guest manifest.
3. **Guest Manifest Is Protected**: The real browser flow calls `https://cached.freeanimehentai.net/api/v8/guest/videos/<hv_id>/manifest`, but direct requests to that endpoint return `401 Unauthorized`.
4. **Runtime Signer Is Required**: The request needs runtime-generated headers including `x-signature`, `x-time`, and related token/license headers.
5. **Signer Comes From WASM**: The frontend does not compute the signer in normal JS. It loads an embedded WebAssembly blob that eventually assigns `window.ssignature` and `window.stime`.
6. **Logged-In HTML Remains Hostile to Parsing**: The `__NUXT__` payload for authenticated pages is still emitted as executable JS rather than clean JSON, so static parsing remains fragile even before the signer problem.

## New Confirmed Findings (2026-04-17)

1. **The site still has a working guest HLS stream**.
2. **The real playback flow uses the signed guest manifest endpoint**:
   `https://cached.freeanimehentai.net/api/v8/guest/videos/3427/manifest`
3. **That manifest returns real Highwinds `.m3u8` URLs**, for example:
   `https://m3u8s.highwinds-cdn.com/api/v9/m3u8s/s3snkAsfAspvxmwhzzwzf20gsyZlf62dsr7vx9qnxpwsvwv1f8g41.m3u8`
4. **That captured `.m3u8` is genuinely playable**:
   - Valid HLS playlist data is returned
   - AES key `https://hanime.tv/sign.bin` is reachable
   - Media segment URLs are reachable
   - `ffprobe` detects playable `h264` video and `aac` audio
5. **The static page state is still misleading**: the embedded `videos_manifest` in HTML still points to the dead `streamable.cloud` URL, not the real Highwinds manifest.

## Signed Request Details
The working browser request to the guest manifest uses these headers:

- `x-signature-version: web2`
- `x-signature: <runtime-generated>`
- `x-time: <runtime-generated>`
- `x-session-token`
- `x-user-license`
- `x-license`
- `x-csrf-token`

Observed runtime globals:

- `window.ssignature`
- `window.stime`

The frontend waits until `window.stime` becomes non-zero before continuing with signed API requests.

## WASM Signer Discovery
The signer is produced by embedded WASM, not plain JavaScript.

Relevant frontend behavior recovered from the browser bundles:

```js
Module["ccall"] = ccall;
var ASM_CONSTS = {
  17392: () => parseInt((new Date).getTime() / 1e3),
  17442: ($0, $1) => {
    window.ssignature = UTF8ToString($0);
    window.stime = $1;
  },
};
```

Other observed runtime behavior:

- JS initializes `window.ssignature = ""` and `window.stime = 0`
- JS polls until `window.stime` is set
- JS forwards browser/window events into WASM via `Module.ccall("on_window_event", ...)`
- The WASM blob is embedded in the vendor bundle and decoded at runtime

This means a simple Kotlin patch that swaps endpoints is not enough. The extension would also need to reproduce the signer logic or get the result from an app-level browser flow.

## Strategies Attempted & Results

| Strategy | Description | Result |
| :--- | :--- | :--- |
| **1. Guest API** | Standard extraction using `/api/v8/video`. | Failed. Returns broken `streamable.cloud` placeholder. |
| **2. Auth API** | Calling API with valid session cookie. | Failed. Still returns broken `streamable.cloud` placeholder. |
| **3. Regex HTML** | Scanning authenticated HTML for `.m3u8` links. | Failed. No valid Highwinds URL in static source. |
| **4. Header Spoofing** | Accessing placeholder URL with `Referer: hanime.tv`. | Failed. Placeholder remains unusable. |
| **5. Player URL** | Returning `https://player.hanime.tv/...` as video. | Failed. Aniyomi player cannot play generic HTML player pages. |
| **6. Browser Playback Probe** | Launch real browser, click poster overlay, capture playback network. | Success. Revealed signed guest manifest request and real Highwinds `.m3u8`. |
| **7. Direct Guest Manifest Call** | Call `/api/v8/guest/videos/<hv_id>/manifest` without signer. | Failed. Returns `401 Unauthorized`. |
| **8. Bundle Inspection** | Reverse engineer frontend JS for signer path. | Partial success. Confirmed the signer is produced by embedded WASM. |

## Current Code State
The extension code has not been switched to the guest manifest flow yet.

Reason:

- The endpoint is protected by runtime-generated signer headers.
- A direct extension patch would still fail with `401 Unauthorized`.
- The current Kotlin code remains in a best-effort fallback state rather than a working extractor.

Additional local investigation artifacts:

- `hanime-stream-probe.js`: Playwright probe to simulate playback and capture the real stream URL
- `hanime-stream-probe-output.json`: captured network output showing the signed manifest flow and working Highwinds URL

## Most Plausible Next Steps
Since the extension cannot execute the site JS/WASM runtime by itself:

1. **App-Level WebView Interception**: Most plausible practical fix. Add support in the Aniyomi app to load the Hanime page in a hidden/offscreen WebView, let JS/WASM run normally, and intercept the signed manifest or final Highwinds request.
2. **WASM Reverse Engineering**: Most plausible extension-only research path. Reverse engineer the embedded signer and reimplement it in Kotlin.
3. **External Helper Service**: Technically workable fallback. A helper process could run the browser logic and return signed manifests, but this is less desirable for a normal upstream extension.

## Practical Conclusion

- **Most plausible short-term usable fix:** app-level WebView/network interception in the Aniyomi app repo, not the extension repo.
- **Most plausible extension-only path:** reverse engineer the WASM signer and port it.
- **Not plausible as-is:** a simple extension patch that only swaps to the guest manifest endpoint.
