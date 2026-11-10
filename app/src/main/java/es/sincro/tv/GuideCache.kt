package es.sincro.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** La última guía descargada, para enseñarla al instante al abrir la app. */
object GuideCache {
    private fun file(dir: File) = File(dir, "guide.json")

    fun save(dir: File, list: List<Match>) {
        val a = JSONArray()
        for (m in list) a.put(JSONObject().put("d", m.date).put("t", m.time).put("c", m.competition).put("h", m.home)
            .put("a", m.away).put("s", m.sport).put("ch", JSONArray(m.channels)).put("ci", JSONArray(m.channelIds)))
        try { file(dir).writeText(a.toString()) } catch (_: Exception) { }
    }

    fun load(dir: File): List<Match>? = try {
        val a = JSONArray(file(dir).readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val ch = o.getJSONArray("ch")
            val ci = o.optJSONArray("ci")
            Match(o.getString("d"), o.getString("t"), o.optString("c"), o.optString("h"), o.optString("a"),
                (0 until ch.length()).map { ch.getString(it) }, o.optString("s", "Fútbol"),
                (0 until (ci?.length() ?: 0)).map { ci!!.getString(it) })
        }
    } catch (e: Exception) { null }
}
