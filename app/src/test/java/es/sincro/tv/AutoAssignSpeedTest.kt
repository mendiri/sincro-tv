package es.sincro.tv

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AutoAssignSpeedTest {
    @Test
    fun rapidoConMuchosCanales() {
        val rnd = Random(1)
        val pre = listOf("ES|", "UK|", "IT|", "DE|", "FR|", "PT|", "US|", "MX|", "AR|", "TR|", "NL|", "PL|")
        val brands = listOf("SPORT", "SPORTS", "TV", "SKY", "DAZN", "ESPN", "FOX", "BEIN", "EUROSPORT", "CANAL", "MOVISTAR", "NEWS", "CINEMA", "KIDS", "MUSIC", "TNT", "RAI", "ARENA", "NOVA", "PRIME")
        val q = listOf("HD", "FHD", "4K", "HEVC", "SD", "")
        val list = ArrayList<Channel>()
        repeat(55_000) {
            val n = "${pre.random(rnd)} ${brands.random(rnd)} ${if (rnd.nextBoolean()) brands.random(rnd) else ""} ${rnd.nextInt(1, 12)} ${q.random(rnd)}"
            list += Channel(n.replace(Regex("\\s+"), " ").trim(), "G${rnd.nextInt(300)}", "http://x/$it")
        }
        list += Channel("ES| TVE 1 4K", "ESPAÑA", "u1"); list += Channel("ES| TVE 1 HEVC", "ESPAÑA", "u2"); list += Channel("ES| TVE 1 FHD", "ESPAÑA", "u3")
        val stations = (1..2617).map { "${brands.random(rnd)} ${if (rnd.nextBoolean()) brands.random(rnd) else ""} ${rnd.nextInt(1, 20)}".trim() } + "TVE 1"
        val idx = ChannelIndex(list)
        val t0 = System.currentTimeMillis()
        var found = 0
        for (s in stations) if (AutoAssign.clearMatches(idx, s).isNotEmpty()) found++
        val t1 = System.currentTimeMillis()
        println("Auto-asignar ${stations.size} canales: ${t1 - t0} ms, con coincidencia clara: $found")
        val tve = AutoAssign.clearMatches(idx, "TVE 1").map { it.name }
        println("TVE 1 -> $tve")
        assertTrue(tve.containsAll(listOf("ES| TVE 1 4K", "ES| TVE 1 HEVC", "ES| TVE 1 FHD")))
        // Antes (sin la mejora): la búsqueda normal
        val t2 = System.currentTimeMillis()
        for (s in stations.take(300)) idx.rank(s, 40)
        println("Búsqueda normal, 300 canales: ${System.currentTimeMillis() - t2} ms")
    }
}
