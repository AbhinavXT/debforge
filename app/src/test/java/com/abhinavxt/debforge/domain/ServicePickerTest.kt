package com.abhinavxt.debforge.domain

import com.abhinavxt.debforge.domain.ProviderId.ALL_DEBRID
import com.abhinavxt.debforge.domain.ProviderId.PREMIUMIZE
import com.abhinavxt.debforge.domain.ProviderId.REAL_DEBRID
import com.abhinavxt.debforge.domain.ProviderId.TORBOX
import com.abhinavxt.debforge.domain.ServicePicker.Option
import org.junit.Assert.assertEquals
import org.junit.Test

class ServicePickerTest {

    private fun opt(id: ProviderId, supports: Boolean = true, cached: Boolean = false) = Option(id, supports, cached)

    @Test fun singleServiceIsActive() =
        assertEquals(TORBOX, ServicePicker.pick(TORBOX, listOf(opt(TORBOX)), null, false))

    @Test fun activeStaysWhenNothingCachedAnywhere() =
        assertEquals(TORBOX, ServicePicker.pick(TORBOX, listOf(opt(TORBOX), opt(PREMIUMIZE)), null, false))

    @Test fun cachedElsewhereWins() =
        assertEquals(PREMIUMIZE, ServicePicker.pick(TORBOX, listOf(opt(TORBOX), opt(PREMIUMIZE, cached = true)), null, false))

    @Test fun activeCachedBeatsOtherCached() =
        assertEquals(TORBOX, ServicePicker.pick(TORBOX, listOf(opt(PREMIUMIZE, cached = true), opt(TORBOX, cached = true)), null, false))

    @Test fun userChoiceWins() =
        assertEquals(TORBOX, ServicePicker.pick(TORBOX, listOf(opt(TORBOX), opt(PREMIUMIZE, cached = true)), TORBOX, true))

    @Test fun userChoiceDroppedWhenItCantTakeTheInput() {
        // Picked Real-Debrid, then pasted a magnet (RD can't take magnets).
        val options = listOf(opt(REAL_DEBRID, supports = false), opt(ALL_DEBRID))
        assertEquals(ALL_DEBRID, ServicePicker.pick(REAL_DEBRID, options, REAL_DEBRID, true))
    }

    @Test fun magnetWhileRealDebridActive() {
        val options = listOf(opt(REAL_DEBRID, supports = false), opt(TORBOX), opt(ALL_DEBRID))
        assertEquals(TORBOX, ServicePicker.pick(REAL_DEBRID, options, null, false))
    }

    @Test fun nobodyCanTakeItFallsBackToActive() =
        assertEquals(REAL_DEBRID, ServicePicker.pick(REAL_DEBRID, listOf(opt(REAL_DEBRID, supports = false)), null, false))

    // --- direct download ---------------------------------------------------------

    private val direct = ProviderId.DIRECT

    @Test fun directLinksPreferDirect() =
        assertEquals(direct, ServicePicker.pick(TORBOX, listOf(opt(TORBOX), opt(direct)), null, false, preferred = direct))

    @Test fun directNeverChosenWithoutPreference() =
        assertEquals(TORBOX, ServicePicker.pick(TORBOX, listOf(opt(TORBOX), opt(direct)), null, false))

    @Test fun userPickBeatsDirectPreference() =
        assertEquals(TORBOX, ServicePicker.pick(TORBOX, listOf(opt(TORBOX), opt(direct)), TORBOX, true, preferred = direct))

    @Test fun directPreferenceIgnoredWhenItCantTakeInput() =
        assertEquals(TORBOX, ServicePicker.pick(TORBOX, listOf(opt(TORBOX), opt(direct, supports = false)), null, false, preferred = direct))
}
