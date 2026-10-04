package io.github.weslocke.outrider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowTest {
    private val a = ServerAddress("192.168.1.208", 8025)
    private fun version(password: Boolean, signedIn: Boolean, api: Int = 1, minApp: String = "1.0.0") =
        OutriderApi.Result.Ok(VersionInfo("2026.10.4", api, minApp, password, signedIn))

    @Test fun pageWhenSignedInOrNoPassword() {
        assertEquals(Flow.Step.Page, Flow.afterVersion(a, version(password = false, signedIn = false), false, "1.0.0"))
        assertEquals(Flow.Step.Page, Flow.afterVersion(a, version(password = true, signedIn = true), true, "1.0.0"))
    }

    @Test fun signInExplainsALostSessionOnlyWhenThereWasOne() {
        val first = Flow.afterVersion(a, version(password = true, signedIn = false), hadToken = false, "1.0.0") as Flow.Step.SignIn
        assertEquals(null, first.note)
        val lost = Flow.afterVersion(a, version(password = true, signedIn = false), hadToken = true, "1.0.0") as Flow.Step.SignIn
        assertTrue(lost.note!!.contains("signed out"))
        assertTrue(lost.forgetToken)
    }

    @Test fun updates() {
        assertEquals(true, (Flow.afterVersion(a, version(true, true, api = 2), true, "1.0.0") as Flow.Step.Update).app)
        assertEquals(true, (Flow.afterVersion(a, version(true, true, minApp = "2.0.0"), true, "1.0.0-debug") as Flow.Step.Update).app)
        val old = Flow.afterVersion(a, OutriderApi.Result.Failed(ApiError(404, "not_found", "x")), false, "1.0.0") as Flow.Step.Update
        assertEquals(false, old.app)
        assertEquals(true, (Flow.afterVersion(a, OutriderApi.Result.Failed(ApiError(426, "app_too_old", "too old")), false, "1.0.0") as Flow.Step.Update).app)
    }

    @Test fun noLink() {
        assertEquals(Flow.Step.NoLink("down"), Flow.afterVersion(a, OutriderApi.Result.Unreachable("down"), false, "1.0.0"))
        assertTrue(Flow.afterVersion(a, OutriderApi.Result.NotOutrider(200), false, "1.0.0") is Flow.Step.NoLink)
        assertEquals(Flow.Step.NoLink("boom"), Flow.afterVersion(a, OutriderApi.Result.Failed(ApiError(500, "server_error", "boom")), false, "1.0.0"))
    }

    @Test fun pageErrorsOnTheTabletPage() {
        assertEquals(Flow.PageError.Reconnect, Flow.afterPageHttpError(a, a.url("/tablet"), 401, 0))
        // a second 401 soon after /api/version said "signed in": stop reloading, sign in
        assertTrue(Flow.afterPageHttpError(a, a.url("/tablet"), 401, 1) is Flow.PageError.SignIn)
        assertTrue(Flow.afterPageHttpError(a, a.url("/tablet?x=1"), 404, 0) is Flow.PageError.Update)
        assertTrue(Flow.afterPageHttpError(a, a.url("/tablet"), 502, 0) is Flow.PageError.NoLink)
    }

    @Test fun otherPathsAreANoticeNotAScreen() {
        // a link the page followed (an export an older Outrider lacks) mustn't claim there's no tablet page
        assertEquals(Flow.PageError.Notice("Outrider answered 404 for /api/export/firsts.csv"),
            Flow.afterPageHttpError(a, a.url("/api/export/firsts.csv"), 404, 0))
        assertTrue(Flow.afterPageHttpError(a, a.url("/api/whatever"), 401, 5) is Flow.PageError.Notice)
    }
}
