package com.abhinavxt.debforge.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class ListMergeTest {

    private fun m(cur: List<String>, n: Int, fresh: List<String>) = mergeFirstPage(cur, n, fresh) { it }

    private val loaded = listOf("a1", "a2", "a3", "b1", "b2", "b3", "c1")

    @Test fun noChange() = assertEquals(loaded, m(loaded, 3, listOf("a1", "a2", "a3")))

    @Test fun newItemPushesLastOneDown() =
        assertEquals(listOf("n1", "a1", "a2", "a3", "b1", "b2", "b3", "c1"), m(loaded, 3, listOf("n1", "a1", "a2")))

    @Test fun deletedItemIsDropped() =
        assertEquals(listOf("a1", "a3", "b1", "b2", "b3", "c1"), m(loaded, 3, listOf("a1", "a3", "b1")))

    @Test fun newAndDeletedTogether() =
        // n1 added, a1 deleted: a3 was not pushed off (still fresh), a1 gone.
        assertEquals(listOf("n1", "a2", "a3", "b1", "b2", "b3", "c1"), m(loaded, 3, listOf("n1", "a2", "a3")))

    // a3 deleted, b1 moved up from page 2: a3 dropped, b1 appears once.
    @Test fun itemMovingUpIsNotDuplicated() =
        assertEquals(listOf("a1", "a2", "b1", "b2", "b3", "c1"), m(loaded, 3, listOf("a1", "a2", "b1")))

    @Test fun onlyFirstPageLoaded() =
        assertEquals(listOf("n1", "n2", "a1", "a2", "a3"), m(listOf("a1", "a2", "a3"), 3, listOf("n1", "n2", "a1")))
}
