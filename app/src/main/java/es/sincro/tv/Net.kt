package es.sincro.tv

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Por dónde sale lo de tu proveedor IPTV (la lista y los canales):
 * - por la Raspberry (el proxy de gluetun), si está activado y responde: el Fire TV no conecta su VPN;
 * - si no, por la VPN del propio Fire TV, como siempre.
 * La guía, la radio y las actualizaciones van siempre directas.
 */
object Net {
    /** true = lo de IPTV va por la Raspberry; false = por la VPN del Fire TV (o directo si no hay VPN). */
    @Volatile var viaRaspberry = false
        private set
    /** Mensaje si la Raspberry está activada pero no responde. */
    @Volatile var raspberryProblem: String? = null
        private set
    @Volatile private var client: OkHttpClient? = null

    /** "192.168.0.12:8888" -> (host, puerto). Sin puerto, 8888. */
    fun parse(hp: String): Pair<String, Int>? {
        val t = hp.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (t.isBlank()) return null
        val host = t.substringBeforeLast(':', t)
        val port = t.substringAfterLast(':', "").toIntOrNull() ?: 8888
        return if (host.isBlank() || port !in 1..65535) null else host to port
    }

    /** ¿Responde el proxy? Bloquea como mucho 2 s. */
    fun reachable(hp: String): Boolean {
        val (h, port) = parse(hp) ?: return false
        return try { Socket().use { it.connect(InetSocketAddress(h, port), 2000) }; true } catch (e: Exception) { false }
    }

    /**
     * Decide la red y la prepara: con la Raspberry, apaga la VPN del Fire TV; si no, la enciende.
     * Devuelve el error de la VPN del Fire TV, si lo hubo. Bloquea: llamar fuera del hilo principal.
     */
    fun prepare(app: App): String? {
        val p = app.prefs
        client = null
        if (p.useProxy && p.proxy.isNotBlank()) {
            if (reachable(p.proxy)) {
                viaRaspberry = true
                raspberryProblem = null
                if (app.vpn.state == com.wireguard.android.backend.Tunnel.State.UP) app.vpn.down()
                return null
            }
            raspberryProblem = "la Raspberry (${p.proxy}) no responde"
        } else raspberryProblem = null
        viaRaspberry = false
        if (!app.vpn.isConfigured()) return null
        return try { app.vpn.up(); null } catch (e: Exception) { VpnController.explain(e) }
    }

    /** Cliente de red para lo de IPTV (lista, API y canales). */
    fun iptvClient(): OkHttpClient = client ?: run {
        val hp = if (viaRaspberry) parse(App.instance.prefs.proxy) else null
        val proxy = hp?.let { Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(it.first, it.second)) } ?: Proxy.NO_PROXY
        Http.client.newBuilder().proxy(proxy).build().also { client = it }
    }

    /** Comprueba por qué país sale el proxy. Bloquea. */
    fun testProxy(hp: String): String {
        val (h, port) = parse(hp) ?: return "la dirección no es válida (ej. 192.168.0.12:8888)."
        if (!reachable(hp)) return "no responde en $h:$port. ¿Está encendido gluetun y publicado ese puerto?"
        return try {
            val c = Http.client.newBuilder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(h, port)))
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
            val body = c.newCall(Request.Builder().url("http://ip-api.com/json/?fields=status,country,query").build())
                .execute().use { it.body!!.string() }
            val o = JSONObject(body)
            "funciona: sale por ${o.optString("country", "?")} (IP ${o.optString("query", "?")})."
        } catch (e: Exception) {
            "responde, pero no deja salir a internet (${e.message ?: e.javaClass.simpleName})."
        }
    }
}
