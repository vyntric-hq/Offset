package com.vyntric.offset.ui.channel

import com.vyntric.offset.protocol.NicFw950Profile
import com.vyntric.offset.protocol.RadioModel
import com.vyntric.offset.protocol.Rt950ProProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelDraftTest {
    private val draft = ChannelDraft(name = "Rpt 70cm ", freq = "442.100")

    @Test fun `names keep their case on radios that show lowercase`() {
        assertEquals("Rpt 70cm", draft.build(NicFw950Profile).channel!!.name)
        assertEquals("Rpt 70cm", draft.build(Rt950ProProfile).channel!!.name)
    }

    @Test fun `names are uppercased on radios that show capitals only`() {
        assertEquals("RPT 70", draft.copy(name = "Rpt 70", freq = "446.000").build(RadioModel.UV5R).channel!!.name)
    }

    @Test fun `only radios that store a scan flag offer one`() {
        assertFalse(NicFw950Profile.storesScanAdd)
        assertTrue(Rt950ProProfile.storesScanAdd)
        assertTrue(RadioModel.UV5R.storesScanAdd)
    }
}
