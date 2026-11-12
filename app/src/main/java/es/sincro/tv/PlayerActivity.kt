package es.sincro.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import es.sincro.tv.Ui.dp
import es.sincro.tv.Ui.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Pantalla completa: el canal con su imagen y la radio retrasada sonando por la tele.
 * Mando: ←/→ cambiar de canal · ↑/↓ ±0,5 s · ⏪/⏩ ±5 s · OK dos veces = sincronizar · ▶❚❚ o menú = sonido de la tele / radio.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private val app get() = App.instance
    private val prefs get() = app.prefs
    private val scope = MainScope()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var playerView: PlayerView
    private lateinit var overlay: TextView
    private lateinit var center: TextView
    private var video: ExoPlayer? = null

    private lateinit var title: String
    private var chans = listOf<Channel>()
    private var sites = listOf<String>()      // claves ("#código" o nombre)
    private var siteNames = listOf<String>()  // nombres para enseñar
    private var chIdx = 0
    private var autoPicked = false
    private var candidates = listOf<String>()
    private var current = 0
    private var attempts = 0
    private val errors = mutableListOf<String>()
    private var stallStart = 0L
    private var wasReady = false
    private var syncStart = 0L
    private var released = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        title = intent.getStringExtra("title").orEmpty()
        val urls = intent.getStringArrayListExtra("urls").orEmpty()
        val names = intent.getStringArrayListExtra("names").orEmpty()
        val groups = intent.getStringArrayListExtra("groups").orEmpty()
        chans = urls.indices.map { Channel(names.getOrElse(it) { "" }, groups.getOrElse(it) { "" }, urls[it]) }
        sites = intent.getStringArrayListExtra("sites").orEmpty()
        siteNames = intent.getStringArrayListExtra("siteNames") ?: sites
        autoPicked = intent.getBooleanExtra("auto", false)
        val chName = chans.firstOrNull()?.name.orEmpty()
        candidates = variantsOf(chans.firstOrNull()?.url.orEmpty())

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(0xFF000000.toInt())
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        center = text(22f).apply { gravity = Gravity.CENTER; text = "Conectando…"; setPadding(dp(48), 0, dp(48), 0) }
        root.addView(center, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        overlay = text(18f).apply {
            setBackgroundResource(R.drawable.overlay_bg)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setLineSpacing(0f, 1.2f)
        }
        root.addView(overlay, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(48), 0, dp(48), dp(32))
        })
        setContentView(root)

        showOverlay("$title\n$chName" + if (autoPicked) "\n(elegido automáticamente; ←/→ para ver los otros ${chans.size - 1} canales del partido)" else "", long = true)
        scope.launch {
            // Por la Raspberry (si está activada y responde) o por la VPN del Fire TV
            if (!Net.viaRaspberry && (app.prefs.useProxy || app.vpn.isConfigured())) {
                center.text = if (app.prefs.useProxy) "Conectando con la Raspberry…" else "Conectando la VPN…"
                withContext(Dispatchers.IO) { runCatching { Net.prepare(app) } }
            }
            if (released) return@launch
            startVideo()
            if (prefs.radioOn) {
                app.startRadio()
                app.radio.setAudible(true)
            }
        }
    }

    // ---------- Imagen ----------

    /** Variantes de la dirección del canal que se prueban si una falla. */
    private fun variantsOf(url: String): List<String> {
        val out = linkedSetOf(url)
        val m = Regex("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(?:\\.(ts|m3u8))?$").find(url)
        if (m != null) {
            val (host, u, p, id) = m.destructured
            out += "$host/live/$u/$p/$id.m3u8"
            out += "$host/live/$u/$p/$id.ts"
            out += "$host/$u/$p/$id"
        }
        for (v in out.toList()) {
            out += if (v.startsWith("https://")) "http://" + v.removePrefix("https://") else "https://" + v.removePrefix("http://")
        }
        return out.toList()
    }

    private val loadTimeout = Runnable { fail("el canal no responde") }

    private fun startVideo() {
        // Los canales salen por la Raspberry o por la VPN del Fire TV (Net decide)
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(Net.iptvClient())
            .setUserAgent(app.playlist.userAgent)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        video = player
        playerView.player = player
        player.volume = if (prefs.radioOn) 0f else 1f
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        ui.removeCallbacks(loadTimeout)
                        center.visibility = View.GONE
                        attempts = 0
                        // Funciona: se recuerda para la próxima vez
                        sites.getOrNull(chIdx)?.takeIf { it.isNotBlank() }?.let { prefs.rememberIfEmpty(it, chans[chIdx]) }
                        if (stallStart > 0) {
                            val stalled = (SystemClock.elapsedRealtime() - stallStart) / 1000f
                            stallStart = 0
                            // Si la imagen se para, el directo de la tele se atrasa: atrasamos la radio lo mismo
                            if (stalled >= 0.5f && prefs.autoAdjust && prefs.radioOn) {
                                changeDelay(stalled, "La imagen se ha parado ${fmtSec(stalled)} s: retraso la radio lo mismo")
                            }
                        }
                        wasReady = true
                    }
                    Player.STATE_BUFFERING -> if (wasReady && stallStart == 0L) stallStart = SystemClock.elapsedRealtime()
                    Player.STATE_ENDED -> fail("el canal ha dejado de emitir")
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                fail(if (code != null) "el servidor responde $code" else error.errorCodeName.removePrefix("ERROR_CODE_").lowercase())
            }
        })
        load()
    }

    private fun load() {
        val p = video ?: return
        val url = candidates[current]
        center.visibility = View.VISIBLE
        center.text = if (attempts == 0 && current == 0) "Cargando el canal…" else "Probando otra forma de abrir el canal (${current + 1}/${candidates.size})…"
        val b = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8", true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaItem(b.build())
        p.prepare()
        p.playWhenReady = true
        ui.removeCallbacks(loadTimeout)
        ui.postDelayed(loadTimeout, 25_000)
    }

    private fun fail(reason: String) {
        if (released) return
        ui.removeCallbacks(loadTimeout)
        stallStart = 0
        val p = video ?: return
        p.stop()
        if (wasReady) {
            // Ya se estaba viendo: se ha cortado. Reintenta la misma dirección.
            wasReady = false
            center.visibility = View.VISIBLE
            center.text = "Se ha cortado la señal ($reason). Reintentando…"
            ui.postDelayed({ if (!released) load() }, 2000)
            return
        }
        errors += "${shortUrl(candidates[current])}: $reason"
        attempts++
        current = (current + 1) % candidates.size
        if (attempts >= candidates.size && chIdx + 1 < chans.size) {
            // Este canal no va: el siguiente candidato
            val bad = chans[chIdx].name
            chIdx++
            candidates = variantsOf(chans[chIdx].url)
            current = 0; attempts = 0
            showOverlay("«$bad» no funciona. Probando «${chans[chIdx].name}»", long = true)
            ui.postDelayed({ if (!released) load() }, 800)
            return
        }
        if (attempts >= candidates.size) {
            center.visibility = View.VISIBLE
            center.text = "No se puede ver este canal.\n\n" + errors.takeLast(4).joinToString("\n") +
                "\n\n" + (if (chans.size > 1) "Pulsa ←/→ para probar otro canal de este partido, o Atrás. " else "Pulsa Atrás y prueba otro. ") + "Si ningún canal funciona, prueba a ver este mismo en TiviMate con la VPN puesta."
            return
        }
        ui.postDelayed({ if (!released) load() }, 800)
    }

    private fun shortUrl(u: String) = u.replace(Regex("/live/[^/]+/[^/]+/"), "/live/…/").replace(Regex("//([^/]+)/[^/]+/[^/]+/(\\d+)$"), "//$1/…/$2")

    // ---------- Radio ----------

    private fun changeDelay(diff: Float, note: String? = null) {
        val v = ((prefs.delay + diff) * 10).roundToInt() / 10f
        prefs.delay = v.coerceIn(0f, DelayAudioProcessor.MAX_SEC - 5f)
        app.radio.setDelay(prefs.delay)
        showOverlay(note)
    }

    private fun toggleAudio() {
        prefs.radioOn = !prefs.radioOn
        video?.volume = if (prefs.radioOn) 0f else 1f
        if (prefs.radioOn) { app.startRadio(); app.radio.setAudible(true) } else app.radio.setAudible(false)
        showOverlay(if (prefs.radioOn) "Suena la radio" else "Suena la tele")
    }

    // ---------- Mando ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (event.repeatCount == 0) switchChannel(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_UP -> if (event.repeatCount == 0) switchChannel(1)
            KeyEvent.KEYCODE_DPAD_UP -> if (prefs.radioOn) changeDelay(0.5f) else showOverlay()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (prefs.radioOn) changeDelay(-0.5f) else showOverlay()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (prefs.radioOn) changeDelay(5f) else showOverlay()
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (prefs.radioOn) changeDelay(-5f) else showOverlay()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) syncTap()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MENU -> if (event.repeatCount == 0) toggleAudio()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun syncTap() {
        if (!prefs.radioOn) { showOverlay(); return }
        val now = SystemClock.elapsedRealtime()
        if (syncStart == 0L || now - syncStart > 90_000) {
            syncStart = now
            showOverlay("Sincronizando: pulsa OK otra vez cuando lo VEAS en la tele", long = true)
        } else {
            val d = (now - syncStart) / 1000f
            syncStart = 0
            changeDelay(d, "Radio retrasada ${fmtSec(d)} s más")
        }
    }

    /** Pasa al canal anterior o siguiente de los que emiten este partido. */
    private fun switchChannel(dir: Int) {
        if (chans.size < 2) { showOverlay("Este partido solo tiene un canal en tu lista"); return }
        ui.removeCallbacks(loadTimeout)
        chIdx = (chIdx + dir + chans.size) % chans.size
        candidates = variantsOf(chans[chIdx].url)
        current = 0; attempts = 0; wasReady = false; stallStart = 0
        errors.clear()
        video?.stop()
        load()
        showOverlay()
    }

    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    private fun showOverlay(note: String? = null, long: Boolean = false) {
        val lines = mutableListOf<String>()
        if (note != null) lines += note
        if (chans.isNotEmpty()) {
            val site = siteNames.getOrNull(chIdx)?.takeIf { it.isNotBlank() }
            lines += "📺 Canal ${chIdx + 1}/${chans.size}: ${chans[chIdx].name}" + (site?.let { " ($it)" } ?: "") +
                (if (chans.size > 1) "   ←/→ cambiar" else "")
        }
        if (prefs.radioOn) {
            lines += "📻 ${prefs.radioName}: ${app.radio.status} · retardo ${fmtSec(prefs.delay)} s"
            val saved = app.radio.historySeconds()
            if (app.radio.status == "en directo" && saved < prefs.delay) {
                lines += "Empezará a sonar en ${fmtSec(prefs.delay - saved)} s (aún no hay tanta radio guardada)"
            }
            lines += "↑/↓ ±0,5 s   ⏪/⏩ ±5 s   OK, OK: sincronizar   ▶❚❚: oír la tele"
        } else {
            lines += "📺 Sonido de la tele   ·   ▶❚❚: oír la radio"
        }
        overlay.text = lines.joinToString("\n")
        overlay.visibility = View.VISIBLE
        ui.removeCallbacks(hideOverlay)
        ui.postDelayed(hideOverlay, if (long) 8000 else 4000)
    }

    // ---------- Ciclo de vida ----------

    private fun releaseAll() {
        if (released) return
        released = true
        ui.removeCallbacksAndMessages(null)
        scope.cancel()
        playerView.player = null
        video?.release(); video = null
        app.radio.setAudible(false) // sigue grabando en silencio para el próximo canal
    }

    override fun onStop() {
        super.onStop()
        releaseAll()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseAll()
    }

    companion object {
        fun intent(ctx: Context, ch: Channel, title: String) = intent(ctx, listOf(ch), emptyList(), emptyList(), title, false)

        /** Varios canales candidatos: si uno no funciona, se prueba el siguiente. */
        fun intent(ctx: Context, chans: List<Channel>, sites: List<String>, siteNames: List<String>, title: String, auto: Boolean) =
            Intent(ctx, PlayerActivity::class.java)
                .putStringArrayListExtra("urls", ArrayList(chans.map { it.url }))
                .putStringArrayListExtra("names", ArrayList(chans.map { it.name }))
                .putStringArrayListExtra("groups", ArrayList(chans.map { it.group }))
                .putStringArrayListExtra("sites", ArrayList(sites))
                .putStringArrayListExtra("siteNames", ArrayList(siteNames))
                .putExtra("title", title).putExtra("auto", auto)
    }
}
