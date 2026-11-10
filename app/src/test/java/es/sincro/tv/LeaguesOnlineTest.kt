package es.sincro.tv

import org.junit.Assume.assumeTrue
import org.junit.Test

class LeaguesOnlineTest {
    @Test
    fun nombresDeLigas() {
        assumeTrue(System.getenv("LIVE") == "1")
        val ms = LiveGuide.fetch("16,2,19,17,4,18,1,44,61,10,33,35,60,21,22".split(','))
        ms.map { "${it.sport} | ${it.competition}" }.distinct().sorted().forEach { println("LIGA $it") }
        ms.filter { m -> listOf("Spain", "España", "Madrid", "Barcelona", "Zaragoza", "Alcaraz", "Badosa", "Topuria").any { (m.home + m.away).contains(it) } }
            .forEach { println("EQ ${it.sport} | ${it.home} | ${it.away} | ${it.competition}") }
    }
}
