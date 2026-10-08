package io.github.weslocke.outrider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractTest {
    @Test fun parsesVersionAndIgnoresUnknownFields() {
        val v = VersionInfo.parse("""{"outrider":"2026.10.3","api":1,"min_app":"1.0.0","password":true,"signed_in":false,"new_field":[1]}""")
        assertEquals(VersionInfo("2026.10.3", 1, "1.0.0", password = true, signedIn = false), v)
    }

    @Test fun serverMode() {
        // an older Outrider has no game_pc: it is always the game PC
        assertEquals(true, VersionInfo.parse("""{"outrider":"x","api":1,"min_app":"1.0.0","password":false,"signed_in":true}""")!!.gamePc)
        assertEquals(false, VersionInfo.parse("""{"outrider":"x","api":1,"min_app":"1.0.0","password":true,"signed_in":true,"game_pc":false}""")!!.gamePc)
    }

    @Test fun versionAnswerThatIsNotOutriders() {
        assertNull(VersionInfo.parse("<html>hello</html>"))
        assertNull(VersionInfo.parse("""{"status":"ok"}"""))
        assertNull(VersionInfo.parse("""{"api":"one"}"""))
    }

    @Test fun errorShape() {
        assertEquals(ApiError(401, "bad_password", "Wrong password"), ApiError.parse(401, """{"error":"Wrong password","code":"bad_password"}"""))
        assertEquals(ApiError(429, "rate_limited", "slow down", 30), ApiError.parse(429, """{"error":"slow down","code":"rate_limited"}""", 30))
        // an old Outrider: {"error"} only, or no JSON at all
        assertEquals("signin_required", ApiError.parse(401, """{"error":"no"}""").code)
        assertEquals(ApiError(500, "http_500", "Outrider answered HTTP 500"), ApiError.parse(500, "<html><body>oops</body></html>"))
        // Outrider's host check answers in plain text: keep its words
        val host = "ED Outrider does not answer to the host name 'gamepc.local:8025'. To reach it by that name, add it to [server] allowed_hosts in ed_outrider.toml."
        assertEquals(ApiError(403, "http_403", host), ApiError.parse(403, host + "\n"))
    }

    @Test fun askAnswer() {
        assertEquals(AskAnswer("Fuel 80 percent, 12 jumps.", true, "fixed", "fuel"),
            AskAnswer.parse("""{"answer":"Fuel 80 percent, 12 jumps.","spoken":true,"matched":"fixed","command":"fuel","extra":1}"""))
        assertEquals(AskAnswer("Sorry.", false, "none", null),
            AskAnswer.parse("""{"answer":"Sorry.","spoken":false,"matched":"none","command":null}"""))
        assertNull(AskAnswer.parse("""{"ok":true}"""))
        assertNull(AskAnswer.parse("<html>"))
    }

    @Test fun versionCompare() {
        assertTrue(Versions.compare("1.10.0", "1.9") > 0)
        assertEquals(0, Versions.compare("1.0", "1.0.0"))
        assertEquals(0, Versions.compare("1.0.0-debug", "1.0.0"))
        assertTrue(Versions.compare("1.0.0", "1.0.1") < 0)
        assertTrue(Versions.compare("2026.10.3", "2026.9.30") > 0)
    }

    private fun info(api: Int = 1, minApp: String = "1.0.0") = VersionInfo("2026.10.3", api, minApp, password = true, signedIn = true)

    @Test fun compatibility() {
        assertEquals(Versions.Verdict.Ok, Versions.check(info(), "1.0.0"))
        assertEquals(Versions.Verdict.Ok, Versions.check(info(), "1.0.0-debug"))
        assertTrue(Versions.check(info(api = 2), "1.0.0") is Versions.Verdict.UpdateApp)
        assertTrue(Versions.check(info(api = 0), "1.0.0") is Versions.Verdict.UpdateOutrider)
        assertTrue(Versions.check(info(minApp = "1.1.0"), "1.0.9") is Versions.Verdict.UpdateApp)
    }

    @Test fun projectLinksAreTheGitHubRepos() {
        assertEquals("https://github.com/weslocke/ED-Outrider-Android", Project.APP_URL)
        assertEquals("https://github.com/weslocke/ED-Outrider", Project.OUTRIDER_URL)
    }
}
