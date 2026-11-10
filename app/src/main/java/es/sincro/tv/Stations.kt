package es.sincro.tv

import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.Executors

/** Un canal de Live Guide (p. ej. "TVE 1", de España). */
data class Station(val name: String, val country: String, val logo: String, val id: String = "") {
    /** Clave única: el código de Live Guide ("#1686") o, si no se sabe, el nombre. */
    val ref get() = refOf(name, id)
}

/** Clave de un canal de la guía: "#código" si se conoce (único), si no, el nombre. */
fun refOf(name: String, id: String?) = if (!id.isNullOrBlank()) "#$id" else name

/** La lista completa de canales de Live Guide de tus países, guardada para no pedirla cada vez. */
object Stations {
    private const val BASE = "https://sport-tv-guide.live"
    private fun file(dir: File) = File(dir, "canales_liveguide.json")

    /** País de cada canal de Live Guide según la lista guardada, por clave ("#código" o nombre; null si el nombre está en varios países). */
    fun countryIndex(dir: File): Map<String, String?> = try {
        val a = JSONObject(file(dir).readText()).getJSONArray("list")
        val m = HashMap<String, String?>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val st = Station(o.getString("n"), o.getString("c"), "", o.optString("i"))
            val c = countryOf(st)
            if (st.id.isNotBlank()) m[st.ref] = c
            m[st.name] = if (m.containsKey(st.name) && m[st.name] != c) null else c
        }
        m
    } catch (e: Exception) { emptyMap() }

    /** País de un canal de Live Guide: lo que diga su nombre o, si no, la lista de la que viene. */
    fun countryOf(st: Station): String? = Countries.ofStationName(st.name) ?: Countries.idOfName(st.country)

    fun countryName(id: String) = LiveGuide.COUNTRIES.firstOrNull { it.first == id }?.second ?: "Otros"

    /** Lee la lista de canales de un país (lo que devuelve la web al pulsar «Pick channels»). */
    fun parse(html: String, countryId: String): List<Station> {
        val doc = Jsoup.parse(html)
        return doc.select("input[name=channels[]]").mapNotNull { inp ->
            val img = inp.parent()?.selectFirst("img") ?: return@mapNotNull null
            val name = img.attr("title").ifBlank { inp.parent()?.selectFirst("span")?.text().orEmpty() }.trim()
            if (name.isBlank()) null
            else Station(name, countryName(inp.attr("data-country").ifBlank { countryId }), img.attr("data-src").ifBlank { img.attr("src") }, inp.attr("value"))
        }.distinctBy { it.ref }
    }

    fun download(countries: List<String>): List<Station> {
        val pool = Executors.newFixedThreadPool(6)
        try {
            val jobs = countries.map { c ->
                pool.submit<List<Station>> {
                    val body = FormBody.Builder().add("c", c).add("n", countryName(c)).build()
                    val req = Request.Builder().url("$BASE/ajaxdata/selectStations").post(body)
                        .header("User-Agent", Http.WEB_UA).header("X-Requested-With", "XMLHttpRequest").build()
                    Http.client.newCall(req).execute().use { r -> if (r.isSuccessful) parse(r.body!!.string(), c) else emptyList() }
                }
            }
            return jobs.flatMap { runCatching { it.get() }.getOrDefault(emptyList()) }
        } finally { pool.shutdownNow() }
    }

    /** Canales de tus países + los que hayan salido en la guía. Se renueva cada semana o si cambias de países. */
    fun all(dir: File, countries: List<String>, extraFromGuide: List<Pair<String, String>>, force: Boolean = false): List<Station> {
        val f = file(dir)
        val key = countries.joinToString(",")
        var list: List<Station>? = null
        if (!force) try {
            val o = JSONObject(f.readText())
            if (o.optInt("v") == 2 && o.optString("countries") == key && System.currentTimeMillis() - f.lastModified() < 7 * 86400_000L) {
                val a = o.getJSONArray("list")
                list = (0 until a.length()).map { a.getJSONObject(it) }.map { Station(it.getString("n"), it.getString("c"), it.optString("l"), it.optString("i")) }
            }
        } catch (_: Exception) { }
        if (list == null) {
            list = download(countries)
            if (list.isNotEmpty()) {
                val a = JSONArray()
                list.forEach { a.put(JSONObject().put("n", it.name).put("c", it.country).put("l", it.logo).put("i", it.id)) }
                try { f.writeText(JSONObject().put("v", 2).put("countries", key).put("list", a).toString()) } catch (_: Exception) { }
            }
        }
        val known = list.flatMap { listOf(it.ref, it.name) }.toHashSet()
        return list + extraFromGuide.filter { (n, id) -> n.isNotBlank() && refOf(n, id) !in known && known.add(refOf(n, id)) }
            .map { (n, id) -> Station(n, "Vistos en la guía", "", id) }
    }
}
