package es.sincro.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationsTest {
    @Test
    fun leeLosCanalesDeEspana() {
        val html = javaClass.classLoader!!.getResource("stations_es.html")!!.readText()
        val st = Stations.parse(html, "16")
        println("${st.size} canales: " + st.take(15).joinToString { it.name })
        assertTrue(st.size > 80)
        val a3 = st.first { it.name == "Antena 3" }
        assertEquals("España", a3.country)
        assertTrue(a3.logo.endsWith("a620.png"))
        assertTrue(st.any { it.name == "DAZN Laliga" })
    }
}
