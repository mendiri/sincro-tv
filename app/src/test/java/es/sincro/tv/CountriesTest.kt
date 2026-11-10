package es.sincro.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountriesTest {
    @Test
    fun paisDeTusCanales() {
        assertEquals("16", Countries.ofChannel("ES| DAZN 1 FHD", ""))
        assertEquals("16", Countries.ofChannel("|ES| DAZN 1", ""))
        assertEquals("16", Countries.ofChannel("[ES] DAZN 1", ""))
        assertEquals("17", Countries.ofChannel("IT: DAZN 1", ""))
        assertEquals("2", Countries.ofChannel("UK - Sky Sports Main Event", ""))
        assertEquals("19", Countries.ofChannel("Sport TV 1", "PORTUGAL | DESPORTO"))
        assertEquals("16", Countries.ofChannel("DAZN 1", "ESPAÑA | DEPORTES"))
        assertEquals("16", Countries.ofChannel("DAZN 1", "ES | DEPORTES"))
        assertEquals("44", Countries.ofChannel("MX| TUDN", ""))
        assertNull(Countries.ofChannel("DAZN 1", "Deportes"))
    }

    @Test
    fun paisDeLosCanalesDeLiveGuide() {
        assertEquals("16", Countries.ofStationName("DAZN España"))
        assertEquals("1", Countries.ofStationName("DAZN [USA]"))
        assertEquals("2", Countries.ofStationName("DAZN UK"))
        assertEquals("16", Countries.ofStationName("Amazon Prime ES"))
        assertNull(Countries.ofStationName("Antena 3"))
        assertNull(Countries.ofStationName("DAZN 1"))
        assertEquals("16", Countries.idOfName("España"))
    }

    @Test
    fun asignaSoloDelPais() {
        val list = listOf(
            Channel("ES| DAZN 1 FHD", "ESPAÑA", "e1"), Channel("ES| DAZN 1 HEVC", "ESPAÑA", "e2"),
            Channel("IT| DAZN 1", "ITALIA", "i1"), Channel("DE| DAZN 1 HD", "GERMANY", "d1"),
            Channel("PT| ELEVEN 1", "PORTUGAL", "p1"), Channel("ES| ANTENA 3 FHD", "ESPAÑA", "a1"), Channel("MX| ANTENA 3", "MEXICO", "m1"),
        )
        val idx = ChannelIndex(list)
        // «DAZN 1» de la lista de España: solo los españoles
        assertEquals(setOf("e1", "e2"), AutoAssign.clearMatches(idx, "DAZN 1", "16").map { it.url }.toSet())
        // El mismo nombre en la lista de Alemania: solo el alemán
        assertEquals(setOf("d1"), AutoAssign.clearMatches(idx, "DAZN 1", "4").map { it.url }.toSet())
        assertEquals(setOf("a1"), AutoAssign.clearMatches(idx, "Antena 3", "16").map { it.url }.toSet())
        // Sugerencias (no estrictas): los de otro país al final
        val sug = idx.rank("DAZN 1", 10, countryId = "17").map { it.first.url }
        assertEquals("i1", sug.first())
        assertTrue(sug.indexOf("e1") > 0)
    }
}
