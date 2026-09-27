package com.abhinavxt.debforge.download

import com.abhinavxt.debforge.data.prefs.DownloadRules
import com.abhinavxt.debforge.download.conditions.Block
import com.abhinavxt.debforge.download.conditions.DownloadConditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ScheduleWindowTest {

    private fun at(day: Int, hour: Int, minute: Int = 0, second: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.SEPTEMBER, day, hour, minute, second)
        }.timeInMillis

    private val night = DownloadRules(scheduleEnabled = true, scheduleStartHour = 23, scheduleEndHour = 7)
    private val day = DownloadRules(scheduleEnabled = true, scheduleStartHour = 9, scheduleEndHour = 17)

    @Test fun wrapsMidnight() {
        assertTrue(DownloadConditions.inWindow(night, at(27, 23, 30)))
        assertTrue(DownloadConditions.inWindow(night, at(27, 3)))
        assertFalse(DownloadConditions.inWindow(night, at(27, 7)))   // end hour is exclusive
        assertFalse(DownloadConditions.inWindow(night, at(27, 12)))
    }

    @Test fun sameDayWindow() {
        assertTrue(DownloadConditions.inWindow(day, at(27, 9)))
        assertFalse(DownloadConditions.inWindow(day, at(27, 17)))
    }

    @Test fun disabledOrEqualHoursMeansAlways() {
        assertTrue(DownloadConditions.inWindow(day.copy(scheduleEnabled = false), at(27, 3)))
        assertTrue(DownloadConditions.inWindow(day.copy(scheduleEndHour = 9), at(27, 3)))
    }

    @Test fun nextStartLaterToday() =
        assertEquals(at(27, 23, 0, 5), DownloadConditions.nextWindowStart(23, at(27, 12)))

    @Test fun nextStartTomorrowOncePassed() =
        assertEquals(at(28, 9, 0, 5), DownloadConditions.nextWindowStart(9, at(27, 9, 0, 10)))

    @Test fun scheduleOutranksOtherBlocks() {
        val r = night.copy(wifiOnly = true, chargingOnly = true)
        assertEquals(Block.OUTSIDE_SCHEDULE, DownloadConditions.evaluate(r, false, false, false, at(27, 12)))
        assertEquals(Block.NEEDS_WIFI, DownloadConditions.evaluate(r, true, false, false, at(27, 1)))
        assertEquals(Block.NEEDS_CHARGER, DownloadConditions.evaluate(r, true, true, false, at(27, 1)))
        assertNull(DownloadConditions.evaluate(r, true, true, true, at(27, 1)))
    }
}
