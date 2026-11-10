package es.sincro.tv

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Simulación con tu lista real (CSV) y los canales reales de Live Guide. Escribe un informe. */
class RealAssignTest {
    @Test
    fun simular() {
        val csv = System.getenv("CANALES_CSV")
        assumeTrue(System.getenv("LIVE") == "1" && csv != null)
        val list = File(csv!!).readLines().drop(1).mapNotNull { l ->
            val p = l.split(';')
            if (p.size < 3) null else Channel(p.drop(2).joinToString(";"), p[1], "u${p.hashCode()}")
        }.filter { !Playlist.isHeader(it.name) }
        val idx = ChannelIndex(list)
        val stCache = File(System.getenv("ST_CACHE") ?: "/tmp/st.tsv")
        val stations = if (stCache.exists()) stCache.readLines().map { val p = it.split('\t'); Station(p[0], p[1], "", p.getOrElse(2) { "" }) }
        else Stations.download(LiveGuide.ALL_COUNTRIES.split(',')).also { s -> stCache.writeText(s.joinToString("\n") { "${it.name}\t${it.country}\t${it.id}" }) }
        val out = StringBuilder()
        var assigned = 0
        val seenRefs = HashSet<String>()
        val t0 = System.currentTimeMillis()
        for (st in stations) {
            val cid = Stations.countryOf(st)
            if (!seenRefs.add(st.ref)) continue
            val m = AutoAssign.clearMatches(idx, st.name, cid)
            if (m.isNotEmpty()) assigned++
            out.append("${st.country}\t${st.name}\t${st.ref}\t${m.size}\t${m.take(6).joinToString(" | ") { it.name + " {" + it.group + "}" }}\n")
        }
        println("Canales IPTV: ${list.size} · Live Guide: ${stations.size} · asignados: $assigned · ${System.currentTimeMillis() - t0} ms")
        File(System.getenv("REPORT") ?: "/tmp/report.tsv").writeText(out.toString())
    }
}
