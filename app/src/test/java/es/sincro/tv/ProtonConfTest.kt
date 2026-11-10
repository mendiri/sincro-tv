package es.sincro.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonConfTest {
    @Test
    fun aceptaElFicheroDeProton() {
        // Misma estructura que el fichero de Proton, con claves de prueba
        val conf = """
            [Interface]
            # Key for SincroTV
            # Bouncing = 5
            # NetShield = 2
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 10.2.0.2/32, 2a07:b944::2:2/128
            DNS = 10.2.0.1, 2a07:b944::2:1

            [Peer]
            # FR#465
            PublicKey = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 178.249.212.171:51820

            # Uncomment the following line (delete the # symbol) to connect to Proton VPN using IPv6.
            # Endpoint = [2a02:6ea0:dc0e:6210::10]:51820
            PersistentKeepalive = 25
        """.trimIndent()
        val cfg = VpnController.buildConfig(conf)
        assertEquals(2, cfg.`interface`.addresses.size)
        assertEquals(2, cfg.`interface`.dnsServers.size)
        val peer = cfg.peers.single()
        assertEquals("178.249.212.171:51820", peer.endpoint.get().toString())
        assertTrue(peer.allowedIps.any { it.toString() == "10.2.0.1/32" })
        assertTrue(peer.allowedIps.none { it.toString() == "0.0.0.0/0" })
        println(peer.allowedIps.filter { it.address is java.net.Inet6Address }); assertTrue(peer.allowedIps.any { it.address is java.net.Inet6Address && it.mask == 0 })
    }

    @Test
    fun aguantaCaracteresInvisiblesAlCopiar() {
        val nb = "\u00A0"; val zw = "\u200B"
        val conf = "[Interface]\nPrivateKey = AAAAAAAAAAAAAAAAAAAAAAAA" + zw + "AAAAAAAAAAAAAAAAAAA=" + nb + "\n" +
            "Address = 10.2.0.2/32\nDNS = 10.2.0.1\n\n[Peer]\nPublicKey =" + nb + "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=\n" +
            "AllowedIPs = 0.0.0.0/0\nEndpoint = 178.249.212.171:51820\n"
        val cfg = VpnController.buildConfig(conf)
        assertEquals(1, cfg.peers.size)
    }

    @Test
    fun explicaQueClaveFalla() {
        val ok = "[Interface]\nPrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\nAddress = 10.2.0.2/32\n[Peer]\nPublicKey = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=\nAllowedIPs = 0.0.0.0/0\nEndpoint = 1.2.3.4:51820\n"
        val r = VpnController.keyReport(ok)
        println(r); assertTrue(r.last().contains("se lee bien"))
        val bad = VpnController.keyReport(ok.replace("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "AAAAAAAAAAAAAAAAAAAAAAAAA…"))
        println(bad); assertTrue(bad[0].contains("NO válida") && bad[0].contains("…"))
    }
}
