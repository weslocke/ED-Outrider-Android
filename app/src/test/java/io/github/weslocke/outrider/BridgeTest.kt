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
    }

    @Test fun junkIsIgnored() {
        assertNull(Bridge.parse(null))
        assertNull(Bridge.parse("not json"))
        assertNull(Bridge.parse("""{"type":"haptic","ms":0}"""))
        assertNull(Bridge.parse("""{"type":"haptic"}"""))
        assertNull(Bridge.parse("""{"type":"setTheme","name":""}"""))
        assertNull(Bridge.parse("""{"type":"somethingNewer"}"""))
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
        for (name in listOf("narn", "sith", "alliance")) assertEquals(name, AppTheme.named(name).name)
        // unknown names (a newer page's theme) and no theme at all: LCARS
        assertEquals("lcars", AppTheme.named("farscape").name)
        assertEquals("lcars", AppTheme.named(null).name)
    }
}
