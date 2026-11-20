# Publishing this fork as an Aniyomi/Anikku extension repo

This repository publishes its own extension repository, so the app can install and
update the extensions built here.

## Install link

```
https://raw.githubusercontent.com/crixsz/aniyomi-extensions-kotlin/repo/index.min.json
```

In Aniyomi/Anikku: **More → Settings → Browse → Extension repos → Add**, paste the
URL, then open **Extensions** to install. The same repo files can also be browsed at
[`repo` branch](https://github.com/crixsz/aniyomi-extensions-kotlin/tree/repo).

## What gets published

The `CI` workflow (`.github/workflows/build_push.yml`) runs on every push to
`master`. It builds the extensions changed by that push and updates the `repo`
branch:

| File / folder | Purpose |
| --- | --- |
| `index.min.json` | Extension index read by the app |
| `index.json` | Indented copy of the same index (debugging) |
| `index.html` | Simple browsable listing |
| `apk/` | Signed extension APKs |
| `icon/` | Extension icons |

Only changed extensions are rebuilt. A push that touches `lib/`, `core/`,
`buildSrc/`, Gradle config or `settings.gradle.kts` rebuilds everything.

## How in-app updates work

The app compares the version code in `index.min.json` with the installed
extension's version code. To ship an update:

1. Change the extension code.
2. Bump `extVersionCode` in `src/<lang>/<name>/build.gradle`.
3. Commit and push to `master`.

The workflow rebuilds that extension, replaces its APK and rewrites the index. The
app then shows an **Update** button.

Extensions that were never built are not listed. To publish everything at once, run
the workflow manually (**Actions → CI → Run workflow**) with `full_build` enabled.

## One-time GitHub setup

The APKs are signed with the same key used by the
`crixsz/tachiyomi-extensions-reborn` repo (`signingkey.jks` from that project).
Keeping one key means one set of secrets to manage. Every future update must use the
same key or the app will reject it.

Add these four repository secrets under **Settings → Secrets and variables →
Actions → New repository secret**:

| Secret | Value |
| --- | --- |
| `SIGNING_KEY` | contents of `signingkey.jks.base64` (one line) |
| `ALIAS` | `extensions` |
| `KEY_STORE_PASSWORD` | the store password saved when that key was generated |
| `KEY_PASSWORD` | the key password (same value) |

Or with the GitHub CLI:

```bash
gh secret set SIGNING_KEY < signingkey.jks.base64
gh secret set ALIAS --body extensions
gh secret set KEY_STORE_PASSWORD --body '<store-password>'
gh secret set KEY_PASSWORD --body '<key-password>'
```

`signingkey.jks` is no longer committed (it is in `.gitignore`). For local release
builds, place it in the repo root and export `ALIAS`, `KEY_STORE_PASSWORD` and
`KEY_PASSWORD`.

The optional API secrets (`MEGACLOUD_API`, `KISSKH_API`, `KISSKH_SUB_API`, `KAISVA`,
`TMDB_API`) can be added the same way if the extensions you publish need them.

Then push to `master`. The first run creates the `repo` branch automatically.

## How modules are selected

`settings.gradle.kts` reads `CI_MODULES` (comma-separated `lang/name`) so CI only
configures the extensions in the current build chunk. Without it, a CI run loads
every extension, and local development uses the small `devExtensions` list near the
top of the file.

## Notes and caveats

- These extensions are signed with a different key than the upstream
  `yuzono/aniyomi-extensions` repo. If an extension was installed from upstream,
  uninstall it once before installing from this repo. The same applies if you
  previously installed APKs signed with the `signingkey.jks` that was committed to
  this repo before this setup.
- Both repos can be added to the app at the same time. Duplicate packages resolve to
  one of them.
- PR builds still run `.github/workflows/build_pull_request.yml` as a compile check,
  but only pushes to `master` publish.
