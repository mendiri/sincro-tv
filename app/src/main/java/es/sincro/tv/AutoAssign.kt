package es.sincro.tv

import kotlin.concurrent.thread

/**
 * «Asignar automáticamente» en segundo plano: para cada canal de Live Guide sin nada asignado,
 * añade las variantes claras de tu lista (4K, HEVC, FHD…). Guarda por tandas y se puede seguir el progreso.
 */
object AutoAssign {
    @Volatile var running = false; private set
    @Volatile var done = 0; private set
    @Volatile var total = 0; private set
    @Volatile var assigned = 0; private set
    @Volatile var error: String? = null; private set

    /** Canales de tu lista muy parecidos (las variantes de un mismo canal). */
    /** Lo que puede sobrar en el nombre de tu lista sin que sea otro canal: calidad, formato, "TV"… */
    private val VARIANT = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "1080", "1080p", "720", "720p", "3840p", "2160p", "1920p",
        "50fps", "60fps", "raw", "vip", "backup", "bk", "multi", "audio", "hdr", "lq", "hq", "ultra", "tv", "channel", "int",
        "gold", "plus", "live",
    )

    /**
     * Canales de tu lista que son el mismo canal (sus variantes 4K, HEVC, FHD…).
     * Tienen que tener todas las palabras del nombre de Live Guide y no sobrarles ninguna que no sea de calidad o formato:
     * "DAZN 1 FHD" vale para "DAZN 1", pero no para "DAZN España" ni para "DAZN 2".
     */
    /** Versiones con retraso ("ITV 1 +1", "MBC AM +6H"): no sirven para ver el directo. */
    private val TIMESHIFT = Regex("(?<![A-Za-z0-9])\\+\\s?\\d+\\s*[Hh]?\\b")
    fun isTimeshift(name: String) = TIMESHIFT.containsMatchIn(name)

    fun clearMatches(idx: ChannelIndex, name: String, countryId: String? = null): List<Channel> =
        clearMatchesAll(idx, name, countryId).filter {
            !isTimeshift(it.name) && (countryId == null || !Countries.mentionsOtherCountry(it.name, countryId, name))
        }

    private fun clearMatchesAll(idx: ChannelIndex, name: String, countryId: String?): List<Channel> {
        val want = Names.tokens(name)
        if (want.isEmpty()) return emptyList()
        val ranked = idx.rank(name, 80, strict = true, countryId = countryId)
        val exact = ranked.filter { (ch, _) ->
            val have = Names.tokens(ch.name)
            have.containsAll(want) && (have - want).all { it in VARIANT }
        }.map { it.first }
        if (exact.isNotEmpty()) return exact
        // Sin coincidencia exacta: el "1" puede faltar o sobrar ("ITV" = "ITV 1", "M. LaLiga 1" = "M+ LALIGA TV")
        val core = want - "1"
        if (core.isEmpty()) return emptyList()
        val loose = if ("1" in want) idx.rank((core).joinToString(" "), 80, strict = true, countryId = countryId) else ranked
        return loose.filter { (ch, _) ->
            val have = Names.tokens(ch.name)
            have.containsAll(core) && (have - core).all { it in VARIANT || it == "1" }
        }.map { it.first }
    }

    @Synchronized
    fun start(stations: () -> List<Station>) {
        if (running) return
        running = true; done = 0; total = 0; assigned = 0; error = null
        thread(name = "auto-asignar", isDaemon = true) {
            try {
                val app = App.instance
                val p = app.prefs
                if (app.playlist.channels.isEmpty()) app.playlist.ensureLoaded(p.m3u)
                val idx = app.playlist.index()
                val todo = stations().filter { p.mappings(it.ref).isEmpty() }.distinctBy { it.ref }
                total = todo.size
                val batch = LinkedHashMap<String, List<Channel>>()
                for (st in todo) {
                    val m = clearMatches(idx, st.name, Stations.countryOf(st))
                    if (m.isNotEmpty()) { batch[st.ref] = m; assigned++ }
                    done++
                    if (batch.size >= 50) { p.setMappingsBulk(batch); batch.clear() }
                }
                p.setMappingsBulk(batch)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                running = false
            }
        }
    }
}
