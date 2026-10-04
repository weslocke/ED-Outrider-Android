package io.github.weslocke.outrider

import org.json.JSONException
import org.json.JSONObject

/**
 * The native-facing contract with Outrider (PLAN-tablet-2026-10-02, "The API contract for the app"). Both sides
 * ignore unknown fields; Outrider bumps `api` only on a breaking change to this contract.
 */
object Contract {
    /** The contract version this app speaks. */
    const val API = 1

    /** The page <-> app bridge version (`window.OutriderApp.bridgeVersion`). */
    const val BRIDGE = 1

    const val APP_HEADER = "X-Outrider-App"
}

/** `GET /api/version`. */
data class VersionInfo(
    val outrider: String,
    val api: Int,
    val minApp: String,
    val password: Boolean,
    val signedIn: Boolean,
    /** False for an Outrider in server mode (away from the game PC: no game buttons). Absent = an older Outrider,
     *  which always runs on the game PC. */
    val gamePc: Boolean = true,
) {
    companion object {
        /** Null when the answer isn't Outrider's (not JSON, or no integer `api`). */
        fun parse(body: String): VersionInfo? = try {
            val o = JSONObject(body)
            if (!o.has("api")) null else VersionInfo(
                outrider = o.optString("outrider", "?"),
                api = o.getInt("api"),
                minApp = o.optString("min_app", "0"),
                password = o.optBoolean("password", false),
                signedIn = o.optBoolean("signed_in", false),
                gamePc = o.optBoolean("game_pc", true),
            )
        } catch (e: JSONException) {
            null
        }
    }
}

/** `POST /api/ask` (phase 6). `matched`: "fixed", "ai" or "none"; `spoken`: whether Outrider said it aloud. */
data class AskAnswer(val answer: String, val spoken: Boolean, val matched: String, val command: String?) {
    companion object {
        /** The longest question the contract accepts. */
        const val MAX_TEXT = 500

        /** Null when the answer isn't Outrider's (no `answer` string). */
        fun parse(body: String): AskAnswer? = try {
            val o = JSONObject(body)
            if (!o.has("answer")) null else AskAnswer(
                answer = o.optString("answer", ""),
                spoken = o.optBoolean("spoken", false),
                matched = o.optString("matched", "none"),
                command = if (o.isNull("command")) null else o.optString("command").ifEmpty { null },
            )
        } catch (e: JSONException) {
            null
        }
    }
}

/** `{"error": "<words for a person>", "code": "<machine code>"}`; falls back to the status when the body isn't that. */
data class ApiError(val status: Int, val code: String, val message: String, val retryAfter: Int? = null) {
    companion object {
        fun parse(status: Int, body: String?, retryAfter: Int? = null): ApiError {
            var code = ""
            var message = ""
            if (!body.isNullOrBlank()) try {
                val o = JSONObject(body)
                code = o.optString("code", "")
                message = o.optString("error", "")
            } catch (e: JSONException) {
                // not JSON: an old Outrider, a proxy, or something else on that port. A short plain-text answer is
                // still worth showing: Outrider's host check explains itself that way ("add it to allowed_hosts").
                val text = body.trim()
                if (text.length <= 300 && !text.startsWith("<")) message = text
            }
            if (code.isEmpty()) code = when (status) {
                400 -> "bad_request"
                401 -> "signin_required"
                404 -> "not_found"
                426 -> "app_too_old"
                429 -> "rate_limited"
                else -> "http_$status"
            }
            if (message.isEmpty()) message = "Outrider answered HTTP $status"
            return ApiError(status, code, message, retryAfter)
        }
    }
}

object Versions {
    /**
     * Compares dotted versions numerically ("1.10.0" > "1.9"); a suffix such as "-debug" is ignored and missing
     * parts count as 0.
     */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun parts(v: String): List<Int> =
        v.trim().substringBefore('-').substringBefore('+').split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    sealed class Verdict {
        object Ok : Verdict()
        data class UpdateApp(val reason: String) : Verdict()
        data class UpdateOutrider(val reason: String) : Verdict()
    }

    fun check(info: VersionInfo, appVersion: String, appApi: Int = Contract.API): Verdict = when {
        info.api > appApi -> Verdict.UpdateApp(
            "Outrider ${info.outrider} speaks app contract ${info.api}; this app knows contract $appApi."
        )
        info.api < appApi -> Verdict.UpdateOutrider(
            "This app needs app contract $appApi; Outrider ${info.outrider} speaks contract ${info.api}."
        )
        compare(appVersion, info.minApp) < 0 -> Verdict.UpdateApp(
            "Outrider ${info.outrider} needs app ${info.minApp} or newer; this is ${appVersion.substringBefore('-')}."
        )
        else -> Verdict.Ok
    }
}
