package es.sincro.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wireguard.android.backend.Tunnel
import es.sincro.tv.Ui.background
import es.sincro.tv.Ui.button
import es.sincro.tv.Ui.dp
import es.sincro.tv.Ui.hbox
import es.sincro.tv.Ui.lp
import es.sincro.tv.Ui.margins
import es.sincro.tv.Ui.text
import es.sincro.tv.Ui.vbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val app get() = App.instance
    private val scope = MainScope()
    private lateinit var status: TextView
    private lateinit var help: TextView
    private lateinit var list: RecyclerView
    private val adapter = MatchAdapter()
    private var vpnError: String? = null
    private var loading = false
    private var guideNote: String? = null
    private lateinit var filterBtn: android.widget.Button
    /** La última guía descargada (sin filtrar), para cambiar de vista sin volver a descargarla. */
    private var lastAll: List<Match> = emptyList()
    private var lastMatches: Result<List<Match>>? = null
    private var lastPlaylist: Result<Any>? = null

    private fun filterLabel() = if (app.prefs.onlyTeams) "★ Lo que sigo" else "☰ Todo"

    /** Botón de la cabecera: solo lo que sigues ↔ todos los partidos. */
    private fun toggleFilter() {
        app.prefs.onlyTeams = !app.prefs.onlyTeams
        filterBtn.text = filterLabel()
        showMatches(lastAll)
        lastMatches?.let { status.text = statusLine(it, lastPlaylist) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vbox().apply { setPadding(dp(48), dp(24), dp(48), dp(12)) }

        val header = hbox()
        header.addView(text(34f, light = true).apply { text = "Sincro TV  ${packageManager.getPackageInfo(packageName, 0).versionName}" }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        filterBtn = button(filterLabel()) { toggleFilter() }
        header.addView(filterBtn.margins(r = dp(10)))
        header.addView(button("Ver un canal") { openPicker(null, null, null) }.margins(r = dp(10)))
        header.addView(button("Actualizar") { loadAll(force = true) }.margins(r = dp(10)))
        updateBtn = button("⬆ Actualizar") { pendingUpdate?.let { askUpdate(it) } }.apply { visibility = View.GONE }
        header.addView(updateBtn.margins(r = dp(10)))
        header.addView(button("Ajustes") { showSettingsHelp() })
        root.addView(header, lp())

        status = text(15f, Ui.MUTED).apply { setPadding(0, dp(6), 0, dp(10)) }
        root.addView(status, lp())

        help = text(20f).apply { visibility = View.GONE; setLineSpacing(0f, 1.25f) }
        root.addView(help, lp())

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(24))
        }
        root.addView(list, lp(h = 0, weight = 1f))
        window.setBackgroundDrawable(background())
        setContentView(root)

        app.onConfigChanged = { loadAll(force = true) }
        startServer()
        prepareVpnThenLoad()
        checkUpdate(silent = true)
    }

    private fun startServer() {
        if (app.server != null) return
        try { app.server = SetupServer().also { it.start() } } catch (e: Exception) { app.server = null }
    }

    private fun settingsUrl() = "http://${lanAddress() ?: "IP-DEL-FIRE-TV"}:${SetupServer.PORT}"

    private fun prepareVpnThenLoad() {
        if (app.vpn.isConfigured()) {
            val consent = VpnService.prepare(this)
            if (consent != null) {
                startActivityForResult(consent, REQ_VPN)
                return
            }
        }
        loadAll()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            vpnError = if (resultCode == RESULT_OK) null else "no has dado permiso para la VPN"
            loadAll()
        }
    }

    private fun loadAll(force: Boolean = false) {
        if (loading) return
        val p = app.prefs
        if (p.m3u.isBlank()) {
            help.visibility = View.VISIBLE
            help.text = "Primero hay que configurar la app.\n\n" +
                "En el móvil, conectado a la misma wifi que el Fire TV, abre el navegador y entra en:\n\n" +
                "    ${settingsUrl()}\n\n" +
                "Ahí pegas el enlace de tu lista de canales y la configuración WireGuard de Proton VPN."
            status.text = ""
            adapter.submit(emptyList())
            return
        }
        help.visibility = View.GONE
        if (force && app.vpn.isConfigured() && VpnService.prepare(this) != null) { prepareVpnThenLoad(); return }
        loading = true
        // La última guía guardada sale al momento; la nueva llega en unos segundos
        if (adapter.itemCount == 0) GuideCache.load(filesDir)?.let { cached ->
            showMatches(Schedule.upcoming(cached, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Madrid"))))
        }
        scope.launch {
            try {
                if (p.useProxy || app.vpn.isConfigured()) {
                    status.text = if (p.useProxy) "Conectando con la Raspberry…" else "Conectando la VPN…"
                    vpnError = withContext(Dispatchers.IO) { Net.prepare(app) }
                }
                app.startRadio()
                status.text = "Actualizando la guía…"
                // Guía y lista de canales a la vez
                val playlistJob = async(Dispatchers.IO) { runCatching { app.playlist.ensureLoaded(p.m3u, force); app.playlist.index() } }
                val matches = withContext(Dispatchers.IO) {
                    // La guía elegida; si falla, la otra
                    val first: () -> List<Match> = if (p.guide == "futbolenlatv") Schedule::fetch else LiveGuide::fetch
                    val second: () -> List<Match> = if (p.guide == "futbolenlatv") LiveGuide::fetch else Schedule::fetch
                    runCatching(first).recoverCatching { e1 -> second().also { guideNote = "guía de reserva (${e1.message})" } }
                }
                matches.getOrNull()?.let { ms ->
                    showMatches(ms)
                    withContext(Dispatchers.IO) { GuideCache.save(filesDir, ms) }
                }
                lastMatches = matches
                status.text = statusLine(matches, null) + "   ·   Cargando tus canales…"
                val playlist = playlistJob.await()
                lastPlaylist = playlist
                status.text = statusLine(matches, playlist)
            } finally {
                loading = false
            }
        }
    }

    private fun statusLine(matches: Result<List<Match>>, playlist: Result<Any>?): String {
        val p = app.prefs
        val parts = mutableListOf<String>()
        parts += when {
            Net.viaRaspberry -> "Red: Raspberry (${p.proxy})"
            Net.raspberryProblem != null && app.vpn.state == Tunnel.State.UP -> "${Net.raspberryProblem}: uso la VPN del Fire TV"
            Net.raspberryProblem != null -> "${Net.raspberryProblem}"
            !app.vpn.isConfigured() -> "VPN sin configurar"
            vpnError != null -> "VPN: error ($vpnError)"
            app.vpn.state == Tunnel.State.UP -> "VPN conectada"
            else -> "VPN desconectada"
        }
        playlist?.let {
            parts += it.fold(
                { "${NumberFormat.getInstance(Locale("es", "ES")).format(app.playlist.channels.size)} canales" },
                { e -> "Lista de canales: ${e.message}" },
            )
        }
        parts += (if (p.guide == "futbolenlatv") "Guía: futbolenlatv.es" else "Guía: Live Guide") +
            (if (p.onlyTeams) " (solo lo que sigues)" else "")
        guideNote?.let { parts += it }
        matches.exceptionOrNull()?.let { parts += "Guía: ${it.message}" }
        parts += "Ajustes desde el móvil: ${settingsUrl()}"
        return parts.joinToString("   ·   ")
    }

    /** Aplica tus filtros (deportes, ligas y equipos que sigues) y enseña la lista. */
    private fun showMatches(all: List<Match>) {
        lastAll = all
        if (::filterBtn.isInitialized) filterBtn.text = filterLabel()
        val p = app.prefs
        val teams = Follow.parse(p.team)
        val leagues = Follow.parse(p.leagues)
        val sportsSel = p.sports.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        var ms = all.filter { sportsSel.isEmpty() || it.sport in sportsSel }
        if (p.onlyTeams && (teams.isNotEmpty() || leagues.isNotEmpty())) {
            ms = ms.filter { Follow.followsTeam(teams, it) || Follow.followsLeague(leagues, it) }
        }
        // Tus equipos, primero
        val mine = ms.filter { Follow.followsTeam(teams, it) }
        val sorted = mine + (ms - mine.toSet())
        val hadFocus = list.focusedChild != null || currentFocus === filterBtn
        adapter.submit(sorted, mine.toSet())
        list.scrollToPosition(0)
        if (sorted.isNotEmpty() && !hadFocus) list.post { list.getChildAt(0)?.requestFocus() }
        help.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        if (sorted.isEmpty()) help.text = if (p.onlyTeams) "Hoy y mañana no hay nada de lo que sigues. Pulsa «★ Lo que sigo» arriba para ver todos los partidos, o «Ver un canal»."
            else "No hay partidos para hoy ni mañana. Pulsa «Ver un canal» para elegir uno de tu lista."
    }

    private fun showSettingsHelp() {
        AlertDialog.Builder(this, Ui.DIALOG)
            .setTitle("Ajustes")
            .setMessage(
                "Abre esta dirección en el navegador del móvil (misma wifi):\n\n${settingsUrl()}\n\n" +
                    "Retardo actual de la radio: ${fmtSec(app.prefs.delay)} s\n" +
                    "Canales emparejados: ${app.prefs.mappingCount()}\n" +
                    "Versión instalada: ${Updater.installedName(this)}"
            )
            .setPositiveButton("Vale", null)
            .setNeutralButton("Buscar actualización") { _, _ -> checkUpdate(silent = false) }
            .show()
    }

    // ---------- Actualizaciones desde GitHub ----------

    private lateinit var updateBtn: android.widget.Button
    private var pendingUpdate: Updater.Release? = null

    /** Mira si hay una versión nueva publicada. [silent]: al abrir la app no dice nada si no la hay. */
    private fun checkUpdate(silent: Boolean) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updater.latest() } }
            val rel = r.getOrNull()
            if (rel != null && rel.versionCode > Updater.installedCode(this@MainActivity)) {
                pendingUpdate = rel
                updateBtn.text = "⬆ Actualizar a ${rel.versionName}"
                updateBtn.visibility = View.VISIBLE
                if (!silent || app.prefs.lastOfferedUpdate != rel.versionCode) {
                    app.prefs.lastOfferedUpdate = rel.versionCode
                    askUpdate(rel)
                }
            } else if (!silent) {
                AlertDialog.Builder(this@MainActivity, Ui.DIALOG).setTitle("Actualizaciones")
                    .setMessage(r.exceptionOrNull()?.let { "No he podido comprobarlo: ${it.message}" } ?: "Ya tienes la última versión (${Updater.installedName(this@MainActivity)}).")
                    .setPositiveButton("Vale", null).show()
            }
        }
    }

    private fun askUpdate(rel: Updater.Release) {
        AlertDialog.Builder(this, Ui.DIALOG)
            .setTitle("Nueva versión ${rel.versionName}")
            .setMessage((rel.notes.ifBlank { "Hay una versión nueva de Sincro TV." }) + "\n\n¿La instalo ahora? Tus ajustes y canales se conservan.")
            .setPositiveButton("Actualizar") { _, _ -> startUpdate(rel) }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    private fun startUpdate(rel: Updater.Release) {
        if (!Updater.canInstall(this)) {
            AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Falta un permiso")
                .setMessage("Para instalar la actualización, Sincro TV necesita permiso para instalar apps.\n\n" +
                    "Actívalo en la pantalla que se abre ahora (o en Ajustes → Mi Fire TV → Opciones para desarrolladores → " +
                    "Instalar apps desconocidas → Sincro TV) y vuelve a pulsar «Actualizar».")
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    if (!Updater.openInstallPermission(this)) {
                        AlertDialog.Builder(this, Ui.DIALOG).setMessage("Este Fire TV no tiene esa pantalla. Actívalo a mano en Ajustes → Mi Fire TV → Opciones para desarrolladores → Instalar apps desconocidas.")
                            .setPositiveButton("Vale", null).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val dlg = AlertDialog.Builder(this, Ui.DIALOG).setTitle("Descargando ${rel.versionName}…").setMessage("0 %").setCancelable(false).show()
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching { Updater.download(this@MainActivity, rel) { pc -> runOnUiThread { dlg.setMessage("$pc %") } } }
            }
            dlg.dismiss()
            f.onSuccess { Updater.install(this@MainActivity, it) }
                .onFailure {
                    AlertDialog.Builder(this@MainActivity, Ui.DIALOG).setTitle("No se ha podido actualizar").setMessage(it.message)
                        .setPositiveButton("Vale", null).show()
                }
        }
    }

    /** [site] = clave del canal de la guía ("#código" o nombre); [siteName] = su nombre para enseñarlo. */
    private class Cand(val site: String, val siteName: String, val ch: Channel, val score: Double)

    /** Mejores canales de tu lista para un partido: primero los ya emparejados, luego los más parecidos. */
    private fun candidatesFor(m: Match): List<Cand> {
        val idx = app.playlist.index()
        val countries = Stations.countryIndex(filesDir)
        val out = ArrayList<Cand>()
        // Uno por cada canal de la guía: el emparejado o, si no, el más parecido de tu lista
        for ((i, name) in m.channels.withIndex()) {
            val ref = m.refs[i]
            // País del canal: por su código (exacto) o por el nombre
            val country = countries[ref] ?: Countries.ofStationName(name)
            // Todos los que hayas asignado en la página «Canales» (o elegido antes)
            val mapped = app.prefs.mappings(ref)
            if (mapped.isNotEmpty()) { mapped.forEach { out += Cand(ref, name, it, 10.0) }; continue }
            // Sin asignar: todas sus variantes claras del país correcto o, si no hay, el más parecido
            val clear = AutoAssign.clearMatches(idx, name, country)
            if (clear.isNotEmpty()) { clear.forEach { out += Cand(ref, name, it, 9.0) }; continue }
            idx.rank(name, 1, countryId = country).firstOrNull()?.let { (ch, sc) -> out += Cand(ref, name, ch, sc) }
        }
        // Primero lo asignado (en el orden de la guía y el tuyo), luego lo encontrado automáticamente
        return out.distinctBy { it.ch.url }.sortedByDescending { if (it.score >= 10.0) 10.0 else it.score }
    }

    private var searching = false

    private fun onMatch(m: Match, forceChoose: Boolean) {
        if (forceChoose) { chooseManually(m, true); return }
        if (searching) return
        searching = true
        val toast = android.widget.Toast.makeText(this, "Buscando el canal…", android.widget.Toast.LENGTH_SHORT).also { it.show() }
        scope.launch {
            try {
                if (app.playlist.channels.isEmpty()) withContext(Dispatchers.IO) { runCatching { app.playlist.ensureLoaded(app.prefs.m3u) } }
                val cands = withContext(Dispatchers.Default) { candidatesFor(m) }
                toast.cancel()
                val good = cands.filter { it.score >= 0.6 }
                if (good.isNotEmpty() && good[0].score >= 0.8) {
                    // Canal claro: se pone directamente; si no funciona, se prueban los siguientes
                    startActivity(PlayerActivity.intent(this@MainActivity, good.map { it.ch }, good.map { it.site }, good.map { it.siteName }, m.title, auto = good[0].score < 10.0))
                } else {
                    chooseManually(m, false, cands.firstOrNull()?.let { it.site to it.siteName })
                }
            } finally { searching = false }
        }
    }

    private fun chooseManually(m: Match, forceChoose: Boolean, best: Pair<String, String>? = null) {
        val refs = m.refs
        when {
            best != null && !forceChoose -> openPicker(best.first, best.second, m.title)
            m.channels.isEmpty() -> openPicker(null, null, m.title)
            m.channels.size == 1 && !forceChoose -> openPicker(refs[0], m.channels[0], m.title)
            else -> {
                val labels = m.channels.mapIndexed { i, s -> if (app.prefs.mapping(refs[i]) != null) "✓  $s" else s }.toTypedArray()
                AlertDialog.Builder(this, Ui.DIALOG)
                    .setTitle(if (forceChoose) "¿Qué canal quieres emparejar?" else "¿Qué canal?")
                    .setItems(labels) { _, i ->
                        val mp = app.prefs.mapping(refs[i])
                        if (mp != null && !forceChoose) play(mp, m.title) else openPicker(refs[i], m.channels[i], m.title)
                    }
                    .show()
            }
        }
    }

    /** [site] = clave del canal de la guía; [siteName] = su nombre (para buscar y para enseñarlo). */
    private fun openPicker(site: String?, siteName: String?, title: String?) {
        startActivity(Intent(this, ChannelPickerActivity::class.java).putExtra("site", site).putExtra("siteName", siteName).putExtra("title", title))
    }

    private fun play(ch: Channel, title: String) {
        startActivity(PlayerActivity.intent(this, ch, title))
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        if (isFinishing) {
            app.onConfigChanged = null
            app.radio.stop()
            app.server?.stop(); app.server = null
            thread { app.vpn.down() }
        }
    }

    private inner class MatchAdapter : RecyclerView.Adapter<MatchAdapter.VH>() {
        private var items = listOf<Match>()
        private var mine = setOf<Match>()

        fun submit(list: List<Match>, mine: Set<Match> = emptySet()) {
            items = list; this.mine = mine
            notifyDataSetChanged()
        }

        inner class VH(val row: LinearLayout, val time: TextView, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = ctx.hbox().apply {
                setBackgroundResource(R.drawable.item_bg)
                isFocusable = true
                isClickable = true
                setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, ctx.dp(4), 0, ctx.dp(4)) }
            }
            val time = ctx.text(22f, Ui.ACCENT, bold = true, mono = true)
            row.addView(time, lp(ctx.dp(170)))
            val col = ctx.vbox()
            val title = ctx.text(22f, bold = true)
            val sub = ctx.text(15f, Ui.MUTED).apply { maxLines = 2 }
            col.addView(title); col.addView(sub)
            row.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return VH(row, time, title, sub)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val m = items[position]
            h.time.text = "${dayLabel(m.date)} ${m.time}"
            val mine = m in this.mine
            h.title.text = if (mine) Ui.starred(m.title) else m.title
            val refs = m.refs
            val chans = m.channels.withIndex().joinToString(", ") { (i, s) -> if (app.prefs.mapping(refs[i]) != null) "✓ $s" else s }
            val shown = if (m.channels.size > 6) chans.split(", ").take(6).joinToString(", ") + " y ${m.channels.size - 6} más" else chans
            val comp = if (m.isFootball) m.competition else "${m.sport} · ${m.competition}"
            h.sub.text = listOf(comp, shown.ifBlank { "Sin canal en la guía" }).filter { it.isNotBlank() }.joinToString("   ·   ")
            h.row.setOnClickListener { onMatch(m, false) }
            h.row.setOnLongClickListener { onMatch(m, true); true }
        }
    }

    private val dayFmt = java.text.SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Europe/Madrid") }
    private fun dayLabel(date: String): String {
        val now = java.util.Calendar.getInstance(dayFmt.timeZone)
        if (dayFmt.format(now.time) == date) return "Hoy"
        now.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return if (dayFmt.format(now.time) == date) "Mañana" else date.take(5)
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged() // refresca las marcas ✓ al volver del selector
    }

    companion object {
        private const val REQ_VPN = 1
    }
}
