package es.sincro.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexTest {
    private fun ch(n: String, g: String) = Channel(n, g, "http://x/$n")

    @Test
    fun eligeElPaisCorrectoYEsRapido() {
        val list = ArrayList<Channel>()
        list += ch("ES| DAZN 1 FHD", "ESPAÑA | DEPORTES")
        list += ch("ES| DAZN 1 HD", "ESPAÑA | DEPORTES")
        list += ch("IT| DAZN 1", "ITALIA | SPORT")
        list += ch("UK: DAZN 1", "UK SPORTS")
        list += ch("ES| LALIGA TV HYPERMOTION FHD", "ESPAÑA | DEPORTES")
        list += ch("UK| SKY SPORTS MAIN EVENT", "UK SPORTS")
        repeat(55_000) { list += ch("XX| Canal $it", "Otros $it") }
        val t0 = System.currentTimeMillis()
        val idx = ChannelIndex(list)
        val t1 = System.currentTimeMillis()
        val es = idx.rank("DAZN 1")
        val it = idx.rank("DAZN Italia")
        val hyp = idx.rank("LALIGA TV HYPERMOTION")
        val sky = idx.rank("Sky Sports Main Event")
        val t2 = System.currentTimeMillis()
        println("índice ${t1 - t0} ms, 4 búsquedas ${t2 - t1} ms")
        assertEquals("ES| DAZN 1 FHD", es.first().first.name)
        assertEquals("IT| DAZN 1", it.first().first.name)
        assertEquals("ES| LALIGA TV HYPERMOTION FHD", hyp.first().first.name)
        assertTrue(hyp.first().second >= 0.8)
        assertEquals("UK| SKY SPORTS MAIN EVENT", sky.first().first.name)
        assertEquals(1, idx.filter("hypermotion").size)
    }
}
