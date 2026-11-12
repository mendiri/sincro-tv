package es.sincro.tv

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream

/** VPN de Proton (WireGuard) integrada en la app. */
class VpnController(private val ctx: Context) {
    private val backend by lazy { GoBackend(ctx.applicationContext) }
    @Volatile var state: Tunnel.State = Tunnel.State.DOWN
        private set

    private val tunnel = object : Tunnel {
        override fun getName() = "proton"
        override fun onStateChange(newState: Tunnel.State) { state = newState }
    }

    fun isConfigured() = App.instance.prefs.wg.contains("[Interface]", ignoreCase = true)

    /** Conecta. Bloquea unos segundos: llamar fuera del hilo principal. */
    @Synchronized
    fun up() {
        if (!isConfigured() || state == Tunnel.State.UP) return
        val cfg = buildConfig(App.instance.prefs.wg)
        state = backend.setState(tunnel, Tunnel.State.UP, cfg)
    }

    @Synchronized
    fun down() {
        try { state = backend.setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) { }
        state = Tunnel.State.DOWN
    }

    companion object {
        /** 0.0.0.0/0 sin las redes de casa: así el móvil puede abrir la página de ajustes con la VPN puesta. */
        private val PUBLIC_V4 = listOf(
            "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3", "64.0.0.0/2",
            "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/6", "172.0.0.0/12", "172.32.0.0/11", "172.64.0.0/10",
            "172.128.0.0/9", "173.0.0.0/8", "174.0.0.0/7", "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11",
            "192.160.0.0/13", "192.169.0.0/16", "192.170.0.0/15", "192.172.0.0/14", "192.176.0.0/12",
            "192.192.0.0/10", "193.0.0.0/8", "194.0.0.0/7", "196.0.0.0/6", "200.0.0.0/5", "208.0.0.0/4",
        )

        fun fixAllowedIps(text: String): String {
            val dns = Regex("(?im)^\\s*DNS\\s*=\\s*(.+)$").find(text)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim() }
                ?.filter { it.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) } ?: emptyList()
            return text.lines().joinToString("\n") { line ->
                val t = line.trim()
                if (t.startsWith("AllowedIPs", true) && t.contains("0.0.0.0/0")) {
                    val ips = PUBLIC_V4 + dns.map { "$it/32" } + (if (t.contains("::/0")) listOf("::/0") else emptyList())
                    "AllowedIPs = " + ips.joinToString(", ")
                } else line
            }
        }

        /** Quita lo que se cuela al copiar desde una web en el móvil: espacios raros, caracteres invisibles, saltos dentro de las claves. */
        fun sanitize(text: String): String {
            val clean = text.replace("\r", "")
                .replace(Regex("[\u00A0\u2007\u202F\u2000-\u200A]"), " ")
                .replace(Regex("[\u200B-\u200D\u2060\uFEFF]"), "")
            return clean.lines().joinToString("\n") { line ->
                val m = Regex("(?i)^\\s*(PrivateKey|PublicKey|PresharedKey)\\s*=\\s*(.*)$").find(line)
                if (m != null) "${m.groupValues[1]} = ${m.groupValues[2].replace(Regex("\\s+"), "")}" else line
            }
        }

        fun buildConfig(text: String): Config =
            Config.parse(ByteArrayInputStream(fixAllowedIps(sanitize(text)).toByteArray()))

        /** Revisa cada clave de la configuración sin mostrarla. Devuelve líneas legibles. */
        fun keyReport(text: String): List<String> {
            if (text.isBlank()) return listOf("No hay ninguna configuración guardada.")
            val out = mutableListOf<String>()
            val clean = sanitize(text)
            for (name in listOf("PrivateKey", "PublicKey")) {
                val v = Regex("(?im)^\\s*$name\\s*=\\s*(\\S*)").find(clean)?.groupValues?.get(1)
                out += when {
                    v == null -> "$name: falta. El fichero está incompleto."
                    v.isEmpty() -> "$name: está vacía."
                    else -> try {
                        com.wireguard.crypto.Key.fromBase64(v)
                        "$name: correcta."
                    } catch (e: Throwable) {
                        val bad = v.filterNot { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
                        "$name: NO válida (${v.length} caracteres; debe tener 44 y acabar en «=»" +
                            (if (bad.isNotEmpty()) "; lleva caracteres raros: ${bad.map { "U+%04X".format(it.code) }.distinct().joinToString()}" else "") +
                            (if (v.contains('…') || v.contains("...")) "; parece recortada con «…»" else "") + ")."
                    }
                }
            }
            if (out.all { it.endsWith("correcta.") }) {
                try { buildConfig(text); out += "La configuración completa se lee bien." } catch (e: Throwable) { out += "Otro problema: ${explain(e)}" }
            }
            return out
        }

        /** Mensaje entendible para un error al leer la configuración o conectar. */
        fun explain(e: Throwable): String {
            val n = e.javaClass.simpleName + " " + (e.message ?: "") + " " + (e.cause?.javaClass?.simpleName ?: "")
            return when {
                n.contains("KeyFormat") -> "la clave de la configuración WireGuard no es válida; súbela otra vez desde el móvil con el fichero .conf"
                n.contains("ParseException") || n.contains("BadConfig") -> "la configuración WireGuard está incompleta; súbela otra vez con el fichero .conf"
                n.contains("VPN_NOT_AUTHORIZED") || n.contains("NOT_AUTHORIZED") -> "falta darle permiso a la VPN"
                else -> e.message ?: e.javaClass.simpleName
            }
        }
    }
}
