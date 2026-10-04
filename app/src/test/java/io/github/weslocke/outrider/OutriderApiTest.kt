package io.github.weslocke.outrider

import io.github.weslocke.outrider.TestHttpServer.Reply
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** The app's own HTTP client against a real local server: headers, bodies, errors, redirects, timeouts. */
class OutriderApiTest {
    private val server = TestHttpServer()
    private val address = ServerAddress("127.0.0.1", server.port)

    @After fun stop() = server.close()

    private val json = "Content-Type" to "application/json"
    private val versionJson = """{"outrider":"2026.10.4","api":1,"min_app":"1.0.0","password":true,"signed_in":true,"game_pc":false}"""

    @Test fun versionSendsTheNumberOnlyAndNoBearerWithoutAToken() {
        server.route("/api/version") { Reply(200, versionJson, json) }
        val r = OutriderApi(address, null, "1.0.0-debug").version()
        assertEquals(VersionInfo("2026.10.4", 1, "1.0.0", password = true, signedIn = true, gamePc = false), (r as OutriderApi.Result.Ok).value)
        val headers = server.seen["/api/version"]!!.headers
        // the -debug suffix would read as an unreadable (too old) version on Outrider
        assertEquals("1.0.0", headers["x-outrider-app"])
        assertNull(headers["authorization"])
    }

    @Test fun bearerWhenThereIsAToken() {
        server.route("/api/version") { Reply(200, versionJson, json) }
        OutriderApi(address, "tok123", "1.0.0").version()
        assertEquals("Bearer tok123", server.seen["/api/version"]!!.headers["authorization"])
    }

    @Test fun signInReturnsTheTokenAndItsCookies() {
        server.route("/api/auth/signin") { Reply(200, """{"ok":true,"token":"abc"}""", json, "Set-Cookie" to "outrider_session=abc; HttpOnly; Path=/") }
        val r = OutriderApi(address, null, "1.0.0").signIn("pw") as OutriderApi.Result.Ok
        assertEquals("abc", r.value)
        assertEquals(listOf("outrider_session=abc; HttpOnly; Path=/"), r.setCookies)
        assertEquals("pw", JSONObject(server.seen["/api/auth/signin"]!!.body.toString(Charsets.UTF_8)).getString("password"))
    }

    @Test fun errorShapes() {
        server.route("/api/auth/signin") { Reply(401, """{"error":"wrong password","code":"bad_password"}""", json) }
        server.route("/api/version") { Reply(500, "Internal Server Error", "Content-Type" to "text/plain") }
        val bad = OutriderApi(address, null, "1.0.0").signIn("x") as OutriderApi.Result.Failed
        assertEquals(ApiError(401, "bad_password", "wrong password"), bad.error)
        val err = OutriderApi(address, null, "1.0.0").version() as OutriderApi.Result.Failed
        assertEquals(500, err.error.status)
        assertEquals("Internal Server Error", err.error.message)   // short plain text is kept
    }

    @Test fun rateLimitCarriesRetryAfter() {
        server.route("/api/auth/signin") { Reply(429, """{"error":"slow down","code":"rate_limited"}""", json, "Retry-After" to "42") }
        val r = OutriderApi(address, null, "1.0.0").signIn("x") as OutriderApi.Result.Failed
        assertEquals(42, r.error.retryAfter)
    }

    @Test fun redirectsAreExplainedNotFollowed() {
        server.route("/api/version") { Reply(301, "", "Location" to "https://example.lan/api/version") }
        val r = OutriderApi(address, null, "1.0.0").version() as OutriderApi.Result.Failed
        assertEquals("redirect", r.error.code)
        assertTrue(r.error.message, r.error.message.contains("https://example.lan/api/version"))
    }

    @Test fun notOutriderWhenTheAnswerIsntItsJson() {
        server.route("/api/version") { Reply(200, "<html>a router</html>", "Content-Type" to "text/html") }
        assertTrue(OutriderApi(address, null, "1.0.0").version() is OutriderApi.Result.NotOutrider)
    }

    @Test fun charsetFromContentType() {
        server.route("/api/version") {
            Reply(200, """{"outrider":"Caf\u00e9","api":1,"min_app":"1.0.0"}""".toByteArray(Charsets.ISO_8859_1),
                listOf("Content-Type" to "application/json; charset=iso-8859-1"))
        }
        val r = OutriderApi(address, null, "1.0.0").version() as OutriderApi.Result.Ok
        assertEquals("Caf\u00e9", r.value.outrider)
        assertEquals(Charsets.UTF_8, OutriderApi.charsetOf(null))
        assertEquals(Charsets.UTF_8, OutriderApi.charsetOf("application/json; charset=no-such-charset"))
    }

    @Test fun askSendsTheQuestionCappedAndItsSource() {
        server.route("/api/ask") { Reply(200, """{"answer":"Fuel 80 percent.","spoken":true,"matched":"fixed","command":"fuel"}""", json) }
        val r = OutriderApi(address, "t", "1.0.0").ask("x".repeat(600)) as OutriderApi.Result.Ok
        assertEquals("fuel", r.value.command)
        val sent = JSONObject(server.seen["/api/ask"]!!.body.toString(Charsets.UTF_8))
        assertEquals(AskAnswer.MAX_TEXT, sent.getString("text").length)
        assertEquals("vespa", sent.getString("source"))
    }

    @Test fun aSilentServerIsUnreachableNotAHang() {
        server.route("/api/version") { Thread.sleep(1500); Reply(200, versionJson, json) }
        val r = OutriderApi(address, null, "1.0.0", readTimeoutMs = 200).version()
        assertTrue(r.toString(), r is OutriderApi.Result.Unreachable && r.reason.startsWith("No answer"))
    }

    @Test fun nothingListening() {
        val port = ServerSocket(0).use { it.localPort }   // free, and closed again
        val r = OutriderApi(ServerAddress("127.0.0.1", port), null, "1.0.0").version()
        assertTrue(r.toString(), r is OutriderApi.Result.Unreachable && r.reason.startsWith("Nothing answered"))
    }
}
