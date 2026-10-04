package io.github.weslocke.outrider

import org.json.JSONException
import org.json.JSONObject

/**
 * `window.OutriderApp`, the page <-> app bridge (contract version [Contract.BRIDGE]). The page feature-detects
 * each method and works without the object in a plain browser.
 *
 * It is built from two origin-restricted WebView features rather than addJavascriptInterface: a document-start
 * script defines `window.OutriderApp`, and a web message listener ([NATIVE_OBJECT]) carries its calls to the app.
 * Neither exists on any page but the configured Outrider's.
 */
object Bridge {
    const val NATIVE_OBJECT = "OutriderAppNative"

    /** The script injected at document start. [appVersion] is baked in, so `appVersion()` answers synchronously. */
    fun script(appVersion: String): String = """
        (function () {
          if (window.OutriderApp) return;
          function send(m) {
            var n = window.$NATIVE_OBJECT;
            if (n && n.postMessage) { try { n.postMessage(JSON.stringify(m)); } catch (e) {} }
          }
          Object.defineProperty(window, "OutriderApp", { value: Object.freeze({
            bridgeVersion: ${Contract.BRIDGE},
            haptic: function (ms) { send({ type: "haptic", ms: Number(ms) || 0 }); },
            signInRequired: function () { send({ type: "signInRequired" }); },
            appVersion: function () { return ${JSONObject.quote(appVersion)}; },
            setTheme: function (name) { send({ type: "setTheme", name: String(name) }); },
            listen: function () { send({ type: "listen" }); },
            openServer: function () { send({ type: "open", screen: "server" }); },
            openVoice: function () { send({ type: "open", screen: "voice" }); },
            openMenu: function () { send({ type: "open", screen: "menu" }); }
          }) });
        })();
    """.trimIndent()

    sealed class Message {
        data class Haptic(val ms: Int) : Message()
        object SignInRequired : Message()
        data class SetTheme(val name: String) : Message()
        /** The page's Ask button: listen for one spoken question and send it to /api/ask. */
        object Listen : Message()
        /** The page's Settings opening one of the app's own screens (they return to the page when left). */
        data class Open(val screen: AppScreen) : Message()
    }

    enum class AppScreen { SERVER, VOICE, MENU }

    /** The longest vibration a page can ask for: rail presses want a short tick, not a buzz. */
    const val MAX_HAPTIC_MS = 100

    /** Null for anything malformed or unknown (a newer page talking to an older app). */
    fun parse(data: String?): Message? {
        if (data == null) return null
        val o = try {
            JSONObject(data)
        } catch (e: JSONException) {
            return null
        }
        return when (o.optString("type")) {
            "haptic" -> {
                val ms = o.optDouble("ms", 0.0)
                if (ms.isNaN() || ms <= 0) null else Message.Haptic(ms.toInt().coerceIn(1, MAX_HAPTIC_MS))
            }
            "signInRequired" -> Message.SignInRequired
            "listen" -> Message.Listen
            "open" -> when (o.optString("screen")) {
                "server" -> Message.Open(AppScreen.SERVER)
                "voice" -> Message.Open(AppScreen.VOICE)
                "menu" -> Message.Open(AppScreen.MENU)
                else -> null
            }
            "setTheme" -> o.optString("name").trim().lowercase().takeIf { it.isNotEmpty() && it.length <= 40 }
                ?.let { Message.SetTheme(it) }
            else -> null
        }
    }
}
