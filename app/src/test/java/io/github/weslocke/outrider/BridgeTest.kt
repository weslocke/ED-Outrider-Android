package io.github.weslocke.outrider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeTest {
    @Test fun messages() {
        assertEquals(Bridge.Message.Haptic(30), Bridge.parse("""{"type":"haptic","ms":30}"""))
        assertEquals(Bridge.Message.Haptic(Bridge.MAX_HAPTIC_MS), Bridge.parse("""{"type":"haptic","ms":5000}"""))
        assertEquals(Bridge.Message.SignInRequired, Bridge.parse("""{"type":"signInRequired"}"""))
        assertEquals(Bridge.Message.SetTheme("lcars"), Bridge.parse("""{"type":"setTheme","name":" LCARS "}"""))
        assertEquals(Bridge.Message.Listen, Bridge.parse("""{"type":"listen"}"""))
        assertEquals(Bridge.Message.Open(Bridge.AppScreen.SERVER), Bridge.parse("""{"type":"open","screen":"server"}"""))
        assertEquals(Bridge.Message.Open(Bridge.AppScreen.VOICE), Bridge.parse("""{"type":"open","screen":"voice"}"""))
        assertEquals(Bridge.Message.Open(Bridge.AppScreen.MENU), Bridge.parse("""{"type":"open","screen":"menu"}"""))
    }

    /** Every argument-free method the script defines sends a message the app understands. */
    @Test fun scriptMessagesParse() {
        val method = Regex("""(\w+): function \(\) \{ send\((\{[^}]*\})\); \}""")
        val sent = method.findAll(Bridge.script("1.1.0")).associate { m ->
            m.groupValues[1] to Bridge.parse(m.groupValues[2].replace(Regex("""(\w+):"""), "\"$1\":"))
        }
        assertEquals(setOf("signInRequired", "listen", "openServer", "openVoice", "openMenu"), sent.keys)
        assertEquals(Bridge.Message.Open(Bridge.AppScreen.SERVER), sent["openServer"])
        assertEquals(Bridge.Message.Open(Bridge.AppScreen.VOICE), sent["openVoice"])
        assertEquals(Bridge.Message.Open(Bridge.AppScreen.MENU), sent["openMenu"])
        assertEquals(Bridge.Message.Listen, sent["listen"])
        assertEquals(Bridge.Message.SignInRequired, sent["signInRequired"])
    }

    @Test fun junkIsIgnored() {
        assertNull(Bridge.parse(null))
        assertNull(Bridge.parse("not json"))
        assertNull(Bridge.parse("""{"type":"haptic","ms":0}"""))
        assertNull(Bridge.parse("""{"type":"haptic"}"""))
        assertNull(Bridge.parse("""{"type":"setTheme","name":""}"""))
        assertNull(Bridge.parse("""{"type":"somethingNewer"}"""))
        assertNull(Bridge.parse("""{"type":"open"}"""))
        assertNull(Bridge.parse("""{"type":"open","screen":"settings"}"""))
    }

    @Test fun scriptCarriesTheVersionsSafely() {
        val s = Bridge.script("1.0.0\"</script>")
        assertTrue(s.contains("bridgeVersion: ${Contract.BRIDGE}"))
        assertTrue(s.contains("window.${Bridge.NATIVE_OBJECT}"))
        assertTrue(s.contains("listen: function"))
        assertTrue(s.contains("\"1.0.0\\\"<\\/script>\""))
    }

    @Test fun themesByName() {
        assertEquals("elite", AppTheme.named("elite").name)
        assertEquals("babylon5", AppTheme.named("BABYLON5").name)
        assertEquals(AppTheme.Frame.CHAMFER, AppTheme.named("elite").frame)
        for (name in listOf("narn", "minbari", "centauri", "sith", "alliance", "dark")) assertEquals(name, AppTheme.named(name).name)
        // secondary buttons default to the fill text colour; the dark theme's grey ones get light text
        assertEquals(AppTheme.LCARS.onFill, AppTheme.LCARS.onSecondary)
        assertEquals(AppTheme.DARK.text, AppTheme.DARK.onSecondary)
        // unknown names (a newer page's theme) and no theme at all: LCARS
        assertEquals("lcars", AppTheme.named("farscape").name)
        assertEquals("lcars", AppTheme.named(null).name)
    }
}
