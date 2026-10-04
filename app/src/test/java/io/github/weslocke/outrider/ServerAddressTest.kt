package io.github.weslocke.outrider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    private fun ok(input: String) = (ServerAddress.parse(input) as ServerAddress.Parsed.Ok).address
    private fun invalid(input: String) = ServerAddress.parse(input) is ServerAddress.Parsed.Invalid

    @Test fun plainIpGetsTheDefaultPort() = assertEquals(ServerAddress("192.168.1.208", 8025), ok(" 192.168.1.208 "))
    @Test fun explicitPort() = assertEquals(ServerAddress("192.168.1.208", 8100), ok("192.168.1.208:8100"))
    @Test fun hostname() = assertEquals(ServerAddress("gamepc.local", 8025), ok("gamepc.local"))
    @Test fun pastedUrlLosesSchemeAndPath() = assertEquals(ServerAddress("192.168.1.208", 8025), ok("http://192.168.1.208:8025/tablet?x=1"))
    @Test fun ipv6InBrackets() = assertEquals(ServerAddress("fe80::1", 8025), ok("[fe80::1]:8025"))

    @Test fun rejectsEmptyHttpsBadPortsAndJunk() {
        assertTrue(invalid(""))
        assertTrue(invalid("https://192.168.1.208"))
        assertTrue(invalid("192.168.1.208:0"))
        assertTrue(invalid("192.168.1.208:70000"))
        assertTrue(invalid("192.168.1.208:abc"))
        assertTrue(invalid("fe80::1"))
        assertTrue(invalid("bad host"))
        assertTrue(invalid("[fe80::1"))
    }

    @Test fun hostsAreCanonical() {
        // what the WebView hands back is lower case and RFC 5952-compressed: the typed address must match it
        assertEquals(ServerAddress("fd00::20", 8025), ok("[fd00:0:0:0:0:0:0:20]:8025"))
        assertEquals(ServerAddress("fe80::1", 8025), ok("[FE80:0000::0001]"))
        assertEquals(ServerAddress("2001:db8::1:0:0:1", 8025), ok("[2001:db8:0:0:1:0:0:1]"))   // the first longest run
        assertEquals(ServerAddress("::ffff:c0a8:1d0", 8025), ok("[::ffff:192.168.1.208]"))
        assertEquals(ServerAddress("gamepc.local", 8025), ok("GamePC.Local"))
        assertEquals(ServerAddress("192.168.1.208", 8025), ok("192.168.1.208."))
    }

    @Test fun zoneIdsAreRefused() {
        assertTrue(invalid("[fe80::1%wlan0]:8025"))
    }

    @Test fun originAndDisplay() {
        assertEquals("http://192.168.1.208:8025", ServerAddress("192.168.1.208", 8025).origin)
        assertEquals("http://[fe80::1]:8100", ServerAddress("fe80::1", 8100).origin)
        assertEquals("192.168.1.208", ServerAddress("192.168.1.208", 8025).display)
        assertEquals("192.168.1.208:8100", ServerAddress("192.168.1.208", 8100).display)
    }

    @Test fun sameOriginOnlyForThatHostPortAndHttp() {
        val a = ServerAddress("192.168.1.208", 8025)
        assertTrue(a.isSameOrigin("http://192.168.1.208:8025/tablet"))
        assertTrue(a.isSameOrigin("HTTP://192.168.1.208:8025/api/version"))
        assertFalse(a.isSameOrigin("http://192.168.1.208:8026/tablet"))
        assertFalse(a.isSameOrigin("http://192.168.1.208/tablet")) // port 80
        assertFalse(a.isSameOrigin("https://192.168.1.208:8025/tablet"))
        assertFalse(a.isSameOrigin("http://192.168.1.209:8025/tablet"))
        assertFalse(a.isSameOrigin("http://192.168.1.208.evil.example:8025/"))
        assertFalse(a.isSameOrigin("http://evil.example/?u=http://192.168.1.208:8025/"))
        assertFalse(a.isSameOrigin("javascript:alert(1)"))
        assertFalse(a.isSameOrigin("not a url"))
        assertTrue(ServerAddress("fe80::1", 8025).isSameOrigin("http://[fe80::1]:8025/x"))
        assertTrue(ServerAddress("fd00::20", 8025).isSameOrigin("http://[FD00:0:0::20]:8025/x"))
        assertTrue(ServerAddress("gamepc.local", 8025).isSameOrigin("http://GamePC.local:8025/tablet"))
    }

    @Test fun sameOriginTakesWhatJavaUriRefuses() {
        val a = ServerAddress("192.168.1.208", 8025)
        for (path in listOf("/a|b", "/x^y", "/s?q={1}", "/q?x=`y`", "/p%zz"))
            assertTrue(path, a.isSameOrigin("http://192.168.1.208:8025$path"))
    }

    @Test fun userInfoTricksAreForeign() {
        val a = ServerAddress("192.168.1.208", 8025)
        assertFalse(a.isSameOrigin("http://192.168.1.208:8025@evil.example/"))
        assertFalse(a.isSameOrigin("http://evil.example\\@192.168.1.208:8025/"))
        assertTrue(a.isSameOrigin("http://user@192.168.1.208:8025/"))
    }
}
