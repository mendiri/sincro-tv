package es.sincro.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** La lista procesada se guarda y, al volver a abrir la app, se carga igual pero mucho más rápido. */
class PlaylistCacheTest {
    private fun m3u(): String {
        val csv = System.getenv("CANALES_CSV")?.let { File(it) }?.takeIf { it.exists() }
        val sb = StringBuilder("#EXTM3U\n")
        if (csv != null) {
            csv.readLines().drop(1).forEachIndexed { i, l ->
                val p = l.split(';')
                if (p.size >= 3) sb.append("#EXTINF:-1 group-title=\"${p[1].replace("\"", "'")}\",${p.drop(2).joinToString(";")}\nhttp://iptv.example.com/live/u/p/$i.ts\n")
            }
        } else {
            val pre = listOf("ES|", "UK|", "IT|", "DE|", "FR|", "AR|", "LAT|")
            for (i in 0 until 55_000) sb.append("#EXTINF:-1 group-title=\"${pre[i % pre.size]} SPORTS\",${pre[i % pre.size]} CANAL ${i % 700} SPORT ${i % 9} FHD\nhttp://x/$i.ts\n")
        }
        sb.append("#EXTINF:-1 group-title=\"ES| DAZN ESPAÑA\",ES| DAZN 1 FHD\nhttp://x/dazn1.ts\n")
        return sb.toString()
    }

    @Test
    fun guardaYCargaLaListaProcesada() {
        val dir = Files.createTempDirectory("lista").toFile()
        File(dir, "playlist.m3u").writeText(m3u())
        File(dir, "playlist.url").writeText("http://iptv.example.com/get.php")

        val t0 = System.currentTimeMillis()
        val a = Playlist(dir, 18).apply { ensureLoaded("http://iptv.example.com/get.php") }
        val t1 = System.currentTimeMillis()
        assertNotNull("debería haberse guardado", File(dir, "playlist.cache").takeIf { it.exists() })
        val b = Playlist(dir, 18).apply { ensureLoaded("http://iptv.example.com/get.php") }
        val t2 = System.currentTimeMillis()
        println("Canales: ${a.channels.size} · procesar: ${t1 - t0} ms · cargar lo guardado: ${t2 - t1} ms · fichero ${File(dir, "playlist.cache").length() / 1024} KB")

        assertEquals(a.channels, b.channels)
        for (q in listOf("DAZN 1", "Antena 3", "Sky Sports Main Event", "beIN Sports 1")) {
            assertEquals(a.index().rank(q, 20, countryId = "16").map { it.first.url }, b.index().rank(q, 20, countryId = "16").map { it.first.url })
            assertEquals(AutoAssign.clearMatches(a.index(), q, "16"), AutoAssign.clearMatches(b.index(), q, "16"))
        }
        assertEquals(a.index().filter("dazn 1"), b.index().filter("dazn 1"))

        // Con otra versión de la app, lo guardado no vale y se vuelve a procesar
        val c = Playlist(dir, 19).apply { ensureLoaded("http://iptv.example.com/get.php") }
        assertEquals(a.channels, c.channels)
        dir.deleteRecursively()
    }
}
