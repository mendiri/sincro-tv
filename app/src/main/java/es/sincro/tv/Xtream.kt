package es.sincro.tv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Proveedores "Xtream Codes": el mismo sistema que usa TiviMate al poner servidor, usuario y contraseña. */
class Xtream(val server: String, val user: String, val pass: String, val ext: String) {

    class BadLogin : IOException("usuario o contraseña no válidos")

    val m3uUrl get() = "$server/get.php?username=${enc(user)}&password=${enc(pass)}&type=m3u_plus&output=$ext"
    private fun api(action: String?) =
        "$server/player_api.php?username=${enc(user)}&password=${enc(pass)}" + (action?.let { "&action=$it" } ?: "")

    private fun get(url: String, ua: String): String {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        return Net.iptvClient().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Playlist.HttpError(r.code)
            r.body!!.string()
        }
    }

    /** Descarga los canales en directo por la API y los devuelve en formato M3U. */
    fun toM3u(ua: String): String {
        val login = get(api(null), ua).trim()
        if (!login.startsWith("{")) throw IOException("respuesta inesperada")
        val info = JSONObject(login).optJSONObject("user_info") ?: throw IOException("respuesta inesperada")
        if (info.optString("auth") == "0" || info.optInt("auth", 1) == 0) throw BadLogin()
        val status = info.optString("status")
        if (status.isNotBlank() && !status.equals("Active", true)) throw IOException("tu cuenta aparece como «$status»")
        return buildM3u(get(api("get_live_categories"), ua), get(api("get_live_streams"), ua))
    }

    fun buildM3u(categoriesJson: String, streamsJson: String): String {
        val cats = HashMap<String, String>()
        val ca = JSONArray(categoriesJson)
        for (i in 0 until ca.length()) {
            val c = ca.getJSONObject(i)
            cats[c.optString("category_id")] = c.optString("category_name")
        }
        val sa = JSONArray(streamsJson)
        val sb = StringBuilder(sa.length() * 120).append("#EXTM3U\n")
        for (i in 0 until sa.length()) {
            val s = sa.getJSONObject(i)
            val id = s.optString("stream_id")
            if (id.isBlank()) continue
            val name = s.optString("name").replace('\n', ' ').trim()
            val group = (cats[s.optString("category_id")] ?: "").replace('"', '\'')
            sb.append("#EXTINF:-1 group-title=\"").append(group).append("\",").append(name).append('\n')
            sb.append(server).append("/live/").append(user).append('/').append(pass).append('/').append(id).append('.').append(ext).append('\n')
        }
        return sb.toString()
    }

    companion object {
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

        /** Saca servidor, usuario y contraseña de un enlace tipo ...?username=X&password=Y. */
        fun from(link: String): Xtream? {
            val u = link.trim().toHttpUrlOrNull() ?: return null
            val user = u.queryParameter("username")?.takeIf { it.isNotBlank() } ?: return null
            val pass = u.queryParameter("password")?.takeIf { it.isNotBlank() } ?: return null
            val defaultPort = (u.scheme == "http" && u.port == 80) || (u.scheme == "https" && u.port == 443)
            val server = "${u.scheme}://${u.host}" + (if (defaultPort) "" else ":${u.port}")
            val out = u.queryParameter("output")?.lowercase()
            val ext = if (out == "m3u8" || out == "hls") "m3u8" else "ts"
            return Xtream(server, user, pass, ext)
        }
    }
}
