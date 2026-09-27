# DebForge

An Android app for downloading files from your debrid service straight to your
phone, tablet or Android TV. It works with TorBox, Real-Debrid, AllDebrid,
Premiumize and Debrid-Link.

[<img src="docs/badges/obtainium.png" alt="Get it on Obtainium" height="48">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%20%22com.abhinavxt.debforge%22%2C%20%22url%22%3A%20%22https%3A%2F%2Fgithub.com%2FAbhinavXT%2Fdebforge%22%2C%20%22author%22%3A%20%22AbhinavXT%22%2C%20%22name%22%3A%20%22DebForge%22%7D)
<!-- Uncomment once DebForge is live on F-Droid:
[<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="60">](https://f-droid.org/packages/com.abhinavxt.debforge/)
-->

## Features

- **One app, many services.** Sign in to as many as you like and switch in Settings.
- **Fast, resumable downloads.** Up to 16 parallel connections per file, pause and
  resume, and automatic link refresh when a link expires.
- **Library view.** Posters and clean titles (e.g. "The Bear · S02E05 · 1080p"),
  series grouped into seasons, and search across your whole account.
- **Add from anywhere.** Tap a magnet link, share a URL, or open a `.torrent`
  file. DebForge sends it to your service and, if you want, downloads it when it's ready.
- **Play without downloading.** Stream any file in VLC, MX Player or another player.
- **Download rules.** Wi-Fi only, while charging, a nightly time window, and a speed limit.
- **Organised library.** Optional Plex/Jellyfin layout
  (`Shows/Name/Season 01`, `Movies/Name (Year)`).
- **TorBox extras.** Download a whole torrent as a single zip.
- **Android TV and tablets.** Leanback launcher support and a side navigation rail
  on wide screens.
- **Languages.** English and Hindi, with a per-app language setting on Android 13+.

## Supported services

| Service      | Sign-in              | Add magnets | Add .torrent | Add links |
|--------------|----------------------|:-----------:|:------------:|:---------:|
| TorBox       | API key              | ✓           | ✓            | ✓         |
| Real-Debrid  | Device code or token | –           | –            | ✓         |
| AllDebrid    | PIN or API key       | ✓           | ✓            | ✓         |
| Premiumize   | API key              | ✓           | ✓            | ✓         |
| Debrid-Link  | API key              | ✓           | ✓            | ✓         |

To add another service, see [PROVIDERS.md](PROVIDERS.md). It takes one package and two lines of wiring.

**Posters** come from [TMDB](https://www.themoviedb.org/). Get a free API key at
themoviedb.org → Settings → API and paste it in the app under Settings → Posters.

## Install

Android 11 or newer is required. Pick one:

- **Obtainium** (recommended until DebForge is on F-Droid): tap the badge above,
  or add `https://github.com/AbhinavXT/debforge` in Obtainium. It installs from
  GitHub Releases and keeps DebForge updated.
- **F-Droid**: submission in progress.
- **Manually**: download `DebForge-vX.Y.Z.apk` from
  [Releases](../../releases/latest) and open it. The app then tells you when a
  new version is out.

All three give you the **same APK signed with the same key**, so you can move
between them without uninstalling or losing your settings. When F-Droid or
Obtainium installed the app, DebForge's own update check starts switched off,
because they already handle updates. You can turn it on in Settings → About.

## Privacy

- API keys are encrypted on the device with a key held in the Android Keystore,
  and are excluded from backups.
- The app talks only to your debrid service, to TMDB for posters (if you add a
  key), and to GitHub to check for updates (you can turn this off in
  Settings → About).
- There are no analytics and no crash-reporting SDK. If the app crashes, a
  report with tokens, links and file names removed is saved on the device. On the
  next launch you're asked whether to share it; nothing is sent automatically.

## Build

Requirements: JDK 21 and the Android SDK (Android Studio is the easiest way to get both).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # unit tests
```

Optional: put `TMDB_API_KEY=your_key` in `local.properties` (never committed)
and debug builds show posters without entering a key in the app. Release builds
never include a key, so they stay reproducible.

## Continuous integration

- **`.github/workflows/ci.yml`** runs on every push and pull request. It builds the
  debug APK, runs the unit tests, and uploads the APK and test reports as
  workflow artifacts. A red check means the code doesn't compile or a test failed.
- **`.github/workflows/release.yml`** runs when you push a `v*` tag. It checks
  the tag matches the app version, builds the signed APK **twice** from clean and
  fails if the two differ (F-Droid would reject a non-reproducible APK), then
  publishes a GitHub Release. The release notes come from the fastlane changelog,
  and the job summary shows the signing-certificate SHA-256 that F-Droid needs.

## Releasing

### One-time setup

1. Create a signing key. Keep it safe: every future update must be signed with it.
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias debforge \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. In the GitHub repo, go to **Settings → Secrets and variables → Actions** and add:
   - `KEYSTORE_BASE64`: the output of `base64 -w0 release.jks` (on macOS: `base64 -i release.jks`)
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
3. Optional, for signed builds on your own machine: create `keystore.properties`
   in the project root (it's git-ignored):
   ```properties
   storeFile=release.jks
   storePassword=...
   keyAlias=debforge
   keyPassword=...
   ```

### Each release

```bash
scripts/release.sh 1.2.1
git push && git push origin v1.2.1
```

The script:
1. Sets `versionName` and `versionCode` in `app/build.gradle.kts`. The code is
   `major*10000 + minor*100 + patch`, so 1.2.1 becomes 10201.
2. Drafts `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` from
   your commit messages and opens it in your editor. This text is the "What's new"
   on F-Droid and the GitHub release notes, and F-Droid allows at most 500 characters.
3. Commits and creates the tag.

The version numbers are plain literals, never computed at build time, because
F-Droid reads them straight from the file without running Gradle.

## Listing in Obtainium's app directory

The badge above already works on its own. Adding DebForge to
[Obtainium's app directory](https://apps.obtainium.imranr.dev/) also makes it
searchable from inside Obtainium.

1. Fork [ImranR98/apps.obtainium.imranr.dev](https://github.com/ImranR98/apps.obtainium.imranr.dev).
2. Copy `obtainium/com.abhinavxt.debforge.json` from this repo to
   `public/data/apps/simple/com.abhinavxt.debforge.json` in the fork. It goes under
   `simple/` because DebForge works with Obtainium's default settings: one APK
   per release, and pre-releases ignored.
3. Open a pull request. Their [app criteria](https://github.com/ImranR98/apps.obtainium.imranr.dev/blob/main/APP_CRITERIA.md)
   only accept configs that point at the official source, which this one does.

Keep each release to a single `.apk` asset (the `.sha256` file is fine). If a
release ever ships several APKs, the entry needs an `apkFilterRegEx` and has
to move to `complex/`.

## Publishing on F-Droid

DebForge uses F-Droid's **reproducible build** path. F-Droid builds the tagged
source itself, compares the result with the APK on GitHub, and if they match it
publishes *your* signed APK.

What's already in this repo:
- `LICENSE` (GPL-3.0-or-later)
- `fastlane/metadata/android/` with the store texts in English and Hindi, the
  changelog, the icon and the feature graphic
- `fdroid/com.abhinavxt.debforge.yml`, a draft build recipe

Steps:

1. **Add screenshots.** Put 2–8 phone screenshots in
   `fastlane/metadata/android/en-US/images/phoneScreenshots/` as `1.png`,
   `2.png` and so on. Library, poster view, Downloads and Add work well. You can
   also add `tenInchScreenshots/` and `tvScreenshots/`.
2. **Release v1.2.0** with `scripts/release.sh 1.2.0` and push the tag. Wait
   for the release job to go green.
3. **Copy the certificate.** Open the release run's summary and copy the
   "Signing certificate SHA-256" value into `AllowedAPKSigningKeys` in
   `fdroid/com.abhinavxt.debforge.yml`.
4. **Submit.** Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata) on GitLab,
   add the recipe as `metadata/com.abhinavxt.debforge.yml`, and open a merge
   request using the "App inclusion" template. The pipeline on your merge
   request builds the app and runs the reproducibility check. Reviewers may ask
   for changes; approval usually takes days to a few weeks.
5. **After it's live,** uncomment the F-Droid badge at the top of this README.
   From then on F-Droid picks up each new `v*` tag automatically. You only run
   `scripts/release.sh`, and the F-Droid update appears a few days after the
   GitHub release.

If F-Droid reports that the APKs differ, the build isn't reproducible yet. The
usual cause in Android apps is the baseline profile (`assets/dexopt/baseline.prof`).
Open an issue here with the diff F-Droid prints.

## License

DebForge is free software under the [GNU General Public License v3.0 or later](LICENSE).
