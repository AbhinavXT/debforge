# DebForge

An Android app for downloading files from your debrid service straight to your
phone, tablet or Android TV. It works with TorBox, Real-Debrid, AllDebrid,
Premiumize and Debrid-Link.

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

## Privacy

- API keys are encrypted on the device with a key held in the Android Keystore,
  and are excluded from backups.
- The app talks only to your debrid service, to TMDB for posters (if you have a
  key configured), and to GitHub to check for updates (you can turn this off in
  Settings → About).
- There are no analytics and no crash-reporting SDK. If the app crashes, a
  report with tokens, links and file names removed is saved on the device. On the
  next launch you're asked whether to share it; nothing is sent automatically.

## Install

Download the latest `DebForge-vX.Y.Z.apk` from
[Releases](../../releases/latest) and open it on your device. You may need to
allow installs from your browser or file manager. Android 11 or newer is required.

## Build

Requirements: JDK 21 and the Android SDK (Android Studio is the easiest way to get both).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # unit tests
```

Optional `local.properties` entries (this file is never committed):

```properties
TMDB_API_KEY=your_tmdb_v3_key     # bundled default for posters; users can also set their own
GITHUB_REPO=owner/repo            # where the update check looks (default abhinavxt/debforge)
```

The version comes from git. `versionName` is the latest `v*` tag and
`versionCode` is the commit count, so you never edit them by hand.

## Continuous integration

- **`.github/workflows/ci.yml`** runs on every push and pull request. It builds the
  debug APK, runs the unit tests, and uploads the APK and test reports as
  workflow artifacts. A red check means the code doesn't compile or a test failed.
- **`.github/workflows/release.yml`** runs when you push a `v*` tag. It builds a
  signed release APK and publishes it as a GitHub Release.

## Releasing

One-time setup:

1. Create a signing key (keep it safe, because updates must be signed with the same key):
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias debforge \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. In the GitHub repo, go to **Settings → Secrets and variables → Actions** and add:
   - `KEYSTORE_BASE64`: the output of `base64 -w0 release.jks` (on macOS: `base64 -i release.jks`)
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
   - `TMDB_API_KEY` (optional)
3. For signed builds on your own machine, create `keystore.properties` in the
   project root (it's git-ignored):
   ```properties
   storeFile=release.jks
   storePassword=...
   keyAlias=debforge
   keyPassword=...
   ```

Each release:

```bash
git tag v1.2.0
git push origin v1.2.0
```

A few minutes later the release appears under Releases with the APK and its
SHA-256. Installed copies see it through the update banner. Tags with a hyphen
(`v1.3.0-beta.1`) are published as pre-releases, which the in-app check ignores.
