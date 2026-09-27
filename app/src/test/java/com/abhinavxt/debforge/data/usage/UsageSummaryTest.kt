package com.abhinavxt.debforge.data.usage

import com.abhinavxt.debforge.domain.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class UsageSummaryTest {

    private val today = LocalDate.of(2026, 9, 27)

    private fun row(day: String, p: ProviderId, metered: Boolean, bytes: Long) = UsageEntity(day, p, metered, bytes)

    @Test fun monthSplitAndServices() {
        val s = UsageSummary.summarize(
            listOf(
                row("2026-09-01", ProviderId.TORBOX, false, 100),
                row("2026-09-27", ProviderId.TORBOX, true, 30),
                row("2026-09-10", ProviderId.REAL_DEBRID, false, 500),
                row("2026-08-31", ProviderId.TORBOX, false, 9_999) // last month: chart only
            ),
            today
        )
        assertEquals(600, s.monthWifi)
        assertEquals(30, s.monthMobile)
        assertEquals(listOf(ProviderId.REAL_DEBRID to 500L, ProviderId.TORBOX to 130L), s.monthByProvider)
    }

    @Test fun thirtyDaysEndingTodayWithZeros() {
        val s = UsageSummary.summarize(listOf(row("2026-08-29", ProviderId.TORBOX, false, 7)), today)
        assertEquals(30, s.lastDays.size)
        assertEquals(LocalDate.of(2026, 8, 29), s.lastDays.first().day)
        assertEquals(today, s.lastDays.last().day)
        assertEquals(7, s.lastDays.first().total)
        assertEquals(0, s.lastDays.last().total)
    }

    @Test fun fromDayCoversMonthAndChart() {
        assertEquals(LocalDate.of(2026, 8, 29), UsageSummary.fromDay(today))           // chart reaches further
        assertEquals(LocalDate.of(2026, 1, 1), UsageSummary.fromDay(LocalDate.of(2026, 1, 31))) // month reaches further
    }
}
