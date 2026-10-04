package io.github.weslocke.outrider

import org.junit.Assert.assertEquals
import org.junit.Test

class ServersTest {
    private val pc = ServerAddress("192.168.1.208", 8025)
    private val nas = ServerAddress("nas.local", 8030)
    private val a3 = ServerAddress("10.0.0.3", 8025)
    private val a4 = ServerAddress("10.0.0.4", 8025)
    private val a5 = ServerAddress("10.0.0.5", 8025)

    @Test fun mostRecentFirstAndCapped() {
        var l = Servers.remember(emptyList(), pc, true)
        l = Servers.remember(l, nas, false)
        assertEquals(listOf(nas, pc), l.map { it.address })
        l = Servers.remember(l, pc, null)   // reconnecting moves it up and keeps what it said before
        assertEquals(SavedServer(pc, true), l.first())
        for (a in listOf(a3, a4, a5)) l = Servers.remember(l, a, null)
        assertEquals(listOf(a5, a4, a3, pc), l.map { it.address })
    }

    @Test fun labels() {
        assertEquals("192.168.1.208 · game PC", SavedServer(pc, true).label)
        assertEquals("nas.local:8030 · server", SavedServer(nas, false).label)
        assertEquals("10.0.0.3", SavedServer(a3).label)
    }

    @Test fun jsonRoundTripAndJunk() {
        val l = listOf(SavedServer(nas, false), SavedServer(pc, true), SavedServer(a3))
        assertEquals(l, Servers.fromJson(Servers.toJson(l)))
        assertEquals(emptyList<SavedServer>(), Servers.fromJson("not json"))
        assertEquals(listOf(SavedServer(pc)), Servers.fromJson("""[{"host":"192.168.1.208","port":8025},{"host":"","port":1},{"port":9}, 5]"""))
    }

    @Test fun forget() {
        assertEquals(listOf(SavedServer(pc)), Servers.forget(listOf(SavedServer(nas), SavedServer(pc)), nas))
    }

    @Test fun legacyTokenMovesToItsOutrider() {
        assertEquals(mapOf(pc.origin to "old"), Servers.migrateLegacyToken(emptyMap(), "old", pc.origin))
        // an Outrider that already has its own token keeps it
        assertEquals(mapOf(pc.origin to "new"), Servers.migrateLegacyToken(mapOf(pc.origin to "new"), "old", pc.origin))
        // no address saved yet, or no legacy token: nothing to move
        assertEquals(emptyMap<String, String>(), Servers.migrateLegacyToken(emptyMap(), "old", null))
        assertEquals(mapOf(nas.origin to "x"), Servers.migrateLegacyToken(mapOf(nas.origin to "x"), null, pc.origin))
    }

    @Test fun tokensPerOutrider() {
        val t = mapOf(pc.origin to "abc", nas.origin to "def")
        assertEquals(t, Servers.tokensFromJson(Servers.tokensToJson(t)))
        assertEquals(emptyMap<String, String>(), Servers.tokensFromJson("[1,2]"))
    }
}
