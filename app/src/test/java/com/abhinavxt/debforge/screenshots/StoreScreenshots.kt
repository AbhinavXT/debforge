package com.abhinavxt.debforge.screenshots

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.abhinavxt.debforge.HiltTestActivity
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.prefs.TokenCipher
import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.usage.UsageDao
import com.abhinavxt.debforge.domain.DownloadState
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.download.DownloadProgressTracker
import com.abhinavxt.debforge.ui.DebForgeRoot
import com.abhinavxt.debforge.ui.theme.DebforgeTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/**
 * Renders the real app (real screens and view-models, demo data from
 * [DemoProvider]) on the JVM and saves store screenshots to
 * app/build/screenshots/. Run with:
 *
 *     ./gradlew recordRoborazziDebug -Pscreenshots
 *
 * Excluded from normal test runs (see testOptions in app/build.gradle.kts).
 * The CI workflow "Screenshots" runs it and commits the PNGs to
 * fastlane/metadata/android/en-US/images/phoneScreenshots/.
 *
 * Everything happens in ONE test: DataStore instances are process-wide, so a
 * second test in the same JVM would see a different app sandbox.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A typical phone: 412x915 dp at 420 dpi = 1080x2400 px.
@Config(application = HiltTestApplication::class, sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class StoreScreenshots {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject lateinit var tokens: TokenStore
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var downloads: DownloadDao
    @Inject lateinit var usage: UsageDao
    @Inject lateinit var progress: DownloadProgressTracker

    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    @Before
    fun seed() {
        // Robolectric has no AndroidKeyStore; tokens get a throwaway software key.
        TokenCipher.testKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        hilt.inject()
        runBlocking {
            tokens.setToken(ProviderId.TORBOX, "demo-token")
            settings.setAutoUpdateCheck(false) // no GitHub call, no banner
            DemoData.downloads.forEach { downloads.upsert(it) }
            // A believable month of data use: mostly Wi-Fi, a little mobile.
            val today = LocalDate.now()
            (0 until 24).forEach { back ->
                val day = today.minusDays(back.toLong()).toString()
                val gb = 1024L * 1024 * 1024
                usage.add(day, ProviderId.TORBOX, metered = false, bytes = ((back * 37) % 11 + 1) * gb / 2)
                if (back % 5 == 0) usage.add(day, ProviderId.TORBOX, metered = true, bytes = gb / 3)
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path)
    }

    private fun text(res: Int) = compose.activity.getString(res)

    private fun waitForText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun storeScreenshots() {
        compose.setContent {
            DebforgeTheme(darkTheme = true, dynamicColor = false) { DebForgeRoot() }
        }

        // 1. Library: posters, processing strip.
        waitForText(DemoData.SHOW_TITLE)
        waitForText("Charge")
        shot("1")

        // 2. A series opened: seasons, download all, zip.
        compose.onAllNodesWithText(DemoData.SHOW_TITLE).onFirst().performClick()
        waitForText("E03")
        shot("2")
        compose.activity.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        // 3. Downloads, with a live speed on the running file.
        compose.onAllNodesWithText(text(R.string.tab_downloads)).onFirst().performClick()
        val running = DemoData.downloading
        val start = running.filesize * 58 / 100
        progress.update(running.id, start, running.filesize, DownloadState.DOWNLOADING)
        Thread.sleep(250)
        progress.update(running.id, start + 5_600_000, running.filesize, DownloadState.DOWNLOADING)
        waitForText("Tears.of.Steel")
        shot("3")

        // 4. Settings.
        compose.onAllNodesWithText(text(R.string.tab_settings)).onFirst().performClick()
        waitForText(text(R.string.set_appearance))
        shot("4")
    }
}
