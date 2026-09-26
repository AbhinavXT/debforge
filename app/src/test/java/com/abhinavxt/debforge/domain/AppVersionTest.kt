package com.abhinavxt.debforge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test fun numericNotLexical() = assertTrue(AppVersion.isNewer("1.10.0", "1.9.3"))
    @Test fun leadingV() = assertTrue(AppVersion.isNewer("v1.2.1", "1.2"))
    @Test fun missingPartsAreZero() = assertEquals(0, AppVersion.compare("1.4", "1.4.0"))
    @Test fun sameIsNotNewer() = assertFalse(AppVersion.isNewer("v2.0.0", "2.0.0"))
    @Test fun olderIsNotNewer() = assertFalse(AppVersion.isNewer("1.3.9", "1.4"))
    @Test fun preReleaseBeforeRelease() {
        assertTrue(AppVersion.isNewer("2.0.0", "2.0.0-beta.3"))
        assertFalse(AppVersion.isNewer("2.0.0-rc.1", "2.0.0"))
    }
    @Test fun preReleaseOrdering() {
        assertTrue(AppVersion.isNewer("2.0.0-beta.10", "2.0.0-beta.2"))
        assertTrue(AppVersion.isNewer("2.0.0-rc.1", "2.0.0-beta.9"))
    }
    @Test fun buildMetadataIgnored() = assertEquals(0, AppVersion.compare("1.2.0+45", "1.2.0"))
    @Test fun garbageDoesNotThrow() = assertTrue(AppVersion.isNewer("1.1", "dev"))
}
