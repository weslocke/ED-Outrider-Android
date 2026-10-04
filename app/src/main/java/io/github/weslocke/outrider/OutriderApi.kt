package io.github.weslocke.outrider

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * The app's own (native) calls to Outrider. Blocking: call from a background thread. Every call sends
 * `X-Outrider-App`, and `Authorization: Bearer` when there is a token. Only [address] is ever contacted.
 */
class OutriderApi(
    private val address: ServerAddress,
    private val token: String?,
    private val appVersion: String,
    /** Overridable so tests needn't wait for the real timeouts. */
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val askTimeoutMs: Int = ASK_TIMEOUT_MS,
) {
    sealed class Result<out T> {
        data class Ok<T>(val value: T, val setCookies: List<String> = emptyList()) : Result<T>()
        data class Failed(val error: ApiError) : Result<Nothing>()
        data class Unreachable(val reason: String) : Result<Nothing>()
        /** Something answered, but not with Outrider's JSON. */
        data class NotOutrider(val status: Int) : Result<Nothing>()
    }

    fun version(): Result<VersionInfo> = when (val r = request("GET", "/api/version")) {
        is Response.Ok -> VersionInfo.parse(r.body)?.let { Result.Ok(it) } ?: Result.NotOutrider(r.status)
        is Response.Error -> r.result
    }

    /** Signs in; the value is the session token. Its `Set-Cookie` lines go into the WebView's cookie store. */
    fun signIn(password: String): Result<String> {
        val body = JSONObject().put("password", password).toString()
        return when (val r = request("POST", "/api/auth/signin", body)) {
            is Response.Ok -> {
                val token = try {
                    JSONObject(r.body).optString("token", "")
                } catch (e: org.json.JSONException) {
                    ""
                }
                if (token.isEmpty()) Result.NotOutrider(r.status) else Result.Ok(token, r.setCookies)
            }
            is Response.Error -> r.result
        }
    }

    /** Asks Outrider a spoken question (phase 6). The PC speaks the answer; the value is its text. */
    fun ask(text: String): Result<AskAnswer> {
        val body = JSONObject().put("text", text.take(AskAnswer.MAX_TEXT)).put("source", "vespa").toString()
        return when (val r = request("POST", "/api/ask", body)) {
            is Response.Ok -> AskAnswer.parse(r.body)?.let { Result.Ok(it) } ?: Result.NotOutrider(r.status)
            is Response.Error -> r.result
        }
    }

    fun signOut(): Result<Unit> = when (val r = request("POST", "/api/auth/signout", "{}")) {
        is Response.Ok -> Result.Ok(Unit)
        is Response.Error -> r.result
    }

    private sealed class Response {
        class Ok(val status: Int, val body: String, val setCookies: List<String>) : Response()
        class Error(val result: Result<Nothing>) : Response()
    }

    private fun request(method: String, path: String, body: String? = null): Response {
        val conn = try {
            URL(address.url(path)).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return Response.Error(Result.Unreachable(e.message ?: "bad address"))
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = if (path == "/api/ask") askTimeoutMs else readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            // The number only: Outrider compares dotted numbers, and "1.0.0-debug" would read as unreadable (too old).
            conn.setRequestProperty(Contract.APP_HEADER, appVersion.substringBefore('-'))
            if (!token.isNullOrEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                // no fixed-length streaming: with it, some HttpURLConnections drop a 401's error body (the reason)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            if (status in 300..399) {
                // never followed: a proxy or a wrong port sending the app elsewhere (https, a login page)
                val where = conn.getHeaderField("Location")
                return Response.Error(Result.Failed(ApiError(status, "redirect", redirectMessage(status, where))))
            }
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val charset = charsetOf(conn.contentType)
            val text = stream?.bufferedReader(charset)?.use { it.readText() } ?: ""
            if (status in 200..299) {
                val cookies = conn.headerFields.entries
                    .filter { it.key != null && it.key.equals("Set-Cookie", ignoreCase = true) }
                    .flatMap { it.value }
                return Response.Ok(status, text, cookies)
            }
            val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
            val error = ApiError.parse(status, text, retryAfter)
            return Response.Error(Result.Failed(error))
        } catch (e: IOException) {
            return Response.Error(Result.Unreachable(reason(e)))
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** The charset a Content-Type names ("application/json; charset=iso-8859-1"), UTF-8 when none or unknown. */
        fun charsetOf(contentType: String?): java.nio.charset.Charset {
            val name = contentType?.split(';')?.map { it.trim() }
                ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')?.trim('"', ' ')
            return try {
                if (name.isNullOrEmpty()) Charsets.UTF_8 else java.nio.charset.Charset.forName(name)
            } catch (e: IllegalArgumentException) {
                Charsets.UTF_8
            }
        }

        fun redirectMessage(status: Int, location: String?): String =
            if (location.isNullOrBlank()) "Outrider's address answered with a redirect ($status), not Outrider itself."
            else "That address answered with a redirect ($status) to $location: give the app Outrider's own plain " +
                "http address (no proxy, no https)."

        /** Words for a person instead of Java's ("Failed to connect to /192.168.1.208:8025"). */
        fun reason(e: IOException): String = when (e) {
            is SocketTimeoutException -> "No answer: that computer may be off, asleep or blocking the port."
            is ConnectException -> "Nothing answered on that port: Outrider may not be running, or listens only on 127.0.0.1."
            is NoRouteToHostException -> "That computer can't be reached from this network."
            is UnknownHostException -> "No computer by that name on this network."
            else -> e.message ?: e.javaClass.simpleName
        }

        const val CONNECT_TIMEOUT_MS = 4000
        const val READ_TIMEOUT_MS = 8000
        /** /api/ask may wait on the optional AI layer, which has its own (shorter) timeout on the PC. */
        const val ASK_TIMEOUT_MS = 45000
    }
}
