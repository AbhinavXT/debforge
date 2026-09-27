package com.abhinavxt.debforge.data.usage

import com.abhinavxt.debforge.domain.ProviderId
import java.time.LocalDate

data class DayUsage(val day: LocalDate, val wifi: Long, val mobile: Long) {
    val total: Long get() = wifi + mobile
}

data class UsageSummary(
    val monthWifi: Long,
    val monthMobile: Long,
    /** This month, per service, largest first. */
    val monthByProvider: List<Pair<ProviderId, Long>>,
    /** Exactly [DAYS] entries ending today, oldest first (zeros included). */
    val lastDays: List<DayUsage>
) {
    val monthTotal: Long get() = monthWifi + monthMobile

    companion object {
        const val DAYS = 30

        /** First day whose rows [summarize] needs. */
        fun fromDay(today: LocalDate): LocalDate =
            minOf(today.withDayOfMonth(1), today.minusDays((DAYS - 1).toLong()))

        fun summarize(rows: List<UsageEntity>, today: LocalDate): UsageSummary {
            val monthStart = today.withDayOfMonth(1)
            val parsed = rows.mapNotNull { r -> runCatching { LocalDate.parse(r.day) }.getOrNull()?.let { it to r } }
            val month = parsed.filter { (d, _) -> !d.isBefore(monthStart) && !d.isAfter(today) }.map { it.second }
            val byDay = parsed.groupBy({ it.first }, { it.second })
            val days = (DAYS - 1 downTo 0).map { back ->
                val d = today.minusDays(back.toLong())
                val list = byDay[d].orEmpty()
                DayUsage(d, wifi = list.filter { !it.metered }.sumOf { it.bytes }, mobile = list.filter { it.metered }.sumOf { it.bytes })
            }
            return UsageSummary(
                monthWifi = month.filter { !it.metered }.sumOf { it.bytes },
                monthMobile = month.filter { it.metered }.sumOf { it.bytes },
                monthByProvider = month.groupBy { it.provider }
                    .map { (p, list) -> p to list.sumOf { it.bytes } }
                    .filter { it.second > 0 }
                    .sortedByDescending { it.second },
                lastDays = days
            )
        }
    }
}
