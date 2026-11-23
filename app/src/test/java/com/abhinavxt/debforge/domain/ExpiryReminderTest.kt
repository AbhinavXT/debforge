package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.data.provider.AccountInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ExpiryReminderTest {
    private val now = Instant.parse("2026-01-10T12:00:00Z").toEpochMilli()
    private fun inDays(d: Long) = Instant.ofEpochMilli(now).plusSeconds(d * 86400 + 60).toString()

    @Test fun withinThreshold() = assertTrue(ExpiryReminder.shouldNotify(inDays(2), 3, null, now))
    @Test fun onTheThresholdDay() = assertTrue(ExpiryReminder.shouldNotify(inDays(3), 3, null, now))
    @Test fun beyondThreshold() = assertFalse(ExpiryReminder.shouldNotify(inDays(4), 3, null, now))
    @Test fun expiresToday() = assertTrue(ExpiryReminder.shouldNotify(inDays(0), 1, null, now))
    @Test fun alreadyNotified() = assertFalse(ExpiryReminder.shouldNotify(inDays(2), 3, inDays(2), now))
    @Test fun renewedDateRearms() = assertTrue(ExpiryReminder.shouldNotify(inDays(2), 3, inDays(-5), now))
    @Test fun off() = assertFalse(ExpiryReminder.shouldNotify(inDays(1), 0, null, now))
    @Test fun alreadyExpired() = assertFalse(ExpiryReminder.shouldNotify(inDays(-2), 3, null, now))
    @Test fun unparseable() = assertFalse(ExpiryReminder.shouldNotify("soon", 3, null, now))
    @Test fun noDate() = assertFalse(ExpiryReminder.shouldNotify(null, 3, null, now))
    @Test fun offsetDateParses() = assertEquals(2L, AccountInfo.daysUntil("2026-01-12T13:00:00+01:00", now))
}
