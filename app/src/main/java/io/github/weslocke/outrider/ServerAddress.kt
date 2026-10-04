package io.github.weslocke.outrider

/**
 * The computer running Outrider: plain HTTP on the LAN. Everything the app loads or calls must match this origin
 * ([isSameOrigin]); that check stands in for a per-address network security config, which can't follow a
 * user-entered address.
 *
 * The host is kept canonical (lower case; IPv6 in the compressed RFC 5952 form Chromium uses), so the address the
 * person typed compares equal to the URLs the WebView hands back.
 */
data class ServerAddress(val host: String, val port: Int) {

    val origin: String get() = "http://${bracketed()}:$port"

    /** What the settings field shows: the port only when it isn't the default. */
    val display: String get() = if (port == DEFAULT_PORT) host else "${bracketed()}:$port"

    private fun bracketed() = if (':' in host) "[$host]" else host

    fun url(path: String): String = origin + path

    /**
     * Whether [url] is on this origin. A plain split rather than java.net.URI, which refuses characters Chromium
     * leaves unescaped in paths and queries (`|`, `^`, `{`, backtick) and would make a same-origin link look foreign.
     */
    fun isSameOrigin(url: String): Boolean {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0 || !url.substring(0, schemeEnd).equals("http", ignoreCase = true)) return false
        val rest = url.substring(schemeEnd + 3)
        val authority = rest.substring(0, rest.indexOfAny(charArrayOf('/', '?', '#', '\\')).let { if (it < 0) rest.length else it })
        // user info ("http://192.168.1.208:8025@elsewhere/") names a different host: the part after the last '@'
        val hostPort = authority.substringAfterLast('@')
        val (h, p) = splitHostPort(hostPort) ?: return false
        val port = p ?: 80
        val canonical = canonicalHost(h) ?: return false
        return canonical == host && port == this.port
    }

    sealed class Parsed {
        data class Ok(val address: ServerAddress) : Parsed()
        data class Invalid(val reason: String) : Parsed()
    }

    companion object {
        const val DEFAULT_PORT = 8025

        private val HOSTNAME = Regex("^[a-z0-9]([a-z0-9-]{0,62})(\\.[a-z0-9]([a-z0-9-]{0,62}))*\\.?$")
        private val IPV6_CHARS = Regex("^[0-9a-fA-F:.]+$")

        /**
         * Reads what a person types: "192.168.1.208", "192.168.1.208:8025", "MyPC.local", "[fe80::1]:8025", or a
         * pasted "http://192.168.1.208:8025/tablet" (the path is dropped). The port defaults to 8025.
         */
        fun parse(input: String): Parsed {
            var s = input.trim()
            if (s.isEmpty()) return Parsed.Invalid("Enter the address of the computer running Outrider, e.g. 192.168.1.20")
            if (s.startsWith("https://", ignoreCase = true))
                return Parsed.Invalid("Outrider uses plain http on the LAN, not https")
            if (s.startsWith("http://", ignoreCase = true)) s = s.substring(7)
            s = s.substringBefore('/').substringBefore('?').substringBefore('#')
            if ('%' in s) return Parsed.Invalid("IPv6 zone IDs (%wlan0) can't be used: give the address without it, or the PC's name")
            if (!s.startsWith("[") && s.count { it == ':' } > 1)
                return Parsed.Invalid("Put an IPv6 address in brackets, e.g. [fe80::1]:8025")
            if (s.startsWith("[") && ']' !in s) return Parsed.Invalid("Missing ] after the IPv6 address")
            val (rawHost, portText) = splitHostPort(s, keepPortText = true)?.let { it.first to it.second }
                ?: return Parsed.Invalid("Unexpected text after the address")
            val host = canonicalHost(rawHost)
                ?: return Parsed.Invalid(if (s.startsWith("[")) "That isn't an IPv6 address" else "That isn't a valid address")
            val port = if (portText == null) DEFAULT_PORT else portText
            if (port !in 1..65535) return Parsed.Invalid("The port must be a number from 1 to 65535")
            return Parsed.Ok(ServerAddress(host, port))
        }

        /** "host", "host:port", "[v6]" or "[v6]:port" → host and port (null when absent); null when malformed. */
        private fun splitHostPort(s: String, keepPortText: Boolean = false): Pair<String, Int?>? {
            val host: String
            val portPart: String?
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) return null
                host = s.substring(1, end)
                val after = s.substring(end + 1)
                portPart = when {
                    after.isEmpty() -> null
                    after.startsWith(":") -> after.substring(1)
                    else -> return null
                }
            } else {
                host = s.substringBefore(':')
                portPart = if (':' in s) s.substringAfter(':') else null
            }
            if (portPart == null) return host to null
            val port = portPart.toIntOrNull() ?: return if (keepPortText) host to -1 else null
            return host to port
        }

        /**
         * The canonical form of a host: IPv6 literals compressed and lower-cased (RFC 5952, as Chromium writes
         * them), names lower-cased without a trailing dot; null when it is neither.
         */
        fun canonicalHost(raw: String): String? {
            val h = raw.trim().lowercase()
            if (':' in h) return canonicalIpv6(h)
            val name = h.removeSuffix(".")
            return if (name.isNotEmpty() && HOSTNAME.matches(name)) name else null
        }

        /**
         * "FD00:0:0:0:0:0:0:20" → "fd00::20". An embedded IPv4 tail ("::ffff:1.2.3.4") becomes two hex groups
         * ("::ffff:102:304"), as Chromium writes it.
         */
        fun canonicalIpv6(raw: String): String? {
            if (!IPV6_CHARS.matches(raw)) return null
            var text = raw
            val lastColon = text.lastIndexOf(':')
            val tail = text.substring(lastColon + 1)
            if ('.' in tail) {
                val v4 = tail.split('.').map { it.toIntOrNull() ?: return null }
                if (v4.size != 4 || v4.any { it !in 0..255 }) return null
                text = text.substring(0, lastColon + 1) +
                    Integer.toHexString(v4[0] * 256 + v4[1]) + ":" + Integer.toHexString(v4[2] * 256 + v4[3])
            }
            val halves = text.split("::")
            if (halves.size > 2) return null
            fun groups(part: String) = if (part.isEmpty()) emptyList() else part.split(':')
            val head = groups(halves[0])
            val back = if (halves.size == 2) groups(halves[1]) else emptyList()
            val missing = 8 - head.size - back.size
            if (halves.size == 2 && missing < 1) return null
            if (halves.size == 1 && head.size != 8) return null
            val all = head + List(if (halves.size == 2) missing else 0) { "0" } + back
            val values = all.map { g -> if (g.isEmpty() || g.length > 4) return null else g.toIntOrNull(16) ?: return null }
            // the longest run of two or more zero groups becomes "::" (the first one on a tie)
            var bestStart = -1
            var bestLen = 0
            var i = 0
            while (i < 8) {
                if (values[i] == 0) {
                    var j = i
                    while (j < 8 && values[j] == 0) j++
                    if (j - i > bestLen && j - i >= 2) {
                        bestStart = i
                        bestLen = j - i
                    }
                    i = j
                } else i++
            }
            fun join(from: Int, to: Int) = (from until to).joinToString(":") { Integer.toHexString(values[it]) }
            return if (bestStart < 0) join(0, 8) else join(0, bestStart) + "::" + join(bestStart + bestLen, 8)
        }
    }
}
