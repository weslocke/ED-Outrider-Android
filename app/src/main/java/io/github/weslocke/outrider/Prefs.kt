package io.github.weslocke.outrider

import android.content.Context
import androidx.core.content.edit

/** What the app keeps on the tablet. The password itself is never stored: only the session token it earns. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("outrider", Context.MODE_PRIVATE)

    var address: ServerAddress?
        get() {
            val host = sp.getString(KEY_HOST, null) ?: return null
            return ServerAddress(host, sp.getInt(KEY_PORT, ServerAddress.DEFAULT_PORT))
        }
        set(value) = sp.edit {
            if (value == null) remove(KEY_HOST).remove(KEY_PORT)
            else putString(KEY_HOST, value.host).putInt(KEY_PORT, value.port)
        }

    /** The session token for the current Outrider (each saved Outrider keeps its own). */
    var token: String?
        get() = address?.let { tokens()[it.origin] }
        set(value) {
            val origin = address?.origin ?: return
            val t = tokens()
            if (value == null) t.remove(origin) else t[origin] = value
            sp.edit { putString(KEY_TOKENS, Servers.tokensToJson(t)) }
        }

    private fun tokens(): MutableMap<String, String> {
        val t = Servers.tokensFromJson(sp.getString(KEY_TOKENS, null))
        val legacy = sp.getString(KEY_TOKEN, null) ?: return t
        val merged = Servers.migrateLegacyToken(t, legacy, address?.origin)
        sp.edit { remove(KEY_TOKEN).putString(KEY_TOKENS, Servers.tokensToJson(merged)) }
        return merged.toMutableMap()
    }

    /** The current Outrider's sign-in cookie lines (see [Servers.cookiesToJson]). */
    var cookieLines: List<String>
        get() = address?.let { Servers.cookiesFromJson(sp.getString(KEY_COOKIES, null))[it.origin] } ?: emptyList()
        set(value) {
            val origin = address?.origin ?: return
            val c = Servers.cookiesFromJson(sp.getString(KEY_COOKIES, null))
            if (value.isEmpty()) c.remove(origin) else c[origin] = value
            sp.edit { putString(KEY_COOKIES, Servers.cookiesToJson(c)) }
        }

    /** Drops everything kept for one Outrider: its token and its cookie lines (when it is forgotten). */
    fun forgetSessionOf(origin: String) {
        val t = tokens()
        t.remove(origin)
        val c = Servers.cookiesFromJson(sp.getString(KEY_COOKIES, null))
        c.remove(origin)
        sp.edit { putString(KEY_TOKENS, Servers.tokensToJson(t)).putString(KEY_COOKIES, Servers.cookiesToJson(c)) }
    }

    /** The Outriders connected to before, most recent first (see [Servers]). */
    var saved: List<SavedServer>
        get() = Servers.fromJson(sp.getString(KEY_SAVED, null))
        set(value) = sp.edit { putString(KEY_SAVED, Servers.toJson(value)) }

    var theme: String?
        get() = sp.getString(KEY_THEME, null)
        set(value) = sp.edit { if (value == null) remove(KEY_THEME) else putString(KEY_THEME, value) }

    /** The wake word (phase 6): on unless switched off; the word after OK / Hey / Hello; how readily it triggers. */
    var wakeEnabled: Boolean
        get() = sp.getBoolean(KEY_WAKE_ON, true)
        set(value) = sp.edit { putBoolean(KEY_WAKE_ON, value) }

    var wakeWord: String
        get() = sp.getString(KEY_WAKE_WORD, null) ?: WakePhrases.DEFAULT_WORD
        set(value) = sp.edit { putString(KEY_WAKE_WORD, value) }

    var wakeSensitivity: WakePhrases.Sensitivity
        get() = WakePhrases.Sensitivity.entries.firstOrNull { it.name == sp.getString(KEY_WAKE_SENS, null) } ?: WakePhrases.Sensitivity.NORMAL
        set(value) = sp.edit { putString(KEY_WAKE_SENS, value.name) }

    /** The microphone has been asked for once at start (a refusal isn't asked again; the Voice screen can). */
    var micAsked: Boolean
        get() = sp.getBoolean(KEY_MIC_ASKED, false)
        set(value) = sp.edit { putBoolean(KEY_MIC_ASKED, value) }

    private companion object {
        const val KEY_WAKE_ON = "wake_on"
        const val KEY_WAKE_WORD = "wake_word"
        const val KEY_WAKE_SENS = "wake_sensitivity"
        const val KEY_MIC_ASKED = "mic_asked"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_TOKEN = "token"   // 1.0.0's single token, moved into KEY_TOKENS on first read
        const val KEY_TOKENS = "tokens"
        const val KEY_COOKIES = "cookies"
        const val KEY_SAVED = "saved_servers"
        const val KEY_THEME = "theme"
    }
}
