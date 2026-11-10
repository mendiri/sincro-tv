package es.sincro.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comprueba el país de cada categoría de tu lista IPTV (categorias_iptv.csv). */
class ProviderCategoriesTest {
    private val expected = mapOf(
        "España" to "16", "Reino Unido" to "2", "Estados Unidos" to "1", "Canadá" to "7", "Alemania" to "4", "Suiza" to "6",
        "Austria" to "5", "Portugal" to "19", "Italia" to "17", "Países Bajos" to "10", "Bélgica" to "33", "Polonia" to "30",
        "Grecia" to "26", "Chipre" to "27", "Malta" to "73", "Suecia" to "21", "Dinamarca" to "28", "Noruega" to "22",
        "Finlandia" to "20", "Albania" to "36", "Eslovenia" to "23", "Bosnia" to "38", "Serbia" to "25", "Croacia" to "24",
        "Lituania" to "45", "Letonia" to "40", "Estonia" to "46", "Hungría" to "34", "Chequia" to "31", "Rumanía" to "42",
        "Bulgaria" to "50", "Rusia" to "37", "Bielorrusia" to "47", "Ucrania" to "41", "Georgia" to "74", "Kazajistán" to "71",
        "Australia" to "3", "Nueva Zelanda" to "29", "Brasil" to "60", "Turquía" to "35", "Azerbaiyán" to "59", "Israel" to "48",
        "Singapur" to "8", "Hong Kong" to "52", "Indonesia" to "51", "Tailandia" to "53", "Taiwán" to "54", "Vietnam" to "55",
        "Malasia" to "11", "Japón" to "77", "Filipinas" to "49", "Islandia" to "72",
    )

    @Test
    fun todasLasCategorias() {
        // El listado de categorías es personal y no va en el repositorio: la prueba se hace solo si está
        val res = javaClass.classLoader!!.getResource("categorias_iptv.csv")
        org.junit.Assume.assumeTrue(res != null)
        val rows = res!!.readText().lines().drop(1)
            .filter { it.isNotBlank() }.map { it.split(';') }
        var checked = 0
        val bad = mutableListOf<String>()
        for (r in rows) {
            val cat = r[1]; val group = r[3]
            if (cat.startsWith("CH| CHINA") || cat.startsWith("HR| HORSE")) continue // el CSV los pone en Suiza/Croacia
            val got = Countries.ofProviderLabel(cat)
            val want = expected[group]
            if (want != null) {
                checked++
                if (got == null || want !in got) bad += "$cat -> $got (esperado $want)"
            }
        }
        println("Categorías con país comprobadas: $checked")
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun casosDificiles() {
        assertEquals(setOf("39", "12", "85", "58"), Countries.ofProviderLabel("AR| BEIN SPORTS FHD"))   // árabe, no Argentina
        assertEquals(setOf("58"), Countries.ofProviderLabel("AR| MOROCCO"))
        assertEquals(setOf("44"), Countries.ofProviderLabel("LAT| MEXICO"))
        assertEquals(setOf("61"), Countries.ofProviderLabel("LAT| ARG FANATIZ PPV"))
        assertEquals(setOf("48"), Countries.ofProviderLabel("IS| ISRAEL HD"))                            // Israel, no Islandia
        assertEquals(emptySet<String>(), Countries.ofProviderLabel("SK| SOUTH KOREA"))                   // Corea, no Eslovaquia
        assertEquals(emptySet<String>(), Countries.ofProviderLabel("HR| HORSE RACING"))
        assertEquals(setOf("84", "52"), Countries.ofProviderLabel("CH| CHINA / HONGKONG"))
        assertEquals(setOf("15"), Countries.ofProviderLabel("IR| IRELAND"))
        assertEquals(emptySet<String>(), Countries.ofProviderLabel("IR| IRAN"))
        assertEquals(setOf("21"), Countries.ofProviderLabel("SWEDEN SPORT HD & HEVC"))
        assertEquals(setOf("76"), Countries.ofProviderLabel("EU| LUXEMBOURG"))
        assertEquals(emptySet<String>(), Countries.ofProviderLabel("BEE| BEIN SPORTS"))
        assertEquals(null, Countries.ofProviderLabel("24/7 MOVIES & SERIES"))
    }

    @Test
    fun asignacionConCategoriasReales() {
        val list = listOf(
            Channel("ES| DAZN LALIGA FHD", "ES| DAZN LALIGA", "e1"), Channel("ES| DAZN LALIGA 4K", "ES| DAZN LALIGA", "e2"),
            Channel("FR| DAZN LA LIGA 1", "FR| DAZN LA LIGA PPV", "f1"),
            Channel("AR| BEIN SPORTS 1 FHD", "AR| BEIN SPORTS FHD", "a1"), Channel("ES| BEIN SPORTS 1", "ES| OTROS DEPORTES", "e3"),
            Channel("BEE| BEIN SPORTS 1", "BEE| BEIN SPORTS", "b1"), Channel("TR| BEIN SPORTS 1", "TR| SPOR KANALLARI", "t1"),
        )
        val idx = ChannelIndex(list)
        assertEquals(setOf("e1", "e2"), AutoAssign.clearMatches(idx, "DAZN Laliga", "16").map { it.url }.toSet())
        assertEquals(setOf("e3"), AutoAssign.clearMatches(idx, "beIN Sports 1", "16").map { it.url }.toSet())
        assertEquals(setOf("t1"), AutoAssign.clearMatches(idx, "beIN Sports 1", "35").map { it.url }.toSet())
        assertEquals(setOf("a1"), AutoAssign.clearMatches(idx, "beIN Sports 1", "39").map { it.url }.toSet())   // Catar
    }

    @Test
    fun retrasosYNombresPegados() {
        assertTrue(AutoAssign.isTimeshift("UK| ITV 1 +1"))
        assertTrue(AutoAssign.isTimeshift("AR| MBC AM +6H"))
        assertTrue(!AutoAssign.isTimeshift("ES| M+ LALIGA TV FHD"))
        assertTrue(!AutoAssign.isTimeshift("UK| SKY SPORTS+ 1"))
        assertEquals(Names.tokens("ITV 2"), Names.tokens("ITV2"))
        val idx = ChannelIndex(listOf(Channel("UK| ITV 1 HD", "UK| GENERAL", "a"), Channel("UK| ITV 1 +1", "UK| GENERAL", "b"), Channel("UK| ITV 2 HD", "UK| GENERAL", "c")))
        assertEquals(listOf("a"), AutoAssign.clearMatches(idx, "ITV", "2").map { it.url })
        assertEquals(listOf("c"), AutoAssign.clearMatches(idx, "ITV2", "2").map { it.url })
    }
}
