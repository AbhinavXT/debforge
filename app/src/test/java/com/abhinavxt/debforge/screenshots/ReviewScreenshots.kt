package com.abhinavxt.debforge.screenshots

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.abhinavxt.debforge.HiltTestActivity
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.prefs.TokenCipher
import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.domain.ProviderId
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
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/**
 * Extra screens and variants for UI review (not store assets). Writes to
 * app/build/screenshots/review/. Run with:
 *
 *     ./gradlew recordRoborazziDebug -Pscreenshots
 *
 * Excluded from normal test runs together with the rest of this package.
 * Skipped: search_results (needs network sources), setup (the signed-out
 * state needs a different token seed and a Library "connect" tap that the
 * demo setup does not provide).
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class ReviewScreenshots {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject lateinit var tokens: TokenStore
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var downloads: DownloadDao

    private val outDir = File(System.getProperty("user.dir"), "build/screenshots/review")

    private fun seed(withDownloads: Boolean = true) {
        TokenCipher.testKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        hilt.inject()
        runBlocking {
            tokens.setToken(ProviderId.TORBOX, "demo-token")
            settings.setAutoUpdateCheck(false)
            settings.setPosterView(true) // DataStore outlives a test: undo the list-view toggle
            if (withDownloads) DemoData.downloads.forEach { downloads.upsert(it) }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) {
        compose.waitForIdle()
        outDir.mkdirs()
        captureScreenRoboImage(File(outDir, "$name.png").path)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureNow(name: String) {
        outDir.mkdirs()
        captureScreenRoboImage(File(outDir, "$name.png").path)
    }

    private fun text(res: Int) = compose.activity.getString(res)

    private fun waitForText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun start(dark: Boolean = true) {
        compose.setContent { DebforgeTheme(darkTheme = dark, dynamicColor = false) { DebForgeRoot() } }
        waitForText(DemoData.SHOW_TITLE)
        waitForText("Charge")
    }

    private fun tab(res: Int) {
        compose.onAllNodesWithText(text(res)).onFirst().performClick()
    }

    private fun scrollScreen() {
        compose.onRoot().performTouchInput {
            swipeUp(startY = height * 0.85f, endY = height * 0.15f)
        }
        compose.waitForIdle()
    }

    private fun openSheet() {
        compose.onAllNodesWithText(DemoData.SHOW_TITLE).onFirst().performClick()
        waitForText("E03")
    }

    private fun showDownloads() {
        tab(R.string.tab_downloads)
        waitForText("Tears of Steel")
    }

    private fun showSettings() {
        tab(R.string.tab_settings)
        waitForText(text(R.string.set_appearance))
    }

    // --- Dark, phone ---

    @Test fun search() {
        seed(); start()
        tab(R.string.tab_search)
        shot("search")
    }

    @Test fun browser() {
        seed(); start()
        tab(R.string.tab_browser)
        shot("browser")
    }

    @Test fun add() {
        seed(); start()
        compose.onAllNodesWithContentDescription(text(R.string.browse_add_cd)).onFirst().performClick()
        // The dialog never goes idle (an endless animation), so freeze the clock instead.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        captureNow("add")
    }

    @Test fun libraryList() {
        seed(); start()
        compose.onAllNodesWithContentDescription(text(R.string.library_view_as_list)).onFirst().performClick()
        shot("library_list")
    }

    @Test fun libraryScrolled() {
        seed(); start()
        scrollScreen()
        shot("library_scrolled")
    }

    @Test fun settingsScrolled() {
        seed(); start()
        showSettings()
        scrollScreen()
        shot("settings_2")
        scrollScreen()
        shot("settings_3")
    }

    @Test fun downloadsEmpty() {
        seed(withDownloads = false)
        compose.setContent { DebforgeTheme(darkTheme = true, dynamicColor = false) { DebForgeRoot() } }
        compose.waitForIdle()
        tab(R.string.tab_downloads)
        shot("downloads_empty")
    }

    // --- Light, phone ---

    @Test fun lightLibrary() { seed(); start(dark = false); shot("light_library") }

    @Test fun lightSheet() { seed(); start(dark = false); openSheet(); shot("light_sheet") }

    @Test fun lightDownloads() { seed(); start(dark = false); showDownloads(); shot("light_downloads") }

    @Test fun lightSettings() { seed(); start(dark = false); showSettings(); shot("light_settings") }

    // --- Dark, tablet (navigation rail) ---

    @Config(application = HiltTestApplication::class, sdk = [35], qualifiers = "w800dp-h1280dp-xhdpi")
    @Test fun tabletLibrary() { seed(); start(); shot("tablet_library") }

    @Config(application = HiltTestApplication::class, sdk = [35], qualifiers = "w800dp-h1280dp-xhdpi")
    @Test fun tabletDownloads() { seed(); start(); showDownloads(); shot("tablet_downloads") }
}
