package es.sincro.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGuideTest {
    @Test
    fun leeLaProgramacion() {
        val html = javaClass.classLoader!!.getResource("liveguide.html")!!.readText()
        val ms = LiveGuide.parse(html, "09/11/2026")
        ms.forEach { println("${it.time} ${it.title} [${it.competition}] ${it.channels}") }
        assertEquals(5, ms.size)
        val s = ms.first { it.home == "Sabadell" }
        assertEquals("R. Sociedad B", s.away)
        assertEquals("LaLiga 1 2 3", s.competition)
        assertTrue(s.channels.contains("LALIGA TV HYPERMOTION"))
        assertEquals("18:00", ms.first { it.home == "Koln W" }.time)
    }

    @Test
    fun horas() {
        assertEquals("18:00", LiveGuide.to24h("6:00PM"))
        assertEquals("00:30", LiveGuide.to24h("12:30AM"))
        assertEquals("12:15", LiveGuide.to24h("12:15PM"))
        assertEquals("21:00", LiveGuide.to24h("21:00"))
    }
}
