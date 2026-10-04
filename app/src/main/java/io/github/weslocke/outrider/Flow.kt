package io.github.weslocke.outrider

/**
 * Where the app goes next, decided without Android: after `/api/version` answers, and after the page's own load
 * fails. MainActivity only carries out the [Step]. Pure, so the decisions are unit-tested.
 */
object Flow {

    sealed class Step {
        object Page : Step()
        /** [forgetToken]: the tablet's session is gone (the password changed, or it was signed out). */
        data class SignIn(val note: String?, val forgetToken: Boolean) : Step()
        data class Update(val app: Boolean, val reason: String) : Step()
        data class NoLink(val reason: String) : Step()
    }

    /** After `GET /api/version`. [hadToken]: the tablet held a token for this Outrider when it asked. */
    fun afterVersion(address: ServerAddress, result: OutriderApi.Result<VersionInfo>, hadToken: Boolean, appVersion: String): Step =
        when (result) {
            is OutriderApi.Result.Ok -> {
                val info = result.value
                when (val verdict = Versions.check(info, appVersion)) {
                    is Versions.Verdict.UpdateApp -> Step.Update(app = true, verdict.reason)
                    is Versions.Verdict.UpdateOutrider -> Step.Update(app = false, verdict.reason)
                    Versions.Verdict.Ok ->
                        if (info.password && !info.signedIn) Step.SignIn(
                            if (hadToken) "This tablet was signed out: the password changed, or it was signed out on Outrider." else null,
                            forgetToken = true,
                        ) else Step.Page
                }
            }
            is OutriderApi.Result.Failed -> when (result.error.status) {
                404 -> Step.Update(app = false, "Outrider at ${address.display} has no tablet support yet (no /api/version).")
                426 -> Step.Update(app = true, result.error.message)
                else -> Step.NoLink(result.error.message)
            }
            is OutriderApi.Result.Unreachable -> Step.NoLink(result.reason)
            is OutriderApi.Result.NotOutrider -> Step.NoLink("Something answered at ${address.display}, but it isn't Outrider.")
        }

    sealed class PageError {
        /** Ask /api/version again (it decides between sign-in and the page). */
        object Reconnect : PageError()
        /** /api/version and the page disagree about the session: don't reload again, sign in. */
        data class SignIn(val note: String) : PageError()
        data class Update(val reason: String) : PageError()
        data class NoLink(val reason: String) : PageError()
        /** Not the tablet page itself (a link the page followed): say so and stay. */
        data class Notice(val text: String) : PageError()
    }

    /** How long a page 401 counts towards [afterPageHttpError]'s loop guard. */
    const val UNAUTHORIZED_WINDOW_MS = 60_000L

    /**
     * A main-frame HTTP error from the WebView. [recentUnauthorized]: page 401s already seen within
     * [UNAUTHORIZED_WINDOW_MS] since /api/version last said the tablet is signed in.
     */
    fun afterPageHttpError(address: ServerAddress, url: String, status: Int, recentUnauthorized: Int): PageError {
        val path = url.substringAfter(address.origin, "").substringBefore('?').substringBefore('#')
        val tabletPage = path == "/tablet" || path == "/tablet/" || path.isEmpty() || path == "/"
        return when {
            !tabletPage -> PageError.Notice("Outrider answered $status for $path")
            status == 401 && recentUnauthorized >= 1 -> PageError.SignIn(
                "Outrider and this tablet disagree about the sign-in: sign in again."
            )
            status == 401 -> PageError.Reconnect
            status == 404 -> PageError.Update("Outrider at ${address.display} has no tablet page (/tablet) yet.")
            else -> PageError.NoLink("Outrider answered HTTP $status for the tablet page.")
        }
    }
}
