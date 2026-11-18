package com.abhinavxt.debforge.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoredSettingsTest {
    @Test fun skipSecondsSnapToAllowedChoices() {
        listOf(5, 10, 15, 30).forEach { assertEquals(it, SettingsStore.restoredInt("audio_skip_seconds", it.toDouble())) }
        assertEquals(10, SettingsStore.restoredInt("audio_skip_seconds", 7.0))
        assertEquals(10, SettingsStore.restoredInt("audio_skip_seconds", -30.0))
        assertEquals(10, SettingsStore.restoredInt("audio_skip_seconds", 1e20))
    }

    @Test fun storageLimitSnapsToAllowedChoices() {
        STORAGE_LIMITS_GB.forEach { assertEquals(it, SettingsStore.restoredInt("storage_limit_gb", it.toDouble())) }
        assertEquals(0, SettingsStore.restoredInt("storage_limit_gb", 3.0))
        assertEquals(0, SettingsStore.restoredInt("storage_limit_gb", -5.0))
        assertEquals(0, SettingsStore.restoredInt("storage_limit_gb", Double.NaN))
    }

    @Test fun otherIntsPassThrough() {
        assertEquals(64, SettingsStore.restoredInt("speed_limit_kbps", 64.0))
    }

    @Test fun validStorageLimit() {
        assertEquals(0, validStorageLimitGb(null))
        assertEquals(20, validStorageLimitGb(20))
        assertEquals(0, validStorageLimitGb(21))
    }
}
