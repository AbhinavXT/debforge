package com.abhinavxt.debforge.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Library's grid: titles rearrange (something was added, sort changed) while a finger scrolls up. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class SafeGridScrollTest {
    @get:Rule val rule = createComposeRule()

    private fun scrollUpWhileRearranging(state: @Composable () -> LazyGridState, change: (List<Int>) -> List<Int>) {
        var ids by mutableStateOf((1..60).toList())
        rule.setContent {
            val grid = state()
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.size(300.dp, 400.dp).testTag("grid"), state = grid) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") { Box(Modifier.fillMaxWidth().height(40.dp)) }
                items(ids, key = { it }) { Box(Modifier.height(100.dp).animateItem(placementSpec = tween(1000))) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("grid").performTouchInput { down(center); repeat(6) { moveBy(androidx.compose.ui.geometry.Offset(0f, -60f)) }; up() }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("grid").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 30f)) }
        rule.mainClock.advanceTimeByFrame()
        ids = change(ids)
        repeat(8) {
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("grid").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, 8f)) }
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
    }

    /** Adding something empties the list, then brings it back with the new title and the processing block on top. */
    private fun addWhileTouching(state: @Composable () -> LazyGridState, settleFrames: Int) {
        var ids by mutableStateOf((1..60).toList())
        var leading by mutableStateOf(false)
        rule.setContent {
            val grid = state()
            Box(Modifier.size(300.dp, 400.dp).testTag("grid")) {
                if (ids.isNotEmpty()) LazyVerticalGrid(GridCells.Fixed(3), Modifier.size(300.dp, 400.dp), state = grid) {
                    if (leading) item(span = { GridItemSpan(maxLineSpan) }, key = "leading") {
                        Box(Modifier.fillMaxWidth().height(150.dp).animateItem())
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header") { Box(Modifier.fillMaxWidth().height(40.dp)) }
                    items(ids, key = { it }) { Box(Modifier.height(100.dp).animateItem()) }
                }
            }
        }
        fun move(y: Float) = rule.onNodeWithTag("grid").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, y)) }
        rule.waitForIdle()
        rule.onNodeWithTag("grid").performTouchInput { down(center); repeat(6) { moveBy(androidx.compose.ui.geometry.Offset(0f, -60f)) }; up() }
        rule.waitForIdle()
        val before = ids
        ids = emptyList()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        ids = listOf(100) + before
        repeat(settleFrames) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag("grid").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 30f)) }
        leading = true
        repeat(4) { rule.mainClock.advanceTimeByFrame(); move(8f) }
        ids = listOf(101) + before.sortedBy { (it * 7) % 61 } // artwork and names arrive: titles regroup
        repeat(8) { rule.mainClock.advanceTimeByFrame(); move(8f) }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
    }

    private val shuffle = { l: List<Int> -> l.sortedBy { (it * 37) % 61 } }

    /** When this stops failing, Compose has fixed it and SafeGridPrefetch can go. */
    @Test fun plainGridCrashesWhenTitlesRearrange() {
        val e = runCatching { scrollUpWhileRearranging({ rememberLazyGridState() }, shuffle) }.exceptionOrNull()
        assertTrue("$e", generateSequence(e) { it.cause }.any { it.message == "currentLine > lineIndex" })
    }

    @Test fun safeGridSurvivesRearranging() = scrollUpWhileRearranging({ rememberSafeGridState() }, shuffle)
    @Test fun safeGridSurvivesReversing() = scrollUpWhileRearranging({ rememberSafeGridState() }) { it.reversed() }
    @Test fun safeGridSurvivesAnAdd() = addWhileTouching({ rememberSafeGridState() }, settleFrames = 2)
}
