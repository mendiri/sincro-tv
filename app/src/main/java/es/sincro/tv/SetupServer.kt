package es.sincro.tv

import fi.iki.elonen.NanoHTTPD
import java.net.URLDecoder

/** Página web en el Fire TV para configurar la app desde el móvil (http://IP-DEL-FIRE-TV:8080). */
class SetupServer(port: Int = PORT) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        if (uri == "/canales") return html(ChannelsPage.HTML)
        if (uri == "/exportar") {
            val withSecrets = session.parms["secretos"] == "1"
            val json = App.instance.prefs.exportJson(withSecrets, Updater.installedName(App.instance)).toString(2)
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json).apply {
                addHeader("Content-Disposition", "attachment; filename=\"sincro-tv${if (withSecrets) "" else "-sin-claves"}.json\"")
            }
        }
        if (uri == "/importar" && session.method == Method.POST) {
            val msg = try {
                val done = App.instance.prefs.importJson(org.json.JSONObject(readBody(session)))
                App.instance.notifyConfigChanged()
                "Importado: " + done.joinToString(", ").ifBlank { "nada" } + ". La tele se actualiza sola."
            } catch (e: Exception) {
                "No se ha podido importar: ${e.message ?: "el fichero no es válido"}."
            }
            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", msg)
        }
        if (uri.startsWith("/fonts/") && uri.endsWith(".ttf") && !uri.contains("..")) return try {
            val ins = App.instance.assets.open(uri.removePrefix("/"))
            newChunkedResponse(Response.Status.OK, "font/ttf", ins).apply { addHeader("Cache-Control", "max-age=604800") }
        } catch (e: Exception) { newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No existe") }
        if (uri.startsWith("/api/")) return try { ChannelsPage.api(uri, session, readBody(session)) } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", org.json.JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString())
        }
        val p = App.instance.prefs
        var msg = ""
        if (session.method == Method.POST) {
            val body = readBody(session)
            val f = parseForm(body)
            val multi = parseMulti(body)
            if (f["filtersSent"] == "1") {
                p.sports = multi["sport"].orEmpty().joinToString(",")
                multi["country"]?.let { if (it.isNotEmpty()) p.countries = it.joinToString(",") }
                p.onlyTeams = f["onlyTeams"] == "1"
            }
            f["m3u"]?.trim()?.let { if (it.isNotEmpty()) p.m3u = it }
            val srv = f["xsrv"]?.trim().orEmpty().trimEnd('/')
            val usr = f["xusr"]?.trim().orEmpty()
            val pwd = f["xpwd"]?.trim().orEmpty()
            if (srv.isNotEmpty() && usr.isNotEmpty() && pwd.isNotEmpty()) {
                val base = if (srv.startsWith("http", true)) srv else "http://$srv"
                p.m3u = "$base/get.php?username=${java.net.URLEncoder.encode(usr, "UTF-8")}" +
                    "&password=${java.net.URLEncoder.encode(pwd, "UTF-8")}&type=m3u_plus&output=ts"
            }
            f["wg"]?.trim()?.let { if (it.isNotEmpty()) p.wg = it }
            if (f["proxySent"] == "1") {
                p.proxy = f["proxy"]?.trim().orEmpty()
                p.useProxy = f["useProxy"] == "1" && p.proxy.isNotBlank()
            }
            f["radioName"]?.trim()?.let { if (it.isNotEmpty()) p.radioName = it }
            f["radio"]?.trim()?.let { if (it.isNotEmpty()) p.radioUrl = it }
            f["team"]?.let { p.team = it.trim() }
            f["leagues"]?.let { p.leagues = it.trim() }
            f["guide"]?.let { if (it == "liveguide" || it == "futbolenlatv") p.guide = it }
            if (f["clearMaps"] == "1") p.clearMappings()
            val err = f["wg"]?.trim()?.takeIf { it.isNotEmpty() }?.let { checkWg(it) }
            msg = (err ?: "Guardado. Ya puedes volver a la tele.") +
                (if (!f["wg"].isNullOrBlank()) "\n\nVPN: " + VpnController.keyReport(p.wg).joinToString(" ") else "") +
                (if (p.proxy.isNotBlank()) "\n\nRaspberry: " + Net.testProxy(p.proxy) + (if (p.useProxy) "" else " (desactivada: la app usa su propia VPN)") else "") +
                "\n\nEmisora: " + testRadio(p.radioUrl)
            App.instance.notifyConfigChanged()
            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", msg)
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page(p))
    }

    /** Comprueba desde el Fire TV (con la VPN si está puesta) que la emisora responde con audio. */
    private fun testRadio(url: String): String = try {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", Http.UA).build()
        Http.client.newBuilder().readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(req).execute().use { r ->
                val type = r.header("Content-Type").orEmpty()
                if (!r.isSuccessful) return@use "responde con error ${r.code}. Revisa la dirección (prueba la que tienes en VLC)."
                val buf = ByteArray(4096)
                val n = r.body!!.byteStream().read(buf)
                when {
                    n <= 0 -> "conecta pero no envía sonido."
                    type.contains("audio", true) || type.contains("mpeg", true) || type.contains("aac", true) -> "responde bien ($type)."
                    type.contains("mpegurl", true) || String(buf, 0, n).contains("#EXTM3U") -> "responde con una lista HLS ($type); debería funcionar."
                    type.contains("html", true) -> "devuelve una página web, no audio. Esa dirección no es la del stream."
                    else -> "responde ($type). Si no suena, prueba la dirección que tienes en VLC."
                }
            }
    } catch (e: Exception) {
        "no he podido conectar (${e.message ?: e.javaClass.simpleName})."
    }

    private fun html(s: String) = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", s)

    private fun checkWg(text: String): String? = try {
        VpnController.buildConfig(text); null
    } catch (e: Exception) {
        "Guardado, pero ojo: ${VpnController.explain(e)}."
    }

    private fun readBody(s: IHTTPSession): String {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = s.inputStream.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun parseMulti(body: String): Map<String, List<String>> = body.split('&').filter { it.contains('=') }
        .map { val (k, v) = it.split('=', limit = 2); URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8") }
        .groupBy({ it.first }, { it.second })

    private fun checks(name: String, options: List<Pair<String, String>>, selected: Set<String>) =
        options.joinToString("") { (value, label) ->
            "<label class=chk><input type=checkbox name=$name value=\"${esc(value)}\"${if (value in selected) " checked" else ""}> ${esc(label)}</label>"
        }

    private fun parseForm(body: String): Map<String, String> = body.split('&').filter { it.contains('=') }.associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(p: Prefs): String = """
<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sincro TV · Ajustes</title>
<style>
@font-face{font-family:Outfit;font-weight:300;src:url(/fonts/outfit_w300.ttf)}
@font-face{font-family:Outfit;font-weight:400;src:url(/fonts/outfit_w400.ttf)}
@font-face{font-family:Outfit;font-weight:500;src:url(/fonts/outfit_w500.ttf)}
:root{--bg:#F3E9E4;--card:rgba(255,255,255,.62);--card2:rgba(255,255,255,.88);--fg:#2E2A35;--muted:#5E5966;--line:rgba(185,178,199,.5);--accent:#D9506B;--focus:#FFC09A;--warn:#B8641A;color-scheme:light}
*{box-sizing:border-box}body{margin:0;background:var(--bg) fixed;background-image:radial-gradient(circle at 40% 62%,rgba(255,201,174,.7),transparent 45%),radial-gradient(circle at 66% 40%,rgba(205,184,242,.6),transparent 42%),radial-gradient(circle at 92% 78%,rgba(185,217,246,.5),transparent 45%),radial-gradient(circle at 8% 98%,rgba(248,185,201,.5),transparent 40%),radial-gradient(circle at 12% 8%,rgba(255,255,255,.4),transparent 45%);background-attachment:fixed;color:var(--fg);font:15px/1.45 Outfit,system-ui,-apple-system,Roboto,sans-serif;padding:16px}
main{max-width:560px;margin:0 auto;display:grid;gap:14px}h1{margin:4px 0;font-size:30px;font-weight:300}
.card{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:16px;display:grid;gap:8px;backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px)}
label{font-size:13px;color:var(--muted)}input,textarea{width:100%;font:inherit;color:var(--fg);background:var(--card2);border:1px solid var(--line);border-radius:12px;padding:10px 12px}
textarea{min-height:150px;font-family:ui-monospace,monospace;font-size:12px}
button{font:inherit;font-weight:500;background:var(--focus);color:var(--fg);border:0;border-radius:14px;padding:14px;width:100%}
input:focus,textarea:focus,button:focus{outline:3px solid var(--focus);outline-offset:1px}
.hint{font-size:13px;color:var(--muted);margin:0}#msg{font-weight:600;min-height:1.4em;white-space:pre-line}
.ok{color:var(--accent)}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:4px 10px}
.chk{color:var(--fg);font-size:14px;display:flex;gap:6px;align-items:center}
.chk input{width:auto}
</style></head><body><main>
<h1>Sincro TV</h1>
<a href="/canales" style="display:block;background:var(--card);border:1px solid var(--accent);border-radius:14px;padding:14px;color:var(--fg);text-decoration:none;font-weight:700">📺 Asignar canales de Live Guide a tu lista IPTV →</a>
<p class="hint">Lo que guardes aquí se queda en el Fire TV. Deja un campo vacío para no cambiarlo.</p>
<form id="f">
<div class="card">
  <label for="m3u">Enlace de tu lista de canales (M3U) ${if (p.m3u.isNotBlank()) "<span class=ok>· guardado</span>" else ""}</label>
  <input id="m3u" name="m3u" inputmode="url" placeholder="${if (p.m3u.isNotBlank()) "(guardado; pega otro para cambiarlo)" else "http://…/get.php?username=…&amp;password=…&amp;type=m3u_plus"}">
  <p class="hint">Es el mismo enlace que pusiste en TiviMate al añadir la lista.</p>
  <p class="hint"><b>O bien</b>, si en TiviMate pusiste servidor, usuario y contraseña («Xtream Codes»), escríbelos aquí:</p>
  <input name="xsrv" inputmode="url" placeholder="Servidor (ej. https://iptv.example.com)">
  <input name="xusr" placeholder="Usuario" autocapitalize="off" autocomplete="off">
  <input name="xpwd" placeholder="Contraseña" autocapitalize="off" autocomplete="off">
</div>
<div class="card">
  <label style="color:var(--fg);font-weight:600">Raspberry (VPN de gluetun)</label>
  <input type=hidden name=proxySent value=1>
  <label class=chk><input type=checkbox name=useProxy value=1 style="width:auto"${if (p.useProxy) " checked" else ""}> Usar la Raspberry para la lista y los canales (el Fire TV no conecta su VPN)</label>
  <label for="proxy">Dirección del proxy de gluetun</label>
  <input id="proxy" name="proxy" inputmode="url" value="${esc(p.proxy)}" placeholder="192.168.0.12:8888">
  <p class="hint">La guía, la radio y las actualizaciones van directas. Si la Raspberry no responde, la app usa su propia VPN (la de abajo). Al guardar, te digo por qué país sale.</p>
</div>
<div class="card">
  <div style="font-size:13px;color:var(--muted)">Estado de la configuración VPN guardada:<br>${VpnController.keyReport(p.wg).joinToString("<br>") { esc(it) }}</div>
  <label for="wg">Configuración WireGuard de Proton VPN ${if (p.wg.isNotBlank()) "<span class=ok>· guardada</span>" else ""}</label>
  <label for="wgfile" style="color:var(--fg);font-weight:600">Sube el fichero .conf (recomendado)</label>
  <input id="wgfile" type="file" accept=".conf,text/plain,application/octet-stream">
  <label for="wg">o pega su texto completo:</label>
  <textarea id="wg" name="wg" placeholder="[Interface]&#10;PrivateKey = …&#10;Address = 10.2.0.2/32&#10;DNS = 10.2.0.1&#10;&#10;[Peer]&#10;PublicKey = …&#10;AllowedIPs = 0.0.0.0/0&#10;Endpoint = …:51820"></textarea>
  <p class="hint">En account.protonvpn.com → Descargas → Configuración de WireGuard: elige plataforma «Android», un servidor de otro país y pulsa «Crear». Abre el fichero .conf y pega aquí todo el texto.</p>
</div>
<div class="card">
  <label for="radioName">Emisora</label>
  <input id="radioName" name="radioName" value="${esc(p.radioName)}">
  <label for="radio">Dirección del stream de la emisora</label>
  <input id="radio" name="radio" inputmode="url" value="${esc(p.radioUrl)}">
  <p class="hint">La misma dirección que tienes guardada en VLC.</p>
</div>
<div class="card">
  <label class=chk><input type=checkbox name=onlyTeams value=1${if (p.onlyTeams) " checked" else ""}> Mostrar solo lo que sigo (mis ligas y mis equipos), como en Live Guide</label>
  <label for="leagues">Ligas que sigues: una por línea, «Deporte: Liga»</label>
  <textarea id="leagues" name="leagues" style="min-height:220px;font-family:inherit;font-size:14px">${esc(p.leagues)}</textarea>
  <label for="team">Equipos y jugadores que sigues: uno por línea, «Deporte: Nombre» (salen los primeros, con ★)</label>
  <textarea id="team" name="team" style="min-height:220px;font-family:inherit;font-size:14px">${esc(p.team)}</textarea>
  <p class="hint">Si no pones el deporte, vale para todos («Real Madrid» incluiría también el de baloncesto).</p>
  <input type=hidden name=filtersSent value=1>
  <label>Deportes que quieres ver (ninguno marcado = todos)</label>
  <div class=grid>${checks("sport", (p.knownSports.split(',') + p.sports.split(',') + listOf("Fútbol")).map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted().map { it to it }, p.sports.split(',').map { it.trim() }.toSet())}</div>
  <p class="hint">La lista de deportes se completa con los que van apareciendo en Live Guide.</p>
  <label>Países de los canales en Live Guide (${p.countries.split(',').count { it.isNotBlank() }} de ${LiveGuide.COUNTRIES.size} marcados)</label>
  <div class=row style="display:flex;gap:8px"><button type=button onclick="document.querySelectorAll('input[name=country]').forEach(c=>c.checked=true)" style="width:auto;padding:8px 12px;background:var(--card);color:var(--fg);border:1px solid var(--line)">Marcar todos</button><button type=button onclick="document.querySelectorAll('input[name=country]').forEach(c=>c.checked=false)" style="width:auto;padding:8px 12px;background:var(--card);color:var(--fg);border:1px solid var(--line)">Quitar todos</button></div>
  <div class=grid>${checks("country", LiveGuide.COUNTRIES.map { it.first to it.second }, p.countries.split(',').map { it.trim() }.toSet())}</div>
  <label>Programación</label>
  <label style="color:var(--fg)"><input type="radio" name="guide" value="liveguide" style="width:auto" ${if (p.guide != "futbolenlatv") "checked" else ""}> Live Guide (todos los deportes)</label>
  <label style="color:var(--fg)"><input type="radio" name="guide" value="futbolenlatv" style="width:auto" ${if (p.guide == "futbolenlatv") "checked" else ""}> futbolenlatv.es (solo fútbol, más canales españoles)</label>
  <label><input type="checkbox" name="clearMaps" value="1" style="width:auto"> Olvidar los ${p.mappingCount()} canales emparejados</label>
</div>
<button type="submit">Guardar</button>
<div class="card" style="margin-top:14px">
  <label style="color:var(--fg);font-weight:500">Copia de la configuración</label>
  <p class="hint">Para llevar todo a otro dispositivo: ajustes, lo que sigues, la emisora y las asignaciones de canales.</p>
  <label class=chk><input type=checkbox id=expSecrets style="width:auto"> Incluir la lista IPTV (usuario y contraseña) y la clave de la VPN</label>
  <button type=button id=expBtn>Exportar configuración</button>
  <p class="hint">Si incluyes las claves, guarda el fichero en un sitio privado y no lo compartas.</p>
  <label for=impFile style="margin-top:8px">Importar una copia (fichero .json exportado desde otro dispositivo)</label>
  <input type=file id=impFile accept=".json,application/json">
  <button type=button id=impBtn>Importar configuración</button>
  <p id=impMsg class="hint" style="white-space:pre-line"></p>
</div>
<p id="msg"></p>
</form>
<script>
document.getElementById('wgfile').addEventListener('change', async e => {
  const f = e.target.files[0]; if (!f) return;
  document.getElementById('wg').value = await f.text();
});
document.getElementById('expBtn').addEventListener('click', () => {
  location.href = '/exportar' + (document.getElementById('expSecrets').checked ? '?secretos=1' : '');
});
document.getElementById('impBtn').addEventListener('click', async () => {
  const f = document.getElementById('impFile').files[0], m = document.getElementById('impMsg');
  if (!f) { m.textContent = 'Elige primero el fichero .json.'; return; }
  m.textContent = 'Importando…';
  try {
    const r = await fetch('/importar', {method:'POST', headers:{'Content-Type':'application/json'}, body: await f.text()});
    m.textContent = await r.text();
  } catch (e) { m.textContent = 'No se ha podido importar: ¿sigue encendida la app en la tele?'; }
});
document.getElementById('f').addEventListener('submit', async e => {
  e.preventDefault();
  const m = document.getElementById('msg'); m.textContent = 'Guardando…';
  try {
    const r = await fetch('/', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8'},
      body: new URLSearchParams(new FormData(e.target)).toString()});
    m.textContent = await r.text();
    e.target.querySelectorAll('textarea, input:not([type=checkbox])').forEach(i => { if (['m3u','wg','xsrv','xusr','xpwd'].includes(i.name)) i.value = ''; });
  } catch (err) { m.textContent = 'No se ha podido guardar: ¿sigue encendida la app en la tele?'; }
});
</script>
</main></body></html>
""".trimIndent()

    companion object {
        const val PORT = 8080
    }
}
