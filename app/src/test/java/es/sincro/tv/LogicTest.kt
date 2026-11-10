package es.sincro.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.util.Calendar
import java.util.TimeZone

class LogicTest {
    @Test
    fun leeLaGuiaDePartidos() {
        val html = javaClass.classLoader!!.getResource("futbolenlatv.html")!!.readText()
        val all = Schedule.parse(html)
        assertTrue("debería haber partidos", all.size > 50)
        val betis = all.first { it.home == "Elche" && it.away == "Real Betis" }
        assertEquals("14:00", betis.time)
        assertEquals("08/11/2026", betis.date)
        assertTrue(betis.competition.contains("La Liga"))
        assertEquals(listOf("DAZN LaLiga (M55 O113)", "LaLiga TV Bar"), betis.channels)

        val now = Calendar.getInstance(TimeZone.getTimeZone("Europe/Madrid")).apply { set(2026, Calendar.NOVEMBER, 8, 17, 0) }
        val up = Schedule.upcoming(all, now)
        assertTrue(up.none { it.date == "08/11/2026" && it.time == "14:00" })
        assertTrue(up.any { it.date == "09/11/2026" })
        assertTrue(betis.involves("real betis"))
        println("Partidos leídos: ${all.size}, próximos: ${up.size}")
        up.take(8).forEach { println("${it.date} ${it.time} ${it.title} [${it.competition}] ${it.channels}") }
    }

    @Test
    fun leeListaM3uYEmpareja() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="" tvg-name="ES| DAZN LALIGA FHD" group-title="ESPAÑA | DEPORTES",ES| DAZN LALIGA FHD
            http://prov.tv:8080/u/p/101.ts
            #EXTINF:-1 tvg-name="ES| DAZN LALIGA 2 FHD" group-title="ESPAÑA | DEPORTES",ES| DAZN LALIGA 2 FHD
            http://prov.tv:8080/u/p/102.ts
            #EXTINF:-1 tvg-name="ES| MOVISTAR LALIGA FHD" group-title="ESPAÑA | DEPORTES",ES| MOVISTAR LALIGA FHD
            http://prov.tv:8080/u/p/103.ts
            #EXTINF:-1 tvg-name="ES| M+ LIGA DE CAMPEONES HD" group-title="ESPAÑA | DEPORTES",ES| M+ LIGA DE CAMPEONES HD
            http://prov.tv:8080/u/p/104.ts
            #EXTINF:-1 group-title="ESPAÑA | DEPORTES",##### DEPORTES ESPAÑA #####
            http://prov.tv:8080/u/p/999.ts
            #EXTINF:-1 group-title="Cine, estrenos",Película X
            http://prov.tv:8080/movie/u/p/9.mkv
        """.trimIndent()
        val chans = Playlist.parse(StringReader(m3u))
        assertEquals(4, chans.size)
        assertEquals("ESPAÑA | DEPORTES", chans[0].group)

        assertEquals("ES| DAZN LALIGA FHD", Names.rank("DAZN LaLiga (M55 O113)", chans).first().name)
        assertEquals("ES| MOVISTAR LALIGA FHD", Names.rank("M+ LALIGA (M54 O110)", chans).first().name)
        assertEquals("ES| M+ LIGA DE CAMPEONES HD", Names.rank("M+ Liga de Campeones (M60 O60)", chans).first().name)
        assertEquals(Names.key("DAZN LaLiga (M55 O113)"), Names.key("DAZN LALIGA"))
        assertEquals(1, Names.filter("dazn 2", chans).size)
    }

    @Test
    fun limpiaYOrdenaLaGuia() {
        assertEquals("LALIGA TV Hypermotion (M56 O120)", Schedule.cleanChannel("LALIGA TV Hypermotion (M56 O120): VER PARTIDO"))
        assertEquals("DAZN App Gratis", Schedule.cleanChannel("DAZN App Gratis (Ver gratis)"))
        val now = Calendar.getInstance(TimeZone.getTimeZone("Europe/Madrid")).apply { set(2026, Calendar.NOVEMBER, 9, 17, 0) }
        val ms = listOf(
            Match("09/11/2026", "20:30", "", "A", "B", emptyList()),
            Match("10/11/2026", "12:00", "", "E", "F", emptyList()),
            Match("09/11/2026", "18:00", "", "C", "D", emptyList()),
        )
        assertEquals(listOf("C", "A", "E"), Schedule.upcoming(ms, now).map { it.home })
    }

    @Test
    fun entiendeEnlacesXtream() {
        val x = Xtream.from("https://iptv.example.com/?username=usu&password=cla&type=m3u_plus&output=ts")!!
        assertEquals("https://iptv.example.com", x.server)
        assertEquals("https://iptv.example.com/get.php?username=usu&password=cla&type=m3u_plus&output=ts", x.m3uUrl)
        val y = Xtream.from("http://prov.tv:8080/get.php?username=a&password=b&type=m3u_plus&output=m3u8")!!
        assertEquals("http://prov.tv:8080", y.server); assertEquals("m3u8", y.ext)
        assertEquals(null, Xtream.from("http://prov.tv/lista.m3u"))

        val m3u = x.buildM3u(
            """[{"category_id":"5","category_name":"ESPAÑA | DEPORTES"}]""",
            """[{"name":"ES| DAZN LALIGA FHD","stream_id":101,"category_id":"5"},{"name":"ES| M+ LALIGA","stream_id":"102","category_id":"9"}]"""
        )
        val chans = Playlist.parse(StringReader(m3u))
        assertEquals(2, chans.size)
        assertEquals("ESPAÑA | DEPORTES", chans[0].group)
        assertEquals("https://iptv.example.com/live/usu/cla/101.ts", chans[0].url)
        assertEquals("ES| M+ LALIGA", chans[1].name)
    }

    @Test
    fun excluyeRedesDeCasaDeLaVpn() {
        val conf = """
            [Interface]
            PrivateKey = aaaa
            Address = 10.2.0.2/32
            DNS = 10.2.0.1

            [Peer]
            PublicKey = bbbb
            AllowedIPs = 0.0.0.0/0
            Endpoint = 1.2.3.4:51820
        """.trimIndent()
        val fixed = VpnController.fixAllowedIps(conf)
        assertTrue(fixed.contains("10.2.0.1/32"))
        assertTrue(!fixed.contains("0.0.0.0/0"))
        assertTrue(fixed.contains("192.0.0.0/9"))
    }
}
