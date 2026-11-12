package es.sincro.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Casos de la captura: el mismo partido en español y en inglés ("Belgium at Turkey"). */
class DuplicatesTest {
    private fun ev(href: String, time: String, title: String, league: String, chans: List<Pair<String, String>>) = """
        <a class="article flag" href="$href"><div class="row">
        <div class="main col-xs-2"><div class="text-center"><b>$time</b></div><div class="text-center">
        <img data-src='https://img2.sport-tv-guide.live/images/t1.png' title='Fútbol' alt='Fútbol'></div></div>
        <div class="col-xs-6"><div class="clearfix text-nowrap"><div class="text-nowrap">$title</div><div class="text-nowrap"><b>$league</b></div></div></div>
        <div class="col-xs-4">${chans.joinToString("") { (n, id) -> "<img data-src=\"https://img2.sport-tv-guide.live/images/tvm-station-x-$id.png\" title=\"$n\" alt=\"$n\">" }}</div>
        </div></a>
    """.trimIndent()

    private val chA = listOf("Sky Sport Calcio" to "21", "Sport TV 1" to "450", "DAZN España" to "1686", "La Liga TV Bar M3" to "2654")
    private val chB = listOf("DAZN" to "923", "Sky Sport 251" to "300", "Sport TV 2" to "451", "LA 1" to "17")

    @Test
    fun mismoPartidoEnDosIdiomas() {
        val html = ev("/event/turquia-belgica/1", "18:00", "Turquía - Bélgica", "UEFA Nations League B", chA) +
            ev("/tournament/football/europe/nations-league#ontv", "18:00", "Belgium at Turkey", "UEFA Nations League B", chA) +
            ev("/tournament/football/europe/nations-league#ontv", "20:45", "Czech Republic at Spain", "UEFA Nations League A", chB) +
            ev("/event/republica-checa-espana/2", "20:45", "República Checa - España", "UEFA Nations League A", chB)
        val raw = LiveGuide.parse(html, "12/11/2026")
        // "X at Y" se entiende como visitante X, local Y
        val en = raw.first { it.home == "Spain" }
        assertEquals("Czech Republic", en.away)
        // Al limpiar quedan 2 partidos, en su versión en español
        val list = LiveGuide.dedupeRanked(raw.map { it to (if (it.home == "Spain" || it.home == "Turkey") 1 else 0) })
        assertEquals(listOf("Turquía – Bélgica", "República Checa – España"), list.map { it.title })
        // Y el de España es de tus equipos (★)
        val teams = Follow.parse(Follow.DEFAULT_TEAMS)
        assertTrue(Follow.followsTeam(teams, list.last()))
        assertTrue(Follow.followsTeam(teams, en)) // también la versión inglesa, por si fuera la única
    }

    @Test
    fun partidosDistintosALaMismaHoraNoSeJuntan() {
        val html = ev("/event/a/1", "20:45", "Italia - Francia", "UEFA Nations League A", listOf("Rai 1" to "5", "Sport TV 1" to "450", "DAZN España" to "1686")) +
            ev("/event/b/2", "20:45", "Inglaterra - Croacia", "UEFA Nations League A", listOf("ITV" to "9", "DAZN España" to "1686", "La Liga TV Bar M4" to "2655"))
        assertEquals(2, LiveGuide.dedupe(LiveGuide.parse(html, "12/11/2026")).size)
    }
}
