package es.sincro.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.nio.ByteBuffer

/**
 * Retrasa el sonido ya decodificado: guarda los últimos 150 s y en cada momento suena
 * lo que llegó hace "retardo" segundos. Cambiar el retardo es instantáneo.
 */
@OptIn(UnstableApi::class)
class DelayAudioProcessor : BaseAudioProcessor() {
    @Volatile var delayMs: Long = 0
    private var ring = ByteArray(0)
    private var writePos = 0L
    private var bytesPerSec = 0
    private var frame = 4

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        frame = inputAudioFormat.bytesPerFrame
        bytesPerSec = inputAudioFormat.sampleRate * frame
        return inputAudioFormat
    }

    override fun onFlush() {
        val size = (MAX_SEC * bytesPerSec).let { it - it % frame }
        if (ring.size != size) ring = ByteArray(size)
        writePos = 0
    }

    override fun onReset() { ring = ByteArray(0); writePos = 0 }

    /** Segundos de radio guardados (lo máximo que se puede retrasar ahora mismo). */
    fun historySeconds(): Float = if (bytesPerSec == 0) 0f else (writePos.toFloat() / bytesPerSec).coerceAtMost(MAX_SEC.toFloat())

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val size = ring.size
        if (size == 0 || n > size) { // sin memoria preparada: pasa el sonido tal cual
            replaceOutputBuffer(n).put(inputBuffer).flip(); return
        }
        var w = (writePos % size).toInt()
        var left = n
        while (left > 0) {
            val k = minOf(left, size - w)
            inputBuffer.get(ring, w, k)
            w = (w + k) % size; left -= k
        }
        writePos += n

        var d = delayMs * bytesPerSec / 1000
        d -= d % frame
        d = d.coerceIn(0, (size - n).toLong())
        val out = replaceOutputBuffer(n)
        var rp = writePos - n - d
        var remaining = n
        if (rp < 0) { // aún no hay tanto guardado: silencio
            val z = minOf(remaining.toLong(), -rp).toInt()
            for (i in 0 until z) out.put(0)
            rp += z; remaining -= z
        }
        var r = (rp % size).toInt()
        while (remaining > 0) {
            val k = minOf(remaining, size - r)
            out.put(ring, r, k)
            r = (r + k) % size; remaining -= k
        }
        out.flip()
    }

    companion object { const val MAX_SEC = 150 }
}

/** La radio de fondo: siempre grabando; se oye solo cuando estás viendo un canal. */
@OptIn(UnstableApi::class)
class RadioPlayer(private val ctx: Context) {
    private val delay = DelayAudioProcessor()
    private var player: ExoPlayer? = null
    private val ui = Handler(Looper.getMainLooper())
    var url: String? = null
        private set
    var status: String = "parada"
        private set
    private var audible = false
    private var retries = 0

    fun start(u: String, ua: String) {
        if (player != null && u == url) return
        stop()
        url = u
        build(u, ua)
    }

    private fun build(u: String, ua: String) {
        val renderers = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf<AudioProcessor>(delay)).build()
        }
        val http = DefaultHttpDataSource.Factory().setUserAgent(ua).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        val p = ExoPlayer.Builder(ctx, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        p.volume = if (audible) 1f else 0f
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                status = when (state) {
                    Player.STATE_READY -> { retries = 0; "en directo" }
                    Player.STATE_BUFFERING -> "conectando…"
                    Player.STATE_ENDED -> "la emisora ha cortado"
                    else -> status
                }
                if (state == Player.STATE_ENDED) retry(u, ua)
            }

            override fun onPlayerError(error: PlaybackException) {
                val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                status = "error: " + (if (http != null) "la emisora responde $http" else error.errorCodeName)
                retry(u, ua)
            }
        })
        p.setMediaItem(MediaItem.fromUri(u))
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    private fun retry(u: String, ua: String) {
        player?.release(); player = null
        retries++
        ui.postDelayed({ if (url == u && player == null) build(u, ua) }, if (retries < 5) 3000L else 15000L)
    }

    fun setDelay(sec: Float) { delay.delayMs = (sec * 1000).toLong() }
    fun historySeconds() = delay.historySeconds()
    fun setAudible(on: Boolean) { audible = on; player?.volume = if (on) 1f else 0f }
    val isRunning get() = player != null

    fun stop() {
        ui.removeCallbacksAndMessages(null)
        player?.release(); player = null
        url = null; status = "parada"
    }
}
