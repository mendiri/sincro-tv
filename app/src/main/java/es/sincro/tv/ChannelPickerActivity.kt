package es.sincro.tv

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import es.sincro.tv.Ui.background
import es.sincro.tv.Ui.dp
import es.sincro.tv.Ui.lp
import es.sincro.tv.Ui.text
import es.sincro.tv.Ui.vbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Elegir un canal de tu lista: por categorías (como en TiviMate) o buscando.
 * Si viene de un partido, primero propone los más parecidos y recuerda la elección.
 */
class ChannelPickerActivity : Activity() {
    private val app get() = App.instance
    private val scope = MainScope()
    /** Clave del canal de la guía ("#código" o nombre) y su nombre. */
    private var site: String? = null
    private var siteName: String? = null
    private var title: String? = null
    private var openGroup: String? = null
    private var query = ""
    private lateinit var heading: TextView
    private lateinit var info: TextView
    private lateinit var list: RecyclerView
    private val adapter = RowAdapter()

    private sealed class Row {
        data class Group(val name: String, val count: Int) : Row()
        data class Chan(val ch: Channel) : Row()
        data class Action(val label: String, val run: () -> Unit) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = intent.getStringExtra("site")
        siteName = intent.getStringExtra("siteName") ?: site
        title = intent.getStringExtra("title")

        val root = vbox().apply { setPadding(dp(48), dp(24), dp(48), dp(12)) }
        heading = text(26f, bold = true)
        root.addView(heading)
        info = text(15f, Ui.MUTED).apply { setPadding(0, dp(6), 0, dp(10)) }
        root.addView(info)
        val search = EditText(this).apply {
            hint = "Buscar en todos los canales (ej. dazn laliga)"
            setTextColor(Ui.FG); setHintTextColor(Ui.MUTED)
            setSingleLine()
            setBackgroundResource(R.drawable.item_bg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(search, lp())
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChannelPickerActivity)
            adapter = this@ChannelPickerActivity.adapter
            setPadding(dp(4), dp(8), dp(4), dp(24)); clipToPadding = false
        }
        root.addView(list, lp(h = 0, weight = 1f))
        window.setBackgroundDrawable(background())
        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); refresh() }
        })

        scope.launch {
            if (app.playlist.channels.isEmpty()) {
                info.text = "Cargando tu lista de canales…"
                val r = withContext(Dispatchers.IO) { runCatching { app.playlist.ensureLoaded(app.prefs.m3u) } }
                r.exceptionOrNull()?.let { info.text = "No he podido cargar tu lista: ${it.message}"; return@launch }
            }
            refresh(focusFirst = true)
        }
    }

    private fun groups(): List<Row.Group> =
        app.playlist.channels.groupingBy { it.group.ifBlank { "Sin categoría" } }.eachCount()
            .map { Row.Group(it.key, it.value) }

    private var job: kotlinx.coroutines.Job? = null
    private var ranked: List<Channel>? = null

    private fun refresh(focusFirst: Boolean = false) {
        job?.cancel()
        job = scope.launch {
            val q = query
            if (q.isNotBlank()) kotlinx.coroutines.delay(250) // espera a que termines de escribir
            val found = if (q.isNotBlank()) withContext(Dispatchers.Default) { app.playlist.index().filter(q, 500) } else null
            if (site != null && ranked == null) {
                info.text = "Buscando los canales más parecidos…"
                ranked = withContext(Dispatchers.Default) {
                    val country = Stations.countryIndex(filesDir)[site!!] ?: Countries.ofStationName(siteName!!)
                    app.playlist.index().rank(siteName!!, countryId = country).map { it.first }
                }
            }
            show(found, focusFirst)
        }
    }

    private fun show(found: List<Channel>?, focusFirst: Boolean) {
        val all = app.playlist.channels
        val s = site
        val rows = mutableListOf<Row>()
        when {
            found != null -> {
                heading.text = "Buscar «$query»"
                info.text = if (found.isEmpty()) "Ningún canal se llama así." else "${found.size}${if (found.size == 500) "+" else ""} canales encontrados."
                found.mapTo(rows) { Row.Chan(it) }
            }
            openGroup != null -> {
                heading.text = openGroup
                val inGroup = all.filter { it.group.ifBlank { "Sin categoría" } == openGroup }
                info.text = "${inGroup.size} canales · Atrás para volver a las categorías"
                inGroup.mapTo(rows) { Row.Chan(it) }
            }
            s != null -> {
                heading.text = "¿Cuál de tus canales es «$siteName»?"
                val ranked = ranked.orEmpty()
                info.text = if (ranked.isEmpty()) "No encuentro ninguno parecido. Búscalo arriba o entra en una categoría."
                else "Estos son los más parecidos. Lo recordaré para los próximos partidos."
                ranked.mapTo(rows) { Row.Chan(it) }
                rows += Row.Action("Ver todas las categorías…") { site = null; refresh(true) }
            }
            else -> {
                heading.text = "Categorías de tu lista"
                val gs = groups()
                info.text = "${gs.size} categorías · ${all.size} canales"
                rows += gs.sortedWith(compareBy({ !isSpanishSports(it.name) }, { !isSpanish(it.name) }, { it.name }))
            }
        }
        adapter.submit(rows)
        list.scrollToPosition(0)
        if (focusFirst) list.post { list.getChildAt(0)?.requestFocus() }
    }

    private fun isSpanish(g: String) = Regex("(?i)^(es|esp|españa|spain)\\b|\\b(españa|spain)\\b").containsMatchIn(g)
    private fun isSpanishSports(g: String) = isSpanish(g) && Regex("(?i)deport|sport|futbol|fútbol|laliga|dazn|movistar|m\\+").containsMatchIn(g)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && openGroup != null && query.isBlank()) {
            openGroup = null; refresh(true); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun choose(ch: Channel) {
        site?.let { app.prefs.setMapping(it, ch) }
        startActivity(PlayerActivity.intent(this, ch, title ?: ch.name))
        if (site != null) finish()
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        private var items = listOf<Row>()
        fun submit(l: List<Row>) { items = l; notifyDataSetChanged() }

        inner class VH(val box: ViewGroup, val name: TextView, val sub: TextView) : RecyclerView.ViewHolder(box)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val c = parent.context
            val box = c.vbox().apply {
                setBackgroundResource(R.drawable.item_bg)
                isFocusable = true; isClickable = true
                setPadding(c.dp(18), c.dp(10), c.dp(18), c.dp(10))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, c.dp(3), 0, c.dp(3)) }
            }
            val name = c.text(20f, bold = true)
            val sub = c.text(14f, Ui.MUTED)
            box.addView(name); box.addView(sub)
            return VH(box, name, sub)
        }

        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, position: Int) {
            when (val r = items[position]) {
                is Row.Group -> {
                    h.name.text = "▸ ${r.name}"; h.sub.text = "${r.count} canales"
                    h.box.setOnClickListener { openGroup = r.name; refresh(true) }
                }
                is Row.Chan -> {
                    h.name.text = r.ch.name; h.sub.text = r.ch.group
                    h.box.setOnClickListener { choose(r.ch) }
                }
                is Row.Action -> {
                    h.name.text = r.label; h.sub.text = ""
                    h.box.setOnClickListener { r.run() }
                }
            }
        }
    }
}
