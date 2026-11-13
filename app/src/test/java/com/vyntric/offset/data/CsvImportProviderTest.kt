package com.vyntric.offset.data

import com.vyntric.offset.data.repeaters.CsvImportProvider
import com.vyntric.offset.domain.RepeaterException
import com.vyntric.offset.domain.RepeaterFilters
import com.vyntric.offset.domain.RepeaterQuery
import com.vyntric.offset.domain.UsState
import com.vyntric.offset.fixedClock
import com.vyntric.offset.protocol.Band
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CsvImportProviderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val both = RepeaterFilters()

    private val withCoords = """
        Location,Name,Frequency,Duplex,Offset,Tone,rToneFreq,cToneFreq,DtcsCode,DtcsPolarity,Mode,TStep,Skip,Comment,Callsign,Latitude,Longitude,State,County
        0,K6ABC,145.230000,-,0.600000,Tone,100.0,88.5,023,NN,FM,5.00,,San Jose,K6ABC,37.33,-121.89,CA,Santa Clara
        1,W6FAR,146.850000,-,0.600000,Tone,123.0,88.5,023,NN,FM,5.00,,Fresno,W6FAR,36.74,-119.78,CA,Fresno
        2,N6UHF,442.100000,+,5.000000,TSQL,88.5,88.5,023,NN,FM,5.00,,Reno,N6UHF,39.53,-119.81,NV,Washoe
    """.trimIndent()

    @Test fun `near me uses coordinates when the file has them`() = runTest {
        val p = CsvImportProvider(tmp.root.resolve("csv.json"), fixedClock)
        assertEquals(3, p.import(withCoords, "list.csv").entries)
        val near = p.search(RepeaterQuery.Near(37.33, -121.89, 50.0, both))
        assertEquals(listOf("K6ABC"), near.repeaters.map { it.callsign })
        val nv = p.search(RepeaterQuery.ByState(UsState.NV, null, both, null))
        assertEquals(listOf("N6UHF"), nv.repeaters.map { it.callsign })
        val county = p.search(RepeaterQuery.ByState(UsState.CA, "Fresno", both, null))
        assertEquals(listOf("W6FAR"), county.repeaters.map { it.callsign })
    }

    @Test fun `a plain CHIRP CSV without coordinates lists every in band entry`() = runTest {
        val p = CsvImportProvider(tmp.root.resolve("csv.json"), fixedClock)
        p.import("Location,Name,Frequency,Duplex,Offset\n0,ONE,146.520000,,0\n1,TWO,446.000000,,0\n2,AIR,121.500000,,0\n", "plain.csv")
        val r = p.search(RepeaterQuery.Near(40.0, -100.0, 10.0, RepeaterFilters(bands = setOf(Band.TWO_METER))))
        assertEquals(listOf("ONE"), r.repeaters.map { it.callsign })
    }

    @Test fun `import persists across instances and clear removes it`() = runTest {
        val file = tmp.root.resolve("csv.json")
        CsvImportProvider(file, fixedClock).import(withCoords, "list.csv")
        val p = CsvImportProvider(file, fixedClock)
        assertEquals(3, p.search(RepeaterQuery.ByState(UsState.CA, null, both, null)).repeaters.size + 1)
        p.clearCache()
        try {
            p.search(RepeaterQuery.ByState(UsState.CA, null, both, null))
            fail("expected NotConfigured")
        } catch (e: RepeaterException.NotConfigured) {
            // expected
        }
    }
}
