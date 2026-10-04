package io.github.weslocke.outrider

import java.net.URI
import java.net.URISyntaxException

/**
 * The PC running Outrider: plain HTTP on the LAN. Everything the app loads or calls must match this origin
 * ([isSameOrigin]); that check stands in for a per-address network security config, which can't follow a
 * user-entered address.
 */
data class ServerAddress(val host: String, val port: Int) {

    val origin: String get() = "http://${if (':' in host) "[$host]" else host}:$port"

    /** What the settings field shows: the port only when it isn't the default. */
    val display: String get() = if (port == DEFAULT_PORT) host else "${if (':' in host) "[$host]" else host}:$port"

    fun url(path: String): String = origin + path

    fun isSameOrigin(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        if (!"http".equals(uri.scheme, ignoreCase = true)) return false
        val h = uri.host?.removeSurrounding("[", "]") ?: return false
        val p = if (uri.port == -1) 80 else uri.port
        return h.equals(host, ignoreCase = true) && p == port
    }

    sealed class Parsed {
        data class Ok(val address: ServerAddress) : Parsed()
        data class Invalid(val reason: String) : Parsed()
    }

    companion object {
        const val DEFAULT_PORT = 8025

        private val HOSTNAME = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*\\.?$")
        private val IPV6 = Regex("^[0-9A-Fa-f:.]+(%[A-Za-z0-9]+)?$")

        /**
         * Reads what a person types: "192.168.1.208", "192.168.1.208:8025", "mypc.local", "[fe80::1]:8025", or a
         * pasted "http://192.168.1.208:8025/tablet" (the path is dropped). The port defaults to 8025.
         */
        fun parse(input: String): Parsed {
            var s = input.trim()
            if (s.isEmpty()) return Parsed.Invalid("Enter the address of the computer running Outrider, e.g. 192.168.1.20")
            if (s.startsWith("https://", ignoreCase = true))
                return Parsed.Invalid("Outrider uses plain http on the LAN, not https")
            if (s.startsWith("http://", ignoreCase = true)) s = s.substring(7)
            s = s.substringBefore('/').substringBefore('?').substringBefore('#')

            val host: String
            var portText: String? = null
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) return Parsed.Invalid("Missing ] after the IPv6 address")
                host = s.substring(1, end)
                val rest = s.substring(end + 1)
                if (rest.isNotEmpty()) {
                    if (!rest.startsWith(":")) return Parsed.Invalid("Unexpected text after the address")
                    portText = rest.substring(1)
                }
                if (!IPV6.matches(host)) return Parsed.Invalid("That isn't an IPv6 address")
            } else if (s.count { it == ':' } > 1) {
                return Parsed.Invalid("Put an IPv6 address in brackets, e.g. [fe80::1]:8025")
            } else {
                host = s.substringBefore(':')
                if (':' in s) portText = s.substringAfter(':')
                if (!HOSTNAME.matches(host)) return Parsed.Invalid("That isn't a valid address")
            }

            val port = if (portText == null) DEFAULT_PORT else portText.toIntOrNull()
            if (port == null || port !in 1..65535) return Parsed.Invalid("The port must be a number from 1 to 65535")
            return Parsed.Ok(ServerAddress(host.removeSuffix("."), port))
        }
    }
}
