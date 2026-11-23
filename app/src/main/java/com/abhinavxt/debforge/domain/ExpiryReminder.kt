package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.data.provider.AccountInfo

object ExpiryReminder {
    /** Choices offered in Settings; 0 = off. */
    val CHOICES = listOf(0, 1, 3, 7)
    const val DEFAULT_DAYS = 3

    /**
     * True when premium ends within [remindDays] days and this exact expiry
     * hasn't been announced yet. A renewed account has a new date, so it
     * re-arms. Already expired (or no usable date) stays quiet: nothing left to warn about.
     */
    fun shouldNotify(premiumUntil: String?, remindDays: Int, lastNotified: String?, nowMs: Long): Boolean {
        if (remindDays <= 0 || premiumUntil == null || premiumUntil == lastNotified) return false
        val days = AccountInfo.daysUntil(premiumUntil, nowMs) ?: return false
        return days in 0..remindDays
    }
}
