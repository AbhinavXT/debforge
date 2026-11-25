package com.abhinavxt.debforge.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridPrefetchScope
import androidx.compose.foundation.lazy.grid.LazyGridPrefetchStrategy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Use for every grid with animateItem(). Compose 1.7 counts items animating out of view as visible, with no row:
 * scrolling up right then prefetches row -2 and crashes ("currentLine > lineIndex"). This skips prefetching until
 * they are gone. Can go once Compose foundation is 1.8 or newer.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class SafeGridPrefetch(
    private val default: LazyGridPrefetchStrategy = LazyGridPrefetchStrategy()
) : LazyGridPrefetchStrategy by default {
    override fun LazyGridPrefetchScope.onScroll(delta: Float, layoutInfo: LazyGridLayoutInfo) {
        if (layoutInfo.visibleItemsInfo.any { it.row == LazyGridItemInfo.UnknownRow }) return
        with(default) { onScroll(delta, layoutInfo) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberSafeGridState(): LazyGridState =
    rememberLazyGridState(prefetchStrategy = remember { SafeGridPrefetch() })
