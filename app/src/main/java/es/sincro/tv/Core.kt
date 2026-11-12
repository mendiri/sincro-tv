package es.sincro.tv

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var vpn: VpnController
    val playlist by lazy { Playlist(filesDir, Updater.installedCode(this)) }
    val radio by lazy { RadioPlayer(this) }

    /** Arranca la radio de fondo (en silencio) si está activada. */
    fun startRadio() {
        if (prefs.radioOn && prefs.radioUrl.isNotBlank()) {
            radio.setDelay(prefs.delay)
            radio.start(prefs.radioUrl, Http.UA)
        }
    }
    var server: SetupServer? = null

    /** Se llama (en el hilo principal) cuando se guardan ajustes desde el móvil. */
    var onConfigChanged: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    fun notifyConfigChanged() = main.post { onConfigChanged?.invoke() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        vpn = VpnController(this)
    }

    companion object {
        lateinit var instance: App
    }
}

object Http {
    const val UA = "VLC/3.0.20 LibVLC/3.0.20"
    /** Identificaciones de reproductor que se prueban con el proveedor, en este orden. */
    val PLAYER_UAS = listOf(UA, "okhttp/4.12.0", "TiviMate/5.1.6 (Android 11)", "IPTVSmartersPlayer")
    const val WEB_UA = "Mozilla/5.0 (Linux; Android 11; AFTKA) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

const val COPE_URL = "https://net2-cope-rrcast.flumotion.com/cope/net2.mp3"
const val OLD_DEFAULT_COUNTRIES = "16,2,19,17,4,18,1,44,61,10,33,35,60,21,22"
const val OLD_COPE_URL = "https://flucast-b02-06.flumotion.com/cope/net1.mp3"

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    var m3u: String get() = str("m3u"); set(v) = put("m3u", v)
    var wg: String get() = str("wg"); set(v) = put("wg", v)
    var radioUrl: String get() = str("radio", COPE_URL).let { if (it == OLD_COPE_URL) COPE_URL else it }; set(v) = put("radio", v)
    var radioName: String get() = str("radioName", "COPE Tiempo de Juego").let { if (it == "COPE") "COPE Tiempo de Juego" else it }; set(v) = put("radioName", v)
    var team: String get() = str("team"); set(v) = put("team", v)
    /** Países de Live Guide (códigos de la web; máximo 15). */
    var countries: String get() = str("countries", LiveGuide.ALL_COUNTRIES).let { if (it == OLD_DEFAULT_COUNTRIES) LiveGuide.ALL_COUNTRIES else it }
        set(v) = put("countries", v)
    /** Deportes que quieres ver (vacío = todos). */
    var sports: String get() = str("sports", ""); set(v) = put("sports", v)
    /** Deportes vistos en la guía, para ofrecerlos en los ajustes. */
    var knownSports: String get() = str("knownSports", ""); set(v) = put("knownSports", v)
    var onlyTeams: Boolean get() = sp.getBoolean("onlyTeams", true); set(v) = sp.edit().putBoolean("onlyTeams", v).apply()
    /** Ligas que sigues (una por línea, "Deporte: Liga"). */
    var leagues: String get() = str("leagues", Follow.DEFAULT_LEAGUES); set(v) = put("leagues", v)

    init {
        // Primera vez con esta versión: lo que sigues en Live Guide
        if (!sp.getBoolean("followDefaults1", false)) {
            sp.edit().putString("team", Follow.DEFAULT_TEAMS).putString("leagues", Follow.DEFAULT_LEAGUES)
                .putBoolean("onlyTeams", true).putBoolean("followDefaults1", true).apply()
        }
    }
    /** "liveguide" o "futbolenlatv" */
    var guide: String get() = str("guide", "liveguide"); set(v) = put("guide", v)
    var delay: Float get() = sp.getFloat("delay", 30f); set(v) = sp.edit().putFloat("delay", v).apply()
    var radioOn: Boolean get() = sp.getBoolean("radioOn", true); set(v) = sp.edit().putBoolean("radioOn", v).apply()
    /** Proxy de la Raspberry (gluetun), "192.168.0.12:8888". */
    var proxy: String get() = str("proxy"); set(v) = put("proxy", v)
    /** Usar la Raspberry para lo de IPTV en vez de la VPN del Fire TV. */
    var useProxy: Boolean get() = sp.getBoolean("useProxy", false); set(v) = sp.edit().putBoolean("useProxy", v).apply()
    var lastOfferedUpdate: Int get() = sp.getInt("lastOfferedUpdate", 0); set(v) = sp.edit().putInt("lastOfferedUpdate", v).apply()
    var autoAdjust: Boolean get() = sp.getBoolean("autoAdjust", true); set(v) = sp.edit().putBoolean("autoAdjust", v).apply()

    /**
     * Asignaciones: canal de la guía -> uno o varios canales de tu lista, en orden.
     * Se guardan en un fichero aparte (pueden ser muchas).
     */
    private val mapFile = java.io.File(ctx.filesDir, "asignaciones.json")
    private var mapCache: JSONObject? = null

    @Synchronized
    private fun maps(): JSONObject {
        mapCache?.let { return it }
        val m = try { JSONObject(mapFile.readText()) } catch (e: Exception) {
            // Versiones anteriores: estaban en los ajustes, uno por canal
            try { JSONObject(str("maps", "{}")) } catch (e2: Exception) { JSONObject() }
        }
        mapCache = m
        return m
    }

    @Synchronized
    private fun saveMaps(m: JSONObject) {
        mapCache = m
        try { mapFile.writeText(m.toString()) } catch (_: Exception) { }
    }

    /**
     * Clave del canal de la guía. Con código de Live Guide ("#2510") es única aunque el nombre
     * se repita en varios países ("DAZN 1" de España ≠ "DAZN 1" de Francia).
     */
    fun mapKey(siteName: String) = if (siteName.startsWith("#")) siteName else "=" + java.text.Normalizer.normalize(siteName.lowercase().trim(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9+]+"), " ").trim()

    private fun toList(v: Any?): List<Channel> = when (v) {
        is org.json.JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }.map { Channel(it.optString("name"), it.optString("group"), it.optString("url")) }
        is JSONObject -> listOf(Channel(v.optString("name"), v.optString("group"), v.optString("url")))
        else -> emptyList()
    }.filter { it.url.isNotBlank() && !Playlist.isHeader(it.name) }

    /** Todos los canales de tu lista asignados a un canal de la guía. */
    fun mappings(siteName: String): List<Channel> {
        val m = maps()
        if (siteName.startsWith("#")) return toList(m.opt(siteName))
        return toList(m.opt(mapKey(siteName))).ifEmpty { toList(m.opt(Names.key(siteName))) }
    }

    fun mapping(siteName: String): Channel? = mappings(siteName).firstOrNull()

    fun setMappings(siteName: String, list: List<Channel>) {
        val m = maps()
        if (!siteName.startsWith("#")) m.remove(Names.key(siteName))
        if (list.isEmpty()) m.remove(mapKey(siteName))
        else m.put(mapKey(siteName), org.json.JSONArray(list.distinctBy { it.url }.map {
            JSONObject().put("name", it.name).put("group", it.group).put("url", it.url)
        }))
        saveMaps(m)
    }

    /** Muchas asignaciones de golpe, guardando una sola vez. */
    fun setMappingsBulk(all: Map<String, List<Channel>>) {
        if (all.isEmpty()) return
        val m = maps()
        for ((site, list) in all) {
            m.put(mapKey(site), org.json.JSONArray(list.distinctBy { it.url }.map {
                JSONObject().put("name", it.name).put("group", it.group).put("url", it.url)
            }))
        }
        saveMaps(m)
    }

    /** Elegido a mano en la tele: pasa a ser el primero. */
    fun setMapping(siteName: String, ch: Channel) = setMappings(siteName, listOf(ch) + mappings(siteName).filter { it.url != ch.url })

    /** Elegido automáticamente y comprobado que funciona: solo si no había nada asignado. */
    fun rememberIfEmpty(siteName: String, ch: Channel) { if (mappings(siteName).isEmpty()) setMappings(siteName, listOf(ch)) }

    fun clearMappings() = saveMaps(JSONObject())

    // ---------- Copia de la configuración (exportar / importar) ----------

    /**
     * Toda la configuración en un JSON, para llevarla a otro dispositivo.
     * Con [includeSecrets] incluye la lista IPTV (con usuario y contraseña) y la clave de la VPN.
     */
    fun exportJson(includeSecrets: Boolean, appVersion: String): JSONObject {
        val c = JSONObject()
        if (includeSecrets) c.put("m3u", m3u).put("wg", wg)
        c.put("radio", radioUrl).put("radioName", radioName).put("delay", delay.toDouble())
            .put("radioOn", radioOn).put("autoAdjust", autoAdjust)
            .put("team", team).put("leagues", leagues).put("sports", sports).put("knownSports", knownSports)
            .put("onlyTeams", onlyTeams).put("countries", countries).put("guide", guide)
            .put("proxy", proxy).put("useProxy", useProxy)
        return JSONObject().put("app", "sincro-tv").put("formato", 1).put("version", appVersion)
            .put("conSecretos", includeSecrets).put("ajustes", c).put("asignaciones", maps())
    }

    /** Carga una copia exportada. Devuelve qué se ha importado, en palabras. */
    fun importJson(o: JSONObject): List<String> {
        require(o.optString("app") == "sincro-tv") { "este fichero no es una copia de Sincro TV" }
        val c = o.optJSONObject("ajustes") ?: JSONObject()
        val done = mutableListOf<String>()
        fun has(k: String) = c.has(k) && !c.isNull(k)
        if (has("m3u") && c.optString("m3u").isNotBlank()) { m3u = c.optString("m3u"); done += "lista de canales" }
        if (has("wg") && c.optString("wg").isNotBlank()) { wg = c.optString("wg"); done += "VPN de Proton" }
        if (has("radio")) { radioUrl = c.optString("radio"); radioName = c.optString("radioName", radioName); done += "emisora" }
        if (has("delay")) { delay = c.optDouble("delay", delay.toDouble()).toFloat(); done += "retardo de la radio" }
        if (has("radioOn")) radioOn = c.optBoolean("radioOn", radioOn)
        if (has("autoAdjust")) autoAdjust = c.optBoolean("autoAdjust", autoAdjust)
        if (has("team")) team = c.optString("team")
        if (has("leagues")) leagues = c.optString("leagues")
        if (has("team") || has("leagues")) done += "ligas y equipos"
        if (has("sports")) sports = c.optString("sports")
        if (has("knownSports")) knownSports = c.optString("knownSports")
        if (has("onlyTeams")) onlyTeams = c.optBoolean("onlyTeams", onlyTeams)
        if (has("countries")) { countries = c.optString("countries"); done += "países" }
        if (has("guide")) guide = c.optString("guide")
        if (has("proxy")) { proxy = c.optString("proxy"); useProxy = c.optBoolean("useProxy", false) && proxy.isNotBlank(); done += "Raspberry" }
        o.optJSONObject("asignaciones")?.let { saveMaps(it); done += "${it.length()} asignaciones de canales" }
        // Lo que sigues ya viene en la copia: que no lo pisen los valores por defecto
        sp.edit().putBoolean("followDefaults1", true).apply()
        return done
    }
    fun mappingCount() = maps().length()
}

fun lanAddress(): String? = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("wg") }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
} catch (e: Exception) { null }

fun fmtSec(v: Float): String = String.format(java.util.Locale("es", "ES"), "%.1f", v)
