package es.sincro.tv

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DelayTest {
    // 1000 muestras/s, mono, 16 bits -> 2000 bytes por segundo
    private fun proc(delayMs: Long) = DelayAudioProcessor().apply {
        configure(AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT))
        flush()
        this.delayMs = delayMs
    }

    /** Mete [n] muestras numeradas desde [from] y devuelve lo que sale. */
    private fun feed(p: DelayAudioProcessor, from: Int, n: Int): ShortArray {
        val inBuf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until n) inBuf.putShort((from + i).toShort())
        inBuf.flip()
        p.queueInput(inBuf)
        val out = p.output.order(ByteOrder.nativeOrder())
        return ShortArray(out.remaining() / 2) { out.short }
    }

    @Test
    fun retrasaLoJusto() {
        val p = proc(1000) // 1 s = 1000 muestras
        val first = feed(p, 1, 500)
        assertEquals(500, first.size)
        assertEquals(0, first.max().toInt())          // silencio: aún no hay 1 s guardado
        feed(p, 501, 500)
        val third = feed(p, 1001, 500)
        assertEquals(1, third[0].toInt())             // suena lo de hace 1 s
        assertEquals(500, third[499].toInt())
    }

    @Test
    fun cambiarElRetardoAlVuelo() {
        val p = proc(1000)
        for (k in 0 until 6) feed(p, 1 + k * 500, 500) // 3 s grabados (muestras 1..3000)
        p.delayMs = 500
        val a = feed(p, 3001, 500)
        assertEquals(3001 - 500, a[0].toInt())
        p.delayMs = 2000
        val b = feed(p, 3501, 500)
        assertEquals(3501 - 2000, b[0].toInt())
    }
}
