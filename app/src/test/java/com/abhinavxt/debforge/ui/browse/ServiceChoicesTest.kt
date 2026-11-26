package com.abhinavxt.debforge.ui.browse

import com.abhinavxt.debforge.domain.ProviderId
import org.junit.Test
import org.junit.Assert.assertEquals

class ServiceChoicesTest {
    private val all = ProviderId.entries.take(3).map { it to it.name }

    @Test fun listsOnlySignedInInOrderAndMarksActive() {
        val ids = all.map { it.first }
        val r = serviceChoices(all, setOf(ids[2], ids[0]), ids[2])
        assertEquals(listOf(ids[0], ids[2]), r.map { it.id })
        assertEquals(listOf(false, true), r.map { it.active })
    }

    @Test fun nothingSignedInIsEmpty() = assertEquals(emptyList<ServiceChoice>(), serviceChoices(all, emptySet(), null))
}
