package io.github.weslocke.outrider

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The Outriders this tablet has connected to, most recent first, so it can switch between them (e.g. one on the
 * game PC and one on a server). Each remembers whether it said it runs on the game PC. Pure: unit-tested.
 */
data class SavedServer(val address: ServerAddress, val gamePc: Boolean? = null) {
    /** "192.168.1.208 · game PC", "nas.local:8030 · server", or just the address before Outrider has answered. */
    val label: String
        get() = address.display + when (gamePc) {
            true -> " · game PC"
            false -> " · server"
            null -> ""
        }
}

object Servers {
    const val MAX = 4

    /** [list] with [address] moved to the front (its game-PC flag updated), at most [MAX] kept. */
    fun remember(list: List<SavedServer>, address: ServerAddress, gamePc: Boolean?): List<SavedServer> {
        val old = list.firstOrNull { it.address == address }
        val entry = SavedServer(address, gamePc ?: old?.gamePc)
        return (listOf(entry) + list.filter { it.address != address }).take(MAX)
    }

    fun forget(list: List<SavedServer>, address: ServerAddress) = list.filter { it.address != address }

    fun toJson(list: List<SavedServer>): String = JSONArray().apply {
        for (s in list) put(JSONObject().put("host", s.address.host).put("port", s.address.port).apply {
            if (s.gamePc != null) put("game_pc", s.gamePc)
        })
    }.toString()

    /** Anything unreadable is skipped (or the whole list, if it isn't a list). */
    fun fromJson(json: String?): List<SavedServer> {
        if (json.isNullOrBlank()) return emptyList()
        val a = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return emptyList()
        }
        val out = mutableListOf<SavedServer>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val host = o.optString("host", "")
            val port = o.optInt("port", 0)
            if (host.isEmpty() || port !in 1..65535) continue
            out += SavedServer(ServerAddress(host, port), if (o.has("game_pc")) o.optBoolean("game_pc") else null)
        }
        return out.distinctBy { it.address }.take(MAX)
    }

    /** Session tokens per Outrider (keyed by origin), so switching doesn't sign the tablet out of the other one. */
    fun tokensFromJson(json: String?): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        if (json.isNullOrBlank()) return out
        try {
            val o = JSONObject(json)
            for (k in o.keys()) o.optString(k).takeIf { it.isNotEmpty() }?.let { out[k] = it }
        } catch (e: JSONException) {
            // unreadable: no tokens, every Outrider asks to sign in again
        }
        return out
    }

    fun tokensToJson(tokens: Map<String, String>): String = JSONObject(tokens as Map<*, *>).toString()
}
