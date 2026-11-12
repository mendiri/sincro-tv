package es.sincro.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ClockTest {
    private fun httpDate(ms: Long) = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(java.util.Date(ms))

    @Test
    fun corrigeUnRelojQueVaUnDiaPorDetras() {
        val real = System.currentTimeMillis() + 86_400_000L // el servidor dice "mañana" respecto al aparato
        Clock.fromHttpDate(httpDate(real))
        assertTrue(Clock.wrong)
        assertTrue(kotlin.math.abs(Clock.now() - real) < 5_000)
        assertEquals("va 1 día por detrás", Clock.describe())
        // Hoy/Mañana: con el reloj corregido, la fecha del servidor es "hoy"
        val f = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("Europe/Madrid") }
        assertEquals(f.format(java.util.Date(real)), f.format(Clock.madrid().time))
        // Un reloj bien puesto no se toca
        Clock.fromHttpDate(httpDate(System.currentTimeMillis() + 3_000))
        assertFalse(Clock.wrong)
        assertEquals(0L, Clock.offsetMs)
    }
}
