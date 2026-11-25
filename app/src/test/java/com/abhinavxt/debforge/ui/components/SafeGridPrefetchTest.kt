package com.abhinavxt.debforge.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridPrefetchScope
import androidx.compose.foundation.lazy.grid.LazyGridPrefetchStrategy
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalFoundationApi::class)
class SafeGridPrefetchTest {
    // Sealed interfaces: stood in for by proxies.
    @Suppress("UNCHECKED_CAST")
    private fun <T> fake(type: Class<T>, vararg values: Pair<String, Any>): T {
        val map = values.toMap()
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, _ ->
            map[m.name.substringBefore('-')] ?: error(m.name)
        } as T
    }

    private fun item(index: Int, row: Int) = fake(
        LazyGridItemInfo::class.java, "getIndex" to index, "getRow" to row,
        "getOffset" to 0L, "getSize" to 0L
    )

    private fun info(vararg items: LazyGridItemInfo) = fake(
        LazyGridLayoutInfo::class.java, "getVisibleItemsInfo" to items.toList(), "getTotalItemsCount" to 50,
        "getOrientation" to Orientation.Vertical, "getViewportStartOffset" to 0, "getViewportEndOffset" to 100,
        "getMainAxisItemSpacing" to 0
    )

    private fun linesPrefetched(strategy: LazyGridPrefetchStrategy, info: LazyGridLayoutInfo): List<Int> {
        val lines = mutableListOf<Int>()
        val scope = object : LazyGridPrefetchScope {
            override fun scheduleLinePrefetch(lineIndex: Int): List<LazyLayoutPrefetchState.PrefetchHandle> {
                lines += lineIndex
                return emptyList()
            }
        }
        with(strategy) { scope.onScroll(5f, info) } // scrolling up
        return lines
    }

    private val animatingOut = info(item(9, LazyGridItemInfo.UnknownRow), item(12, 4))

    @Test fun composeAsksForNegativeRow() =
        assertEquals(listOf(-2), linesPrefetched(LazyGridPrefetchStrategy(), animatingOut))

    @Test fun skippedWhileAnItemAnimatesOut() =
        assertEquals(emptyList<Int>(), linesPrefetched(SafeGridPrefetch(), animatingOut))

    @Test fun prefetchesNormallyOtherwise() =
        assertEquals(listOf(3), linesPrefetched(SafeGridPrefetch(), info(item(12, 4))))
}
