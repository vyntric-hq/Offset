package com.vyntric.offset.ui.channel

import com.vyntric.offset.protocol.Duplex
import com.vyntric.offset.protocol.Modulation
import com.vyntric.offset.protocol.NicFw950Codec
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

    @Test fun `a receive only mode turns transmit off, and leaving it turns it back on`() {
        val usb = draft.copy(freq = "14.074").withMode(Modulation.USB)
        assertEquals(Duplex.OFF, usb.duplex)
        assertEquals(Duplex.OFF, usb.build(NicFw950Profile).channel!!.duplex)
        assertEquals(Duplex.SIMPLEX, usb.withMode(Modulation.FM).duplex)
    }

    @Test fun `a new channel carries only the options the user set`() {
        assertEquals(emptyMap<String, String>(), draft.build(NicFw950Profile).channel!!.options)
        val grouped = draft.with(NicFw950Codec.GROUPS_OPTION, "BD")
        assertEquals(mapOf("nicfw.groups" to "BD"), grouped.build(NicFw950Profile).channel!!.options)
    }

    @Test fun `the clarifier is stored in canonical form`() {
        val ch = draft.with(NicFw950Codec.CLARIFIER_OPTION, "-1,2").build(NicFw950Profile).channel!!
        assertEquals("-1.20", ch.options["nicfw.clarifier"])
        val bad = draft.with(NicFw950Codec.CLARIFIER_OPTION, "-").build(NicFw950Profile)
        assertEquals(null, bad.channel)
        assertTrue(bad.optionProblems.containsKey("nicfw.clarifier"))
    }

    @Test fun `narrow through nicFW's bandwidth option still reads as narrow`() {
        val ch = draft.with(NicFw950Codec.BANDWIDTH, "1").build(NicFw950Profile).channel!!
        assertFalse(ch.wide)
    }

    @Test fun `too many groups are reported against the groups option`() {
        val r = draft.with(NicFw950Codec.GROUPS_OPTION, "ABCDE").build(NicFw950Profile)
        assertEquals(null, r.channel)
        assertTrue(r.optionProblems.containsKey("nicfw.groups"))
    }

    @Test fun `stock RT-950 offers FM and AM, and no PTT-ID code`() {
        assertEquals(listOf(Modulation.FM, Modulation.AM), Rt950ProProfile.modes)
        assertEquals(0, Rt950ProProfile.pttIdCodes)
        val am = draft.copy(freq = "127.550").withMode(Modulation.AM).build(Rt950ProProfile).channel!!
        assertEquals(Modulation.AM, am.mode)
    }

    @Test fun `switching to an HF mode picks an HF filter width, and back picks wide`() {
        val lsb = draft.copy(freq = "7.200").withMode(Modulation.LSB, NicFw950Profile)
        assertEquals("3", lsb.options["nicfw.bandwidth"])
        assertEquals("0", lsb.withMode(Modulation.FM, NicFw950Profile).options["nicfw.bandwidth"])
        assertEquals(listOf("Wide", "Narrow", "Auto"), NicFw950Codec.BANDWIDTH.choicesFor(Modulation.FM).map { it.second })
    }

    @Test fun `a detour through a receive only mode keeps the repeater offset`() {
        val rpt = draft.copy(duplex = Duplex.PLUS, offset = "5")
        val back = rpt.withMode(Modulation.USB, NicFw950Profile).withMode(Modulation.CW, NicFw950Profile).withMode(Modulation.FM, NicFw950Profile)
        assertEquals(Duplex.PLUS, back.duplex)
        assertEquals("5", back.offset)
    }
}
