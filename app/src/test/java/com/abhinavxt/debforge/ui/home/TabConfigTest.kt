package com.abhinavxt.debforge.ui.home

import com.abhinavxt.debforge.ui.home.TabConfig.BROWSER
import com.abhinavxt.debforge.ui.home.TabConfig.DISCOVER
import com.abhinavxt.debforge.ui.home.TabConfig.HOME
import com.abhinavxt.debforge.ui.home.TabConfig.LIBRARY
import com.abhinavxt.debforge.ui.home.TabConfig.MEDIA
import com.abhinavxt.debforge.ui.home.TabConfig.SEARCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabConfigTest {
    @Test fun defaultBarDependsOnSearchSources() {
        assertEquals(listOf(HOME, LIBRARY, SEARCH, DISCOVER, MEDIA), TabConfig.defaultBar(true))
        assertEquals(listOf(HOME, LIBRARY, DISCOVER, BROWSER, MEDIA), TabConfig.defaultBar(false))
    }

    @Test fun nothingStoredUsesTheDefault() {
        assertEquals(TabConfig.defaultBar(true), TabConfig.bar(null, true))
        assertEquals(TabConfig.defaultBar(false), TabConfig.bar("", false))
    }

    @Test fun storedBarKeepsItsOrderAndIgnoresUnknownAndDuplicateKeys() {
        assertEquals(listOf(MEDIA, HOME, LIBRARY), TabConfig.parse("media, home,nope,media,browse,"))
    }

    @Test fun tooFewKnownTabsFallBackToTheDefault() {
        assertNull(TabConfig.parse("home"))
        assertNull(TabConfig.parse("home,home,junk"))
        assertEquals(TabConfig.defaultBar(true), TabConfig.bar("home,junk", true))
    }

    @Test fun moreThanTheMaximumKeepsTheFirstFive() {
        assertEquals(TabConfig.ALL.take(TabConfig.MAX), TabConfig.parse(TabConfig.ALL.joinToString(",")))
    }

    @Test fun encodeRoundTrips() {
        val bar = listOf(BROWSER, MEDIA, HOME)
        assertEquals(bar, TabConfig.parse(TabConfig.encode(bar)))
    }

    @Test fun toggleRespectsMinAndMax() {
        val two = listOf(HOME, LIBRARY)
        assertNull(TabConfig.toggled(two, HOME))
        assertEquals(listOf(HOME, LIBRARY, MEDIA), TabConfig.toggled(two, MEDIA))
        val five = TabConfig.ALL.take(5)
        assertNull(TabConfig.toggled(five, MEDIA))
        assertEquals(TabConfig.ALL.take(4), TabConfig.toggled(five, TabConfig.ALL[4]))
    }

    @Test fun moveShiftsOneStepAndStopsAtTheEnds() {
        val bar = listOf(HOME, LIBRARY, MEDIA)
        assertEquals(listOf(LIBRARY, HOME, MEDIA), TabConfig.moved(bar, LIBRARY, -1))
        assertEquals(listOf(HOME, MEDIA, LIBRARY), TabConfig.moved(bar, LIBRARY, 1))
        assertEquals(bar, TabConfig.moved(bar, HOME, -1))
        assertEquals(bar, TabConfig.moved(bar, MEDIA, 1))
        assertEquals(bar, TabConfig.moved(bar, SEARCH, 1))
    }

    @Test fun railListsEveryTabWithHiddenOnesAfterTheBar() {
        val bar = listOf(MEDIA, HOME)
        val rail = TabConfig.rail(bar)
        assertEquals(bar, rail.take(2))
        assertEquals(TabConfig.ALL.toSet(), rail.toSet())
        assertEquals(TabConfig.ALL.size, rail.size)
        assertEquals(TabConfig.hidden(bar), rail.drop(2))
    }

    @Test fun startTabIsAutomaticUntilChosen() {
        assertEquals(LIBRARY, TabConfig.startTab(null, true))
        assertEquals(BROWSER, TabConfig.startTab(null, false))
        assertEquals(LIBRARY, TabConfig.startTab(TabConfig.AUTO, true))
        assertEquals(BROWSER, TabConfig.startTab("gone", false))
    }

    @Test fun aChosenStartTabWinsEvenOutsideTheBar() {
        assertEquals(HOME, TabConfig.startTab(HOME, false))
        assertEquals(MEDIA, TabConfig.startTab(MEDIA, true))
        assertTrue(TabConfig.ALL.all { TabConfig.startTab(it, true) == it })
    }

    @Test fun everyBarKeyIsAScreen() {
        TabConfig.ALL.forEach { assertEquals(it, HomeTab.of(it)?.route) }
        assertNull(HomeTab.of("auto"))
    }

    @Test fun libraryIsNamedForTheSignedInService() {
        assertEquals("TorBox", TabConfig.libraryLabel("TorBox", "Library"))
        assertEquals("Library", TabConfig.libraryLabel("", "Library"))
        assertEquals("Library", TabConfig.libraryLabel(null, "Library"))
    }

    @Test fun browserRequestsGoToTheTabOrToDiscoverWeb() {
        val withBrowser = listOf(HOME, BROWSER, MEDIA)
        val without = listOf(HOME, LIBRARY, DISCOVER)
        assertEquals(BROWSER, TabConfig.browserTab(withBrowser))
        assertEquals(DISCOVER, TabConfig.browserTab(without))
        assertTrue(!TabConfig.opensWeb(BROWSER, withBrowser))
        assertTrue(TabConfig.opensWeb(BROWSER, without))
        assertTrue(!TabConfig.opensWeb(LIBRARY, without))
    }
}
