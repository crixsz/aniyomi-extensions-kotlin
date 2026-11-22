# HentaiHavenXX HLS seek / resume investigation

Date: 2026-11-22

## Summary

Playback from the beginning works. Seeking, and resuming from a saved position,
gets stuck on a loading spinner. The same failure reproduces with desktop `mpv`
using the raw HLS URL, which means the problem is in the player's HLS
implementation, not in the extension, the site's ads, or a corrupt upload.

Affected extension: `src/en/hentaihavenxx` (v14.6).

## Symptoms

- Episode starts and plays normally from `0:00`.
- Tapping seek / dragging the seek bar to another position shows an endless
  spinner, and playback never resumes.
- Opening a partially watched episode (resume) shows the same spinner.
- Aniyomi can ANR while waiting (main thread blocked in `PlayerActivity`).

## Environment used

- Emulator: `xyz.jmir.tachiyomi.mi.dev` (Aniyomi 0.18.1.2-8120).
- Phone: Aniyomi 0.18.1.3-8121.
- Also tested: Aniyomi 0.18.2.1 release (latest at the time) — same failure,
  and the player ANR'd.
- Desktop: mpv 0.36.0 with the raw HLS playlist URL.

## Evidence

### Desktop mpv, seeking during playback

After dragging to `3:00`, mpv does not jump to the target segment. It walks the
audio playlist from the beginning:

```text
lavf: queuing seek to 180.000000
cplayer: hr-seek, skipping to 180.000000
lavf: seek done
HLS request .../snd/snd4.html
...
HLS request .../snd/snd195.html
```

During a 45 s capture: 115 segment requests total, 109 were audio, only 3 video.
With ~580 segments that is minutes of loading.

### Desktop mpv, decode errors after seek

```text
[ffmpeg] NULL: Invalid NAL unit size (-1540533205 > 10934).
[ffmpeg] NULL: missing picture in access unit with size 10938
[ffmpeg/demuxer] mov,mp4,m4a,3gp,3g2,mj2: stream 0, offset 0x1ab7f: partial file
AV: 00:03:00 / 00:26:15 (11%) A-V: 0.000 Cache: 0.0s
(Paused) AV: 00:26:15 / 00:26:15 (100%)
```

`Invalid NAL unit size` plus `partial file` means the decoder is fed wrong or
misaligned H.264 data after the seek. Other runs also showed TLS errors:

```text
[ffmpeg] tls: mbedtls_ssl_handshake returned -0x4e
hls: Failed to open segment 90 of playlist 0
hls: Segment 90 of playlist 0 failed too many times, skipping
hls: The m3u8 list sequence may have been wrapped.
```

### Stream itself is valid

- Master and media playlists parse, are VOD (`#EXT-X-PLAYLIST-TYPE:VOD`,
  `#EXT-X-ENDLIST`) and have a normal duration.
- Sampled segments (`ha1`, `ha40`, `ha41`, `ha42`, `ha100`, `ha300`) all start
  with a keyframe (`key_frame=1`) after concatenating the `#EXT-X-MAP` init
  segment.
- `ffmpeg -ss 82 -i <master.m3u8> -frames:v 10 -f null -` decodes 10 frames
  with no errors.

So the media bytes are fine; the failure is in how the player seeks this
particular HLS layout.

## What was ruled out

| Hypothesis | Result |
| --- | --- |
| Ads / page scripts | Not involved. The extension extracts the direct CDN HLS URL; playlists contain no ad markers. |
| Referer / custom headers | Removing `Video.headers` changed nothing. |
| Master playlist vs video-only variant + side-loaded audio | Both behave identically. |
| Corrupt or truncated segments | Segments and keyframes are valid; init + segment decodes. |
| Extension code | Only ~150 lines extract the manifest; desktop mpv reproduces the bug with the same URL. |
| Outdated Aniyomi | Reproduced on 0.18.1.2, 0.18.1.3 and 0.18.2.1. |
| Rate limiting alone | May worsen results after repeated tests, but `Invalid NAL unit size` happens without TLS errors too. |

## Stream layout causing it

Both the working and failing episodes use the same provider layout:

```text
#EXTM3U
#EXT-X-VERSION:7
#EXT-X-MEDIA:URI="snd/a.m3u8",TYPE=AUDIO,GROUP-ID="audio",...
#EXT-X-MEDIA:URI="s/xx.vtt",TYPE=SUBTITLES,...
#EXT-X-STREAM-INF:BANDWIDTH=2077570,RESOLUTION=1280x720,...,AUDIO="audio"
7sop/v.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,...,AUDIO="audio"
3cop/v.m3u8
```

Media playlists are fragmented MP4:

```text
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-MAP:URI="https://<cdn>/<id>/7sop/i.mp4"
#EXTINF:2,
https://<cdn>/<id>/7sop/ha1.html
...
#EXT-X-ENDLIST
```

Notable properties:

- Separate audio rendition instead of muxed audio.
- fMP4 segments named `.html`.
- Segments served with `Content-Type: application/x-mpegURL` even though the
  body is fMP4 video.
- ~2 second segments, ~580-790 per episode.

Two CDN hosts were observed for the same provider; both show the failure:

| Episode | Segment host | Notes |
| --- | --- | --- |
| Hitori Tsuma ep. 1 | `fdsk2s.anpustream.com` (nginx) | failing |
| Rakuen Shinshoku ep. 2 | `estefania.top` (Cloudflare) | user reported "plays flawlessly"; seeking still failed in desktop mpv |

The browser player (`hls.js`) handles this layout fine because it requests each
segment independently and retries on failure.

## Root cause

mpv/ffmpeg's HLS demuxer does not seek this layout reliably:

1. On a precise seek it can walk the audio rendition from segment 1 instead of
   jumping to the target segment.
2. On a direct start/seek it can request the target audio segment, hit a
   transient TLS failure, skip it, and then feed misaligned fMP4 data to the
   decoder.
3. The result is `Invalid NAL unit size` / `missing picture` / `partial file`
   and no frames, which the UI shows as an endless loading spinner.

Aniyomi uses the same mpv/ffmpeg engine, so it inherits the same behaviour.

## Why the extension cannot fix it

- The extension only returns the HLS master URL. The player performs the seek.
- The extension API in this repo (`aniyomi-extensions-lib`) does not expose
  per-video player options (`mpvArgs`, etc.), so the extension cannot pass
  options such as `hr-seek=no`.
- The provider exposes no muxed-audio or progressive MP4 source for full
  episodes. The only `.mp4` files in the page are 30-second preview clips.
- Rewriting the playlist locally is not viable: mpv/ffmpeg blocks nested
  network playlists when the parent playlist is a local file or `data:` URI
  (`Protocol 'https' not on whitelist 'file,crypto,data'`).

## Workarounds

- Use the **WebView** button on the anime details page and watch on the site;
  the browser player seeks correctly.
- Use an external player with its own HLS stack (e.g. VLC).
- Avoid resume/seek in the built-in player; play from the start.

## Suggested upstream fix

Report to the mpv/ffmpeg or Aniyomi issue trackers with:

- The raw master playlist URL and one episode URL.
- The desktop reproduction above (`mpv --start=180 <master>`): audio segment
  crawl and/or `Invalid NAL unit size` / `partial file`.
- Note the provider layout: separate audio rendition, fMP4 `.html` segments,
  wrong `Content-Type`, ~2 s segments.

Likely fixes are provider-side standardisation (`.m4s`/`.ts` segments with
correct MIME types) or player-side seeking/retry handling in the HLS demuxer.

## Reproduction commands

```bash
# Play from start, then seek manually
mpv "https://octopusmanifest.org/<id>/playlist.m3u8"

# Reproduce the resume path
mpv --start=180 "https://octopusmanifest.org/<id>/playlist.m3u8"

# Verbose seek log
mpv --no-config --start=180 --vo=null --ao=null \
  --msg-level=all=v --log-file=mpv_seek.log \
  "https://octopusmanifest.org/<id>/playlist.m3u8"

# Verify the stream bytes are healthy
ffmpeg -ss 82 -i "https://octopusmanifest.org/<id>/playlist.m3u8" \
  -frames:v 10 -f null -
```
