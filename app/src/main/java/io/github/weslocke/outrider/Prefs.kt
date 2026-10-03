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

    var token: String?
        get() = sp.getString(KEY_TOKEN, null)
        set(value) = sp.edit { if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, value) }

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
        const val KEY_TOKEN = "token"
        const val KEY_THEME = "theme"
    }
}
