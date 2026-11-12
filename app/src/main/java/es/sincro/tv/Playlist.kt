package es.sincro.tv

import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.Reader
import java.text.Normalizer

data class Channel(val name: String, val group: String, val url: String)

/** Tu lista de canales (la misma M3U que usabas en TiviMate). */
/** [version]: versión de la app; si cambia, la lista guardada se vuelve a procesar (por si cambian las reglas). */
class Playlist(private val dir: File, private val version: Int = 0) {
    @Volatile var channels: List<Channel> = emptyList()
        private set
    @Volatile private var idx: ChannelIndex? = null

    /** Índice (se crea una vez por lista). */
    fun index(): ChannelIndex = idx?.takeIf { it.channels === channels } ?: ChannelIndex(channels).also { idx = it }
    private val file get() = File(dir, "playlist.m3u")
    private val urlFile get() = File(dir, "playlist.url")

    /** Descarga (si hace falta, como mucho cada 12 h) y lee la lista. Bloquea: llamar fuera del hilo principal. */
    @Synchronized
    fun ensureLoaded(url: String, force: Boolean = false) {
        if (url.isBlank()) throw IOException("Falta el enlace de tu lista de canales")
        val sameUrl = urlFile.exists() && urlFile.readText() == url
        val stale = force || !file.exists() || !sameUrl ||
            System.currentTimeMillis() - file.lastModified() > 12 * 3600_000L
        var downloaded = false
        if (stale) {
            try {
                download(url)
                urlFile.writeText(url)
                downloaded = true
            } catch (e: Exception) {
                // Sin conexión: sirve la copia guardada si es de esta misma lista
                if (!file.exists() || !sameUrl) throw e
            }
        }
        if (downloaded || channels.isEmpty()) {
            // Lista sin cambios desde la última vez: se carga ya procesada (mucho más rápido)
            val cached = if (!downloaded) loadCache() else null
            if (cached != null) {
                channels = cached.channels
                idx = cached
            } else {
                val list = file.bufferedReader().use { parse(it) }
                val ix = ChannelIndex(list)
                channels = list
                idx = ix
                saveCache(ix)
            }
        }
    }

    // ---------- Lista ya procesada, guardada en disco ----------

    private val cacheFile get() = File(dir, "playlist.cache")

    /** Identifica la lista y la forma de procesarla: si algo cambia, lo guardado no vale. */
    private fun stamp() = "${file.length()}|${file.lastModified()}|$version|$CACHE_FORMAT"

    private fun saveCache(ix: ChannelIndex) {
        val tmp = File(dir, "playlist.cache.tmp")
        try {
            val pre = ix.precomputed()
            java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 1 shl 20)).use { o ->
                o.writeUTF(stamp())
                o.writeInt(ix.channels.size)
                for (i in ix.channels.indices) {
                    val c = ix.channels[i]
                    o.writeUTF(c.name); o.writeUTF(c.group); o.writeUTF(c.url)
                    o.writeUTF(pre.toks[i].joinToString(" "))
                    val co = pre.country[i]
                    o.writeBoolean(co != null)
                    if (co != null) o.writeUTF(co.joinToString(","))
                    o.writeUTF(pre.norm[i])
                }
            }
            if (!tmp.renameTo(cacheFile)) tmp.delete()
        } catch (e: Exception) {
            tmp.delete() // sin guardar no pasa nada: la próxima vez se vuelve a procesar
        }
    }

    private fun loadCache(): ChannelIndex? = try {
        if (!cacheFile.exists() || !file.exists()) null
        else java.io.DataInputStream(java.io.BufferedInputStream(cacheFile.inputStream(), 1 shl 20)).use { inp ->
            if (inp.readUTF() != stamp()) return@use null
            val n = inp.readInt()
            val list = ArrayList<Channel>(n)
            val toks = arrayOfNulls<Set<String>>(n)
            val country = arrayOfNulls<Set<String>>(n)
            val norm = arrayOfNulls<String>(n)
            for (i in 0 until n) {
                list += Channel(inp.readUTF(), inp.readUTF(), inp.readUTF())
                toks[i] = inp.readUTF().split(' ').filter { it.isNotEmpty() }.toHashSet()
                country[i] = if (inp.readBoolean()) inp.readUTF().split(',').filter { it.isNotEmpty() }.toHashSet() else null
                norm[i] = inp.readUTF()
            }
            @Suppress("UNCHECKED_CAST")
            ChannelIndex(list, ChannelIndex.Precomputed(toks as Array<Set<String>>, country, norm as Array<String>))
        }
    } catch (e: Exception) {
        null // dañado o de otra versión: se vuelve a procesar
    }

    private val uaFile get() = File(dir, "playlist.ua")

    /** Identificación de reproductor que aceptó tu proveedor; se usa también para ver los canales. */
    val userAgent: String get() = try { uaFile.readText().ifBlank { Http.UA } } catch (e: Exception) { Http.UA }

    class HttpError(val code: Int) : IOException("error $code")

    /**
     * Prueba varias formas, como hacen TiviMate y compañía:
     * 1) la API Xtream (servidor + usuario + contraseña sacados del enlace),
     * 2) el enlace get.php estándar, 3) el enlace tal cual; cada una con varias identificaciones de reproductor.
     */
    private fun download(url: String) {
        val tried = mutableListOf<String>()
        var authFailed = false
        // Primero el enlace tal cual; luego el mismo con http/https cambiado (muchos proveedores solo aceptan http)
        for (link in listOf(url, swapScheme(url)).distinct()) {
        val xt = Xtream.from(link)
        for (ua in Http.PLAYER_UAS) {
            if (xt != null) {
                try {
                    writeAtomically(xt.toM3u(ua)); uaFile.writeText(ua); return
                } catch (e: Xtream.BadLogin) {
                    authFailed = true; tried += "API: usuario o contraseña no válidos"
                } catch (e: Exception) { tried += "API: ${e.message}" }
                try { fetchM3u(xt.m3uUrl, ua); uaFile.writeText(ua); return } catch (e: Exception) { tried += "get.php: ${e.message}" }
            }
            if (xt == null || link != xt.m3uUrl) {
                try { fetchM3u(link, ua); uaFile.writeText(ua); return } catch (e: Exception) { tried += "enlace: ${e.message}" }
            }
        }
        }
        val hint = when {
            authFailed -> "Tu proveedor dice que el usuario o la contraseña no son válidos. Comprueba que son los mismos que en TiviMate."
            tried.any { it.contains("401") || it.contains("403") } ->
                "Tu proveedor rechaza la conexión (${tried.first()}). Comprueba el usuario y la contraseña, y que el servidor es el mismo que en TiviMate."
            else -> "No he podido descargar tu lista (${tried.firstOrNull() ?: "sin respuesta"})"
        }
        throw IOException(hint)
    }

    private fun swapScheme(u: String) = when {
        u.startsWith("https://", true) -> "http://" + u.substring(8)
        u.startsWith("http://", true) -> "https://" + u.substring(7)
        else -> u
    }

    private fun fetchM3u(url: String, ua: String) {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        Net.iptvClient().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code)
            val tmp = File(dir, "playlist.tmp")
            tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            val head = tmp.inputStream().use { String(it.readNBytesCompat(2048)) }
            if (!head.contains("#EXT")) { tmp.delete(); throw IOException("no es una lista M3U") }
            tmp.renameTo(file)
        }
    }

    private fun writeAtomically(text: String) {
        val tmp = File(dir, "playlist.tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) { val r = read(buf, off, n - off); if (r < 0) break; off += r }
        return buf.copyOf(off)
    }

    companion object {
        /** Súbelo si cambia el formato del fichero guardado. */
        private const val CACHE_FORMAT = 1
        private val GROUP = Regex("group-title=\"([^\"]*)\"")
        private val TVG_NAME = Regex("tvg-name=\"([^\"]*)\"")

        /** Lee una lista M3U y se queda solo con los canales en directo (sin películas ni series). */
        fun parse(reader: Reader): List<Channel> {
            val out = ArrayList<Channel>()
            val br = if (reader is BufferedReader) reader else BufferedReader(reader)
            var name: String? = null
            var group = ""
            while (true) {
                val raw = br.readLine() ?: break
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#EXTINF", true)) {
                    group = GROUP.find(line)?.groupValues?.get(1) ?: ""
                    name = titleOf(line).ifBlank { TVG_NAME.find(line)?.groupValues?.get(1) ?: "" }
                } else if (line.startsWith("#")) {
                    if (line.startsWith("#EXTGRP:", true)) group = line.substringAfter(':').trim()
                } else if (name != null) {
                    val low = line.lowercase()
                    if (!isHeader(name) && !low.contains("/movie/") && !low.contains("/series/") &&
                        !low.endsWith(".mkv") && !low.endsWith(".mp4") && !low.endsWith(".avi")) {
                        out += Channel(name, group, line)
                    }
                    name = null
                }
            }
            return out
        }

        /** Los "canales" con ##### en el nombre son encabezados de la lista, no canales de verdad. */
        fun isHeader(name: String) = name.contains("##") || name.trim().let { it.isNotEmpty() && it.all { c -> !c.isLetterOrDigit() } }

        /** El nombre va tras la primera coma que no esté dentro de comillas. */
        private fun titleOf(extinf: String): String {
            var quoted = false
            for (i in extinf.indices) {
                val c = extinf[i]
                if (c == '"') quoted = !quoted
                else if (c == ',' && !quoted) return extinf.substring(i + 1).trim()
            }
            return ""
        }
    }
}

/** Comparar nombres de canal de la guía ("M+ LALIGA (M54 O110)") con los de tu lista ("ES| MOVISTAR LALIGA FHD"). */
object Names {
    private val NOISE = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "1080", "1080p", "720", "720p",
        "50fps", "60fps", "ver", "partido", "gratis", "es", "esp", "spain", "espana", "vip", "raw", "backup", "plus", "multi", "audio", "hdr"
    )

    fun normalize(s: String): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        t = t.replace(Regex("\\(.*?\\)|\\[.*?]"), " ")          // (M54 O110), [backup]
        t = t.replace(Regex("^[^|]{1,10}\\|"), " ")              // "ES|", "ES-C|", "PRIME|", "ASIA |"
        t = t.replace(Regex("^[a-z]{2,4}\\s*:"), " ")            // "ES:"
        t = t.replace("laligatv", "laliga tv").replace("laliga4sports", "laliga 4sports")
        t = t.replace(Regex("\\b([a-z]{2,})(\\d{1,2})\\b"), "$1 $2")   // "ITV2" = "ITV 2"
        t = t.replace("m+", " movistar ").replace("m. ", " movistar ")
        t = t.replace(Regex("\\bla\\s+liga\\b"), "laliga")
        t = t.replace(Regex("\\bliga\\s+de\\s+campeones\\b"), "champions")
        t = t.replace(Regex("[^a-z0-9]+"), " ")
        return t.trim().replace(Regex("\\s+"), " ")
    }

    fun tokens(s: String): Set<String> = normalize(s).split(' ').filter { it.isNotBlank() && it !in NOISE }.toSet()

    fun key(siteName: String) = tokens(siteName).sorted().joinToString(" ")

    /** De 0 a 1: cuánto se parece un canal de tu lista al nombre de la guía. */
    fun score(siteName: String, channelName: String): Double {
        val a = tokens(siteName)
        val b = tokens(channelName)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var hit = 0.0
        for (t in a) {
            hit += when {
                t in b -> 1.0
                t.length >= 3 && b.any { it.startsWith(t) || t.startsWith(it) && it.length >= 3 } -> 0.6
                else -> 0.0
            }
        }
        if (hit == 0.0) return 0.0
        val extra = (b.size - hit).coerceAtLeast(0.0)
        return (hit / a.size) - 0.04 * extra
    }

    fun rank(siteName: String, channels: List<Channel>, limit: Int = 60): List<Channel> =
        ChannelIndex(channels).rank(siteName, limit).map { it.first }

    /** País que dice el nombre de un canal de la guía ("DAZN España", "DAZN [USA]"); null si no lo dice. */
    fun siteCountry(name: String): String? = Countries.ofStationName(name)

    /** País de un canal de tu lista, por su prefijo ("ES|", "IT:") o su categoría. */
    fun channelCountry(name: String, group: String): String? = Countries.ofChannel(name, group)

    /** Pequeña preferencia por la mejor calidad que suele funcionar bien en el Fire TV. */
    fun quality(name: String): Double {
        val n = name.lowercase()
        return when {
            n.contains("backup") || n.contains("bkp") -> -0.03
            n.contains("fhd") || n.contains("1080") -> 0.02
            n.contains("uhd") || n.contains("4k") -> -0.01
            Regex("\\bhd\\b").containsMatchIn(n) -> 0.01
            Regex("\\bsd\\b").containsMatchIn(n) -> -0.01
            else -> 0.0
        }
    }

    fun scoreTokens(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var hit = 0.0
        for (t in a) {
            hit += when {
                t in b -> 1.0
                t.length >= 3 && b.any { it.startsWith(t) || t.startsWith(it) && it.length >= 3 } -> 0.6
                else -> 0.0
            }
        }
        if (hit == 0.0) return 0.0
        val extra = (b.size - hit).coerceAtLeast(0.0)
        return (hit / a.size) - 0.04 * extra
    }

    fun filter(query: String, channels: List<Channel>, limit: Int = 200): List<Channel> {
        val q = normalize(query).split(' ').filter { it.isNotBlank() }
        if (q.isEmpty()) return channels.take(limit)
        return channels.asSequence().filter { c ->
            val n = normalize(c.name)
            q.all { n.contains(it) }
        }.take(limit).toList()
    }
}

/** Índice de tu lista para encontrar canales al instante entre decenas de miles. */
class ChannelIndex(val channels: List<Channel>, pre: Precomputed? = null) {
    /** Lo costoso de calcular de cada canal, para guardarlo y no repetirlo al abrir la app. */
    class Precomputed(val toks: Array<Set<String>>, val country: Array<Set<String>?>, val norm: Array<String>)

    private val toks: Array<Set<String>> = pre?.toks ?: Array(channels.size) { Names.tokens(channels[it].name) }
    /** País(es) de cada canal: null = no se sabe; vacío = temático o país que Live Guide no tiene. */
    private val country: Array<Set<String>?> = pre?.country ?: Array(channels.size) { Countries.ofChannelSet(channels[it].name, channels[it].group) }
    private val inverted = HashMap<String, MutableList<Int>>().also { m ->
        toks.forEachIndexed { i, ts -> ts.forEach { t -> m.getOrPut(t) { ArrayList() }.add(i) } }
    }

    private val norm: Array<String> by lazy { pre?.norm ?: Array(channels.size) { Names.normalize(channels[it].name) } }

    fun precomputed() = Precomputed(toks, country, norm)

    /** Búsqueda por texto: todos los trozos escritos deben aparecer en el nombre. */
    fun filter(query: String, limit: Int = 500): List<Channel> {
        val q = Names.normalize(query).split(' ').filter { it.isNotBlank() }
        if (q.isEmpty()) return channels.take(limit)
        val out = ArrayList<Channel>()
        for (i in channels.indices) {
            val n = norm[i]
            if (q.all { n.contains(it) }) { out += channels[i]; if (out.size >= limit) break }
        }
        return out
    }

    /** Los canales de tu lista más parecidos a un nombre de la guía, con su puntuación (≈0..1). */
    /** País de un canal de tu lista (código de Live Guide) o null si no se sabe. */
    fun countryOf(i: Int) = country[i]

    /**
     * [countryId]: país del canal de Live Guide (de la lista de la que viene). Con [strict] (asignación automática)
     * se descartan los canales de otro país; si no, salen al final.
     */
    fun rank(siteName: String, limit: Int = 60, strict: Boolean = false, countryId: String? = null): List<Pair<Channel, Double>> {
        val a = Names.tokens(siteName)
        if (a.isEmpty()) return emptyList()
        val cands = HashSet<Int>()
        // Empieza por las palabras menos comunes: «tv», «sport» o «1» salen en miles de canales y no ayudan
        val lists = a.mapNotNull { inverted[it] }.sortedBy { it.size }
        if (lists.isNotEmpty()) {
            if (strict) {
                // Coincidencia clara: tiene que tener las dos palabras menos comunes (cruce de listas ordenadas)
                if (lists.size >= 2) {
                    val x = lists[0]; val y = lists[1]
                    var i = 0; var j = 0
                    while (i < x.size && j < y.size) {
                        val d = x[i] - y[j]
                        if (d == 0) { cands.add(x[i]); i++; j++ } else if (d < 0) i++ else j++
                    }
                } else cands.addAll(lists.first())
            }
            else lists.forEachIndexed { i, l -> if (i == 0 || l.size <= 4000) cands.addAll(l) }
        }
        if (cands.isEmpty() && !strict) { // ningún nombre igual: probar por comienzo de palabra
            for ((k, v) in inverted) if (a.any { t -> t.length >= 3 && (k.startsWith(t) || t.startsWith(k) && k.length >= 3) }) cands.addAll(v)
        }
        val want = Names.siteCountry(siteName) ?: countryId
        // Asignación automática: solo canales que sean claramente de ese país
        return cands.asSequence().filter { i -> !(strict && want != null && country[i]?.contains(want) != true) }.map { i ->
            var sc = Names.scoreTokens(a, toks[i])
            val c = country[i]
            if (want != null && c != null) sc += if (want in c) 0.15 else -0.6
            if (want != null && c == null) sc -= 0.05
            if (want == null && c != null && "16" in c) sc += 0.05
            sc += Names.quality(channels[i].name)
            channels[i] to sc
        }.filter { it.second > 0.3 }.sortedByDescending { it.second }.take(limit).toList()
    }
}
