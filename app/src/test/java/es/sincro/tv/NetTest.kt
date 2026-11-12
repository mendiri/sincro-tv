package es.sincro.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NetTest {
    @Test
    fun direccionDelProxy() {
        assertEquals("192.168.0.12" to 8888, Net.parse("192.168.0.12:8888"))
        assertEquals("192.168.0.12" to 8888, Net.parse("http://192.168.0.12:8888/"))
        assertEquals("192.168.0.12" to 8888, Net.parse("192.168.0.12"))
        assertEquals("raspberry.local" to 3128, Net.parse("raspberry.local:3128"))
        assertNull(Net.parse(""))
        assertNull(Net.parse("192.168.0.12:99999"))
    }

    @Test
    fun proxyQueNoResponde() {
        // Puerto cerrado en esta máquina: tiene que decir que no responde, sin colgarse
        assertFalse(Net.reachable("127.0.0.1:1"))
        assertEquals(true, Net.testProxy("127.0.0.1:1").startsWith("no responde"))
    }
}
