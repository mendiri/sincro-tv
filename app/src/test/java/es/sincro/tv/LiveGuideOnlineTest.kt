package es.sincro.tv

import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveGuideOnlineTest {
    @Test
    fun descargaDeVerdad() {
        assumeTrue(System.getenv("LIVE") == "1")
        LiveGuide.debug = { println("DEBUG $it") }
        val t0 = System.currentTimeMillis()
        val ms = LiveGuide.fetch((System.getenv("COUNTRIES") ?: "16,2,19,17,4,18,1,44,61,10,33,35,60,21,22").split(","))
        println("Tiempo: ${System.currentTimeMillis() - t0} ms")
        println("Deportes: " + ms.groupingBy { it.sport }.eachCount())
        println("Canales distintos: " + ms.flatMap { it.channels }.distinct().size)
        println("Eventos: ${ms.size}")
        ms.forEach { println("${it.date} ${it.time} ${it.title} [${it.competition}] ${it.channels}") }
    }
}
