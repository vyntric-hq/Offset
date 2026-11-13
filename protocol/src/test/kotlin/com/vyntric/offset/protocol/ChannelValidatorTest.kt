package com.vyntric.offset.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelValidatorTest {
    private fun fields(ch: Channel, model: RadioModel = RadioModel.UV5R) =
        ChannelValidator.validate(ch, model).map { it.field }.toSet()

    @Test fun `a normal repeater channel is valid`() {
        assertTrue(fields(Channel(146_940_000, Duplex.MINUS, 600_000, Squelch.Ctcss(1000), name = "W1AW")).isEmpty())
    }

    @Test fun `frequency must be inside the model's bands`() {
        assertEquals(setOf(ChannelValidator.Field.FREQUENCY), fields(Channel(220_000_000)))
        assertTrue(fields(Channel(178_000_000), RadioModel.BF_F8HP).isEmpty())
        assertEquals(setOf(ChannelValidator.Field.FREQUENCY), fields(Channel(178_000_000), RadioModel.UV5R))
    }

    @Test fun `offset is limited to 70 MHz and the transmit frequency must be in band`() {
        assertTrue(ChannelValidator.Field.OFFSET in fields(Channel(146_000_000, Duplex.PLUS, 80_000_000)))
        assertTrue(ChannelValidator.Field.TX_FREQUENCY in fields(Channel(174_000_000, Duplex.PLUS, 5_000_000)))
    }

    @Test fun `name length and charset are enforced`() {
        assertEquals(setOf(ChannelValidator.Field.NAME), fields(Channel(146_520_000, name = "TOOLONGNAME")))
        assertEquals(setOf(ChannelValidator.Field.NAME), fields(Channel(146_520_000, name = "A_B")))
    }

    @Test fun `tones and power must exist on the radio`() {
        assertTrue(ChannelValidator.Field.TX_TONE in fields(Channel(146_520_000, txSquelch = Squelch.Dcs(999))))
        assertTrue(ChannelValidator.Field.POWER in fields(Channel(146_520_000, power = Power.MID)))
        assertTrue(fields(Channel(146_520_000, power = Power.MID), RadioModel.BF_F8HP).isEmpty())
    }
}
