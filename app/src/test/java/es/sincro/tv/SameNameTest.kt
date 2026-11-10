package es.sincro.tv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Casos de las capturas: el mismo nombre en varios países y el país escrito en el nombre del canal. */
class SameNameTest {
    private val list = listOf(
        Channel("FR| DAZN 1 FHD", "FR| DAZN LA LIGA PPV", "fr1"), Channel("FR| DAZN 1 HD", "FR| DAZN PPV", "fr2"),
        Channel("ES| DAZN 1 FHD ᴿᴬᵂ", "ES| SPAIN VIP ᴿᴬᵂ", "es1"), Channel("ES| DAZN 1 FHD", "ES| DAZN ESPAÑA", "es2"),
        Channel("LV| EUROSPORT 1", "WORLD LIVE SPORTS", "lv1"), Channel("ES| EUROSPORT 1 FHD", "ES| OTROS DEPORTES", "es3"),
        Channel("PL| ELEVEN SPORTS 2 FHD", "PL| SPORTOWE", "pl1"), Channel("PL-VIP| ELEVEN SPORTS 2 RAW", "PL| POLAND VIP", "pl2"),
        Channel("PLAY+| ELEVEN SPORTS 2 ᴿᴬᵂ", "PL| CANAL+ ONLINE SPORT ᴿᴬᵂ", "pl3"), Channel("UK| ELEVEN SPORTS 2 HD", "UK| SPORTS", "uk1"),
        Channel("FR| BEIN SPORTS 1", "ES| OTROS DEPORTES", "raro"),
    )
    private val idx = ChannelIndex(list)
    private fun urls(name: String, country: String) = AutoAssign.clearMatches(idx, name, country).map { it.url }.toSet()

    @Test
    fun cadaPaisLoSuyo() {
        assertEquals(setOf("es1", "es2"), urls("DAZN 1", "16"))
        assertEquals(setOf("fr1", "fr2"), urls("DAZN 1", "18"))
        assertEquals(setOf("es3"), urls("Eurosport 1", "16"))
        assertEquals(setOf("lv1"), urls("Eurosport 1", "40"))
        assertEquals(setOf("uk1"), urls("Eleven Sports 2", "2"))
        assertEquals(setOf("pl1", "pl2", "pl3"), urls("Eleven Sports 2", "30"))
        // El nombre manda: un "FR|" dentro de una categoría española es de Francia
        assertEquals(setOf("18"), Countries.ofChannelSet("FR| BEIN SPORTS 1", "ES| OTROS DEPORTES"))
        // Pero en los prefijos de zona manda la categoría
        assertEquals(setOf("58"), Countries.ofChannelSet("AR| 2M MAROC", "AR| MOROCCO"))
        assertEquals(setOf("44"), Countries.ofChannelSet("LAT| ESPN", "LAT| MEXICO"))
    }

    @Test
    fun claveUnicaPorCodigo() {
        assertEquals("#2510", refOf("DAZN 1", "2510"))
        assertEquals("DAZN 1", refOf("DAZN 1", ""))
        val html = javaClass.classLoader!!.getResource("liveguide.html")!!.readText()
        val m = LiveGuide.parse(html, "09/11/2026").first { it.home.startsWith("Atletico") }
        println("${m.channels} ${m.channelIds}")
        assertEquals(m.channels.size, m.channelIds.size)
        assertEquals("2510", m.channelIds[m.channels.indexOf("DAZN 1")])
        val st = Stations.parse(javaClass.classLoader!!.getResource("stations_es.html")!!.readText(), "16")
        assertEquals("#2510", st.first { it.name == "DAZN 1" }.ref)
    }

    @Test
    fun otroPaisEnElNombre() {
        val idx = ChannelIndex(listOf(Channel("IT| DAZN 2 ES HD", "WORLD LIVE SPORTS", "x"), Channel("IT| DAZN 2 HD", "IT| DAZN", "y")))
        assertEquals(setOf("y"), AutoAssign.clearMatches(idx, "DAZN 2", "17").map { it.url }.toSet())
        assertEquals(true, Countries.mentionsOtherCountry("IT| DAZN 2 ES HD", "17"))
        assertEquals(false, Countries.mentionsOtherCountry("ES| DAZN 2 FHD", "16"))
        assertEquals(false, Countries.mentionsOtherCountry("UK| SKY SPORTS MAIN EVENT", "2"))
        assertEquals(true, Countries.mentionsOtherCountry("UK| BEIN SPORTS FRANCE", "2"))
    }
}
