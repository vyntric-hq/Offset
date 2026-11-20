package com.vyntric.offset.ui.repeaters

import com.vyntric.offset.data.Units
import com.vyntric.offset.domain.RepeaterSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The Search button's outcome is only shown for the inputs that produced it. */
class RepeatersUiStateTest {
    private val searched = RepeatersUiState(text = "W4CN", radius = 25)

    @Test fun `the same inputs keep the outcome`() {
        assertEquals(searched.searchInputs, searched.copy(results = emptyList(), loading = true).searchInputs)
        assertEquals("surrounding spaces do not count", searched.searchInputs, searched.copy(text = " W4CN ").searchInputs)
    }

    @Test fun `anything a search depends on makes the outcome out of date`() {
        listOf(
            searched.copy(text = "K4"),
            searched.copy(radius = 50),
            searched.copy(units = Units.KILOMETERS),
            searched.copy(mode = SearchMode.STATE),
            searched.copy(service = ServiceFilter.GMRS),
            searched.copy(providers = setOf(RepeaterSource.REPEATERBOOK)),
            searched.copy(locationMode = LocationMode.GRID),
        ).forEach { assertNotEquals(it.toString(), searched.searchInputs, it.searchInputs) }
    }
}
