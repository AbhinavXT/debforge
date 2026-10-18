package com.abhinavxt.debforge.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdHostsTest {

    @Test fun domainAndSubdomains() {
        assertTrue(AdHosts.blocks("doubleclick.net"))
        assertTrue(AdHosts.blocks("securepubads.g.DoubleClick.net."))
        assertTrue(AdHosts.blocks("syndication.exosrv.com"))
    }

    @Test fun lookalikesAndEmpty() {
        assertFalse(AdHosts.blocks("notdoubleclick.net"))
        assertFalse(AdHosts.blocks("doubleclick.net.example.com"))
        assertFalse(AdHosts.blocks("example.com"))
        assertFalse(AdHosts.blocks(""))
        assertFalse(AdHosts.blocks(null))
    }
}
