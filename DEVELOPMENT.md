# Developing DebForge

Building, translating, releasing and publishing. For using the app, see the [README](README.md). To add a debrid service, see [PROVIDERS.md](PROVIDERS.md).

## Translations

App text lives in `app/src/main/res/values*/strings.xml` (English in `values/`).
To fix or add a translation, edit that language's file and run:

```bash
python3 scripts/check_translations.py
```

It checks every language against English: missing or extra strings,
placeholders like `%1$s`, unescaped apostrophes, and the plural forms each
language needs. CI runs it on every push. A new language also needs its tag in
`res/xml/locales_config.xml` and in `LANGUAGES` in `SettingsScreen.kt`, plus
store texts in `fastlane/metadata/android/<locale>/`.

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
- **`.github/workflows/screenshots.yml`** runs when you start it from the Actions
  tab. It renders the real app with demo data on the JVM (Robolectric +
  Roborazzi: no emulator, no account) and commits the store screenshots to
  `fastlane/metadata/android/en-US/images/phoneScreenshots/`. The same run
  locally: `./gradlew recordRoborazziDebug -Pscreenshots` (PNGs land in
  `app/build/screenshots/`). Normal test runs skip it.

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

The Obtainium badge in the README already works on its own. Adding DebForge to
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

1. **Screenshots.** Run the **Screenshots** workflow (Actions → Screenshots →
   Run workflow). It commits `phoneScreenshots/1.png`–`4.png` for you. Run it
   again whenever the UI changes, before tagging a release, since F-Droid reads
   them from the tagged commit.
2. **Release v1.1.2** with `scripts/release.sh 1.1.2` and push the tag. Wait
   for the release job to go green.
3. **Copy the certificate.** Open the release run's summary and copy the
   "Signing certificate SHA-256" value into `AllowedAPKSigningKeys` in
   `fdroid/com.abhinavxt.debforge.yml`.
4. **Submit.** Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata) on GitLab,
   add the recipe as `metadata/com.abhinavxt.debforge.yml`, and open a merge
   request using the "App inclusion" template. The pipeline on your merge
   request builds the app and runs the reproducibility check. Reviewers may ask
   for changes; approval usually takes days to a few weeks.
5. **While the merge request is still open,** each new release also goes into
   it: after the release job is green, copy `fdroid/com.abhinavxt.debforge.yml`
   over `metadata/com.abhinavxt.debforge.yml` in your fdroiddata branch, commit,
   push, and say in a comment what changed (new version, new dependencies or
   network services). The pipeline builds the new version again.
6. **After it's live,** uncomment the F-Droid badge at the top of README.md.
   From then on F-Droid picks up each new `v*` tag automatically. You only run
   `scripts/release.sh`, and the F-Droid update appears a few days after the
   GitHub release.

If F-Droid reports that the APKs differ, the build isn't reproducible yet. The
usual cause in Android apps is the baseline profile (`assets/dexopt/baseline.prof`).
Open an issue here with the diff F-Droid prints.

