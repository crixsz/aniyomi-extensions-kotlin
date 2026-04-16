#!/usr/bin/env node

const fs = require('node:fs');
const path = require('node:path');

function collectM3u8UrlsFromText(text) {
  if (!text) return [];
  return Array.from(new Set(text.match(/https:\/\/[^\s"\\]+?\.m3u8(?:\?[^\s"\\]*)?/g) || []));
}

async function verifyM3u8Url(url) {
  if (!url) {
    return { ok: false, reason: 'missing_url' };
  }

  try {
    const response = await fetch(url, {
      headers: {
        'user-agent':
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36',
      },
    });

    const text = await response.text();
    return {
      ok: response.ok && text.includes('#EXTM3U'),
      status: response.status,
      contentType: response.headers.get('content-type'),
      sample: text.slice(0, 500),
    };
  } catch (error) {
    return {
      ok: false,
      reason: String(error),
    };
  }
}

async function main() {
  let playwright;
  try {
    playwright = require('playwright');
  } catch (error) {
    console.error('Missing dependency: playwright');
    console.error('Install it with: npm install -D playwright');
    process.exit(1);
  }

  const { chromium } = playwright;
  const targetUrl =
    process.argv[2] || 'https://hanime.tv/videos/hentai/cool-de-m-2';
  const outputPath = path.resolve(
    process.argv[3] || 'hanime-stream-probe-output.json',
  );

  const interestingPattern = /(m3u8|mpd|mp4|highwinds|streamable\.cloud|playlist|manifest|token|license|segment|player)/i;
  const log = {
    startedAt: new Date().toISOString(),
    targetUrl,
    requests: [],
    responses: [],
    console: [],
    pageErrors: [],
    summary: {},
  };

  const browser = await chromium.launch({
    headless: false,
    args: ['--disable-blink-features=AutomationControlled'],
  });

  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    userAgent:
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36',
  });

  const page = await context.newPage();

  page.on('console', (message) => {
    log.console.push({
      type: message.type(),
      text: message.text(),
    });
  });

  page.on('pageerror', (error) => {
    log.pageErrors.push(String(error));
  });

  page.on('request', (request) => {
    const url = request.url();
    if (!interestingPattern.test(url) && !['media', 'xhr', 'fetch'].includes(request.resourceType())) {
      return;
    }

    log.requests.push({
      time: new Date().toISOString(),
      method: request.method(),
      resourceType: request.resourceType(),
      url,
      headers: request.headers(),
      postData: request.postData() || null,
    });
  });

  page.on('response', async (response) => {
    const request = response.request();
    const url = response.url();
    if (!interestingPattern.test(url) && !['media', 'xhr', 'fetch'].includes(request.resourceType())) {
      return;
    }

    let bodySample = null;
    try {
      const contentType = response.headers()['content-type'] || '';
      if (/json|javascript|text|mpegurl|xml/i.test(contentType)) {
        bodySample = (await response.text()).slice(0, 4000);
      }
    } catch {
      bodySample = null;
    }

    log.responses.push({
      time: new Date().toISOString(),
      status: response.status(),
      resourceType: request.resourceType(),
      url,
      headers: response.headers(),
      bodySample,
    });
  });

  await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(3000);

  const nuxtSnapshot = await page.evaluate(() => {
    const nuxt = window.__NUXT__ || {};
    const video = nuxt.state?.data?.video || {};
    return {
      nuxtKeys: Object.keys(nuxt),
      stateKeys: Object.keys(nuxt.state || {}),
      videoKeys: Object.keys(video),
      id: video.id || null,
      slug: video.slug || null,
      name: video.name || null,
      posterUrl: video.poster_url || null,
      coverUrl: video.cover_url || null,
      videosManifest: video.videos_manifest || null,
    };
  });

  log.summary.nuxtSnapshot = nuxtSnapshot;

  const dismissSelectors = [
    'button:has-text("Close Ad")',
    'button:has-text("CLOSE AD")',
    '.dialog__container button',
    '[aria-label="Close"]',
  ];

  for (const selector of dismissSelectors) {
    try {
      const button = page.locator(selector).first();
      if (await button.isVisible({ timeout: 1000 })) {
        await button.click({ timeout: 3000 });
        await page.waitForTimeout(1000);
      }
    } catch {
      // Best effort only.
    }
  }

  const playSelectors = [
    '.hvp-panel',
    '.play-btn',
    '.htv-video-player .poster',
    '.htv-video-player',
  ];

  let clickedPlay = false;
  for (const selector of playSelectors) {
    try {
      const target = page.locator(selector).first();
      if (await target.isVisible({ timeout: 1500 })) {
        await target.click({ force: true, timeout: 5000 });
        clickedPlay = true;
        await page.waitForTimeout(4000);
        break;
      }
    } catch {
      // Try the next selector.
    }
  }

  const playbackState = await page.evaluate(() => {
    const video = document.querySelector('video');
    return {
      clickedPlay: null,
      hasVideoTag: Boolean(video),
      currentSrc: video?.currentSrc || null,
      readyState: video?.readyState ?? null,
      networkState: video?.networkState ?? null,
      paused: video?.paused ?? null,
      playerHtml: document.querySelector('.htv-video-player')?.innerHTML || null,
    };
  });

  playbackState.clickedPlay = clickedPlay;
  log.summary.playbackState = playbackState;

  await page.waitForTimeout(10000);

  const candidateUrls = Array.from(
    new Set(
      [...log.requests, ...log.responses]
        .map((entry) => entry.url)
        .filter((url) => interestingPattern.test(url)),
    ),
  );

  const capturedM3u8Urls = Array.from(
    new Set(
      [
        ...candidateUrls.filter((url) => url.includes('.m3u8')),
        ...log.responses.flatMap((entry) => collectM3u8UrlsFromText(entry.bodySample)),
      ],
    ),
  );

  const playableM3u8Url =
    capturedM3u8Urls.find((url) => url.includes('highwinds-cdn.com')) ||
    capturedM3u8Urls[0] ||
    null;

  const playableM3u8Verification = await verifyM3u8Url(playableM3u8Url);

  log.summary.candidateUrls = candidateUrls;
  log.summary.capturedM3u8Urls = capturedM3u8Urls;
  log.summary.playableM3u8Url = playableM3u8Url;
  log.summary.playableM3u8Verification = playableM3u8Verification;
  log.summary.requestCount = log.requests.length;
  log.summary.responseCount = log.responses.length;
  log.finishedAt = new Date().toISOString();

  fs.writeFileSync(outputPath, JSON.stringify(log, null, 2));

  console.log(`Saved probe output to ${outputPath}`);
  console.log(`Clicked play overlay: ${clickedPlay}`);
  console.log(`Interesting URLs found: ${candidateUrls.length}`);
  console.log(`Playable m3u8 URL: ${playableM3u8Url || 'not found'}`);
  console.log(`Playable m3u8 verified: ${playableM3u8Verification.ok}`);

  await browser.close();
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
