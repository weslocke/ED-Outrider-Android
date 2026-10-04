package io.github.weslocke.outrider

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * One activity: Outrider's /tablet page in a WebView, with the app's own screens over it when there is no page
 * to show (first-run settings, sign-in, no link, update needed) and a menu on Back.
 *
 * Flow: [connect] asks GET /api/version; the answer leads to an update screen, the sign-in screen or [showPage].
 * The page's own errors (load failure, 401, a lost session reported over the bridge) come back to [connect].
 */
class MainActivity : ComponentActivity() {

    private enum class State { SETUP, VOICE, CONNECTING, NO_LINK, UPDATE, SIGN_IN, PAGE, MENU }

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var webHost: FrameLayout

    private var state = State.CONNECTING
    private var screen: Screen? = null

    private var web: WebView? = null
    private var webAddress: ServerAddress? = null
    /** False until /tablet has loaded without a main-frame error since the last session change. */
    private var pageLoaded = false

    private var lastVersion: VersionInfo? = null

    /** Short calls: version, sign-in, sign-out. */
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    /** /api/ask alone: it may wait up to 45 s on Outrider's AI layer, and must not hold up reconnecting. */
    private val askIo: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    /** Bumped by every [connect]; a network answer for an older generation is dropped. */
    private var generation = 0
    /** Bumped when a question is cancelled or the Outrider changes: an older answer only clears its overlay. */
    private var askGeneration = 0
    /** Set in onDestroy: answers still on their way must not touch the finished activity. */
    private var destroyed = false
    /** When the page last reported 401s that /api/version then contradicted (the reload-loop guard). */
    private val pageUnauthorized = ArrayDeque<Long>()
    /** Page events that arrived while the user was typing on one of the app's screens, acted on when they leave. */
    private var pendingSignIn = false
    private var pendingReconnect = false

    // asking by voice: the wake word (or the Ask button) starts one spoken question, its progress in a small
    // overlay over the page; while the wake word listens, a small indicator shows it
    private lateinit var listener: Listener
    private lateinit var wake: WakeWord
    private lateinit var voiceOverlay: TextView
    private lateinit var wakeIndicator: TextView
    private val hideOverlay = Runnable { voiceOverlay.visibility = View.GONE }
    /** True from the wake word or Ask until the answer is shown: the wake word waits meanwhile. */
    private var asking = false
    /** Why the wake word couldn't start (model, microphone): not retried until the next resume or a Voice save. */
    private var wakeFailed: String? = null
    /** The overlay is showing "microphone blocked": a tap opens Android's settings for the app. */
    private var overlayOpensSettings = false
    private var tone: android.media.ToneGenerator? = null
    /** Android's text-to-speech, made on first use: answers Outrider couldn't speak on the PC. */
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var resumed = false
    /** What to do once the microphone permission is answered. */
    private var afterMic: (Boolean) -> Unit = {}
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { afterMic(it) }
    /** The model's word pieces and vocabulary, for turning the wake word into keywords; null if the model is missing. */
    private val wakeModel: Pair<Bpe, Set<String>>? by lazy {
        try {
            val bpe = assets.open("kws/bpe.model").use { Bpe.load(it) }
            val tokens = assets.open("kws/tokens.txt").bufferedReader().readLines().map { it.substringBefore(' ') }.toSet()
            bpe to tokens
        } catch (e: Exception) {
            Log.e(TAG, "wake-word model missing", e)
            null
        }
    }

    private var retryIndex = 0
    private var retryAt = 0L
    private var noLinkReason = ""

    /** versionName, e.g. "1.0.0" ("1.0.0-debug" for debug builds). Read from the package: no BuildConfig, so the
     *  build needs no Java compiler (this PC has Java runtimes only). */
    private val appVersion by lazy { packageManager.getPackageInfo(packageName, 0).versionName ?: "0" }
    private val debuggable by lazy { applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webHost = FrameLayout(this)
        root.addView(webHost, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        listener = Listener(this, ::onListen)
        wake = WakeWord(assets, ::onWakeEvent)
        voiceOverlay = makeVoiceOverlay()
        root.addView(voiceOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(96) })
        wakeIndicator = makeWakeIndicator()
        root.addView(wakeIndicator, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(20); bottomMargin = dp(16) })

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        // System bars stay hidden; the keyboard is the only inset, and the page or form shrinks above it.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack(this)
        })

        if (prefs.address == null) showSetup() else connect()
        debugAsk(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugAsk(intent)
    }

    /**
     * Debug builds only: `adb shell "am start -n <pkg>/io.github.weslocke.outrider.MainActivity --es debug_ask 'how much fuel'"`
     * (the inner quotes keep the spaces: adb runs it through the tablet's shell)
     * sends that text as if it had been heard, to test /api/ask without a voice. Release builds ignore it.
     */
    private fun debugAsk(intent: Intent?) {
        val text = intent?.getStringExtra("debug_ask") ?: return
        intent.removeExtra("debug_ask")
        if (debuggable) main.postDelayed({ ask(text) }, 1500)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        wakeFailed = null   // the microphone may be free again
        updateKeepScreenOn()
        voiceIdle()
        hideSystemBars()
        web?.onResume()
        web?.resumeTimers()
        if (state == State.NO_LINK) connect(quiet = true)
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(retryTick)   // no probing in the background; onResume reconnects from No link
        stopWake()
        if (asking) cancelAsk()
        tts?.stop()
        // The page's long poll stops while the app is away; it resumes and rebaselines on its own.
        web?.onPause()
        web?.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        generation++
        askGeneration++
        listener.cancel()
        wake.release()
        tone?.release()
        tts?.shutdown()
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        askIo.shutdownNow()
        web?.destroy()
        web = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun onBack(callback: OnBackPressedCallback) {
        when (state) {
            State.PAGE -> showMenu()
            State.MENU, State.VOICE -> backToPage()
            State.SETUP -> if (prefs.address != null) connect() else leave(callback)
            else -> leave(callback)
        }
    }

    /** Runs [block] on [executor], or nothing once the activity is finishing (no RejectedExecutionException). */
    private fun background(executor: ExecutorService, block: () -> Unit) {
        if (destroyed) return
        try {
            executor.execute(block)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "background work after shutdown dropped")
        }
    }

    /** Posts [block] to the main thread unless the activity has been destroyed by then. */
    private fun onMain(block: () -> Unit) {
        main.post { if (!destroyed) block() }
    }

    /** The app's screens where someone may be typing: page events wait until they leave. */
    private fun typing() = state == State.SETUP || state == State.VOICE || state == State.SIGN_IN

    /** Keep the screen on over the page and while (re)connecting; let it sleep on the app's settings screens. */
    private fun updateKeepScreenOn() {
        val on = state == State.PAGE || state == State.CONNECTING || state == State.NO_LINK
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun leave(callback: OnBackPressedCallback) {
        callback.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
        callback.isEnabled = true
    }

    // ---- connecting ---------------------------------------------------------------------------------------

    /** Asks /api/version and goes wherever the answer leads. [quiet] keeps the current screen while it asks. */
    private fun connect(quiet: Boolean = false) {
        if (destroyed) return
        val address = prefs.address ?: return showSetup()
        val gen = ++generation
        main.removeCallbacks(retryTick)
        if (state == State.NO_LINK) screen?.setBody(noLinkBody(address, "Trying now…"))
        else if (!quiet) show(State.CONNECTING, Screen.Spec(
            title = "Connecting",
            body = "Looking for Outrider at ${address.display}…",
            buttons = listOf(Screen.Button("Settings", primary = false) { showSetup() }),
        ))
        val api = OutriderApi(address, prefs.token, appVersion)
        background(io) {
            val result = api.version()
            onMain { if (gen == generation) onVersion(address, result) }
        }
    }

    private fun onVersion(address: ServerAddress, result: OutriderApi.Result<VersionInfo>) {
        if (result is OutriderApi.Result.Ok) {
            retryIndex = 0
            lastVersion = result.value
            val before = prefs.saved
            prefs.saved = Servers.remember(before, address, result.value.gamePc)
            // one that fell off the list of four is forgotten entirely, its session too
            for (gone in before.map { it.address } - prefs.saved.map { it.address }.toSet()) prefs.forgetSessionOf(gone.origin)
        }
        when (val step = Flow.afterVersion(address, result, prefs.token != null, appVersion)) {
            Flow.Step.Page -> {
                if (result is OutriderApi.Result.Ok && !result.value.signedIn && !result.value.password) pageUnauthorized.clear()
                showPage()
            }
            is Flow.Step.SignIn -> {
                if (step.forgetToken) forgetSession()
                showSignIn(step.note)
            }
            is Flow.Step.Update -> showUpdate(step.app, step.reason)
            is Flow.Step.NoLink -> showNoLink(step.reason)
        }
    }

    // ---- the page -------------------------------------------------------------------------------------------

    private fun showPage(reload: Boolean = false) {
        val address = prefs.address ?: return showSetup()
        if (web == null || webAddress != address) createWebView(address)
        hideScreen()
        state = State.PAGE
        updateKeepScreenOn()
        if (reload || !pageLoaded) {
            pageLoaded = true
            restoreCookies(address)
            web?.loadUrl(address.url("/tablet"))
        }
        askMicOnce()
        voiceIdle()
    }

    private fun backToPage() {
        when {
            // something the page said while the user was typing on one of the app's screens
            pendingSignIn -> {
                pendingSignIn = false
                pendingReconnect = false
                forgetSession()
                showSignIn("Outrider asked this tablet to sign in again.")
            }
            pendingReconnect -> {
                pendingReconnect = false
                connect()
            }
            web != null && pageLoaded -> {
                hideScreen()
                state = State.PAGE
                updateKeepScreenOn()
            }
            else -> connect()
        }
    }

    /**
     * Puts this Outrider's own sign-in cookie back before its page loads: cookies are per host, so two Outriders on
     * one PC (different ports) would otherwise overwrite each other's and loop on 401s.
     */
    private fun restoreCookies(address: ServerAddress) {
        val lines = prefs.cookieLines
        if (lines.isEmpty()) return
        val cookies = CookieManager.getInstance()
        for (line in lines) cookies.setCookie(address.origin + "/", line)
        cookies.flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(address: ServerAddress) {
        destroyWebView()
        WebView.setWebContentsDebuggingEnabled(debuggable) // chrome://inspect on the PC
        val w = WebView(this)
        w.setBackgroundColor(Color.BLACK)
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // the page keeps per-device settings in localStorage
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = "$userAgentString OutriderApp/$appVersion"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        installBridge(w, address)
        w.webViewClient = PageClient(address)
        w.webChromeClient = WebChromeClient() // JS alert/confirm dialogs
        w.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(address, url, userAgent, contentDisposition, mimeType)
        }
        webHost.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // pauseTimers() is process-wide: a WebView made while resumed must not inherit a pause from an older one
        if (resumed) w.resumeTimers()
        web = w
        webAddress = address
        pageLoaded = false
    }

    private fun destroyWebView() {
        web?.let {
            webHost.removeView(it)
            it.destroy()
        }
        web = null
        webAddress = null
        pageLoaded = false
    }

    private fun installBridge(w: WebView, address: ServerAddress) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            Log.w(TAG, "WebView too old for the bridge; the page runs without window.OutriderApp")
            return
        }
        val rules = setOf(address.origin)
        WebViewCompat.addWebMessageListener(w, Bridge.NATIVE_OBJECT, rules) { _, message, sourceOrigin, isMainFrame, _ ->
            if (isMainFrame && address.isSameOrigin(sourceOrigin.toString())) onBridge(Bridge.parse(message.data))
        }
        WebViewCompat.addDocumentStartJavaScript(w, Bridge.script(appVersion), rules)
    }

    private fun onBridge(message: Bridge.Message?) {
        when (message) {
            is Bridge.Message.Haptic -> vibrate(message.ms)
            Bridge.Message.SignInRequired -> {
                // The page keeps polling under the sign-in screen and asks again on every 401: rebuilding the
                // screen each time would wipe a half-typed password.
                if (state == State.SIGN_IN) return
                if (typing()) {
                    pendingSignIn = true
                    return
                }
                forgetSession()
                showSignIn("Outrider asked this tablet to sign in again.")
            }
            is Bridge.Message.SetTheme -> prefs.theme = message.name
            Bridge.Message.Listen -> if (state == State.PAGE) startAsk()
            null -> {}
        }
    }

    private inner class PageClient(private val address: ServerAddress) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (address.isSameOrigin(url.toString())) return false
            // Anything else leaves the app: web links open in the browser, other schemes are dropped.
            if (url.scheme == "http" || url.scheme == "https") try {
                startActivity(Intent(Intent.ACTION_VIEW, url))
            } catch (e: ActivityNotFoundException) {
                toast("No browser to open $url")
            }
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || view !== web) return
            pageLoaded = false
            if (typing()) {
                pendingReconnect = true
                return
            }
            showNoLink(when (error.errorCode) {
                ERROR_CONNECT -> OutriderApi.reason(java.net.ConnectException())
                ERROR_TIMEOUT -> OutriderApi.reason(java.net.SocketTimeoutException())
                ERROR_HOST_LOOKUP -> OutriderApi.reason(java.net.UnknownHostException())
                else -> "The page didn't load (${error.description})."
            })
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (!request.isForMainFrame || view !== web) return
            val now = SystemClock.elapsedRealtime()
            while (pageUnauthorized.isNotEmpty() && now - pageUnauthorized.first() > Flow.UNAUTHORIZED_WINDOW_MS) pageUnauthorized.removeFirst()
            val step = Flow.afterPageHttpError(address, request.url.toString(), response.statusCode, pageUnauthorized.size)
            if (step is Flow.PageError.Notice) {
                // a link the page followed, not the tablet page: say so and go back to the page
                toast(step.text)
                if (view.canGoBack()) view.goBack() else showPage(reload = true)
                return
            }
            pageLoaded = false
            if (typing()) {
                pendingReconnect = true
                return
            }
            when (step) {
                Flow.PageError.Reconnect -> {
                    pageUnauthorized.addLast(now)
                    connect() // the session is gone: /api/version says so and sign-in follows
                }
                is Flow.PageError.SignIn -> {
                    pageUnauthorized.clear()
                    forgetSession()
                    showSignIn(step.note)
                }
                is Flow.PageError.Update -> showUpdate(app = false, step.reason)
                is Flow.PageError.NoLink -> showNoLink(step.reason)
                is Flow.PageError.Notice -> {}
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            if (view === web) {
                destroyWebView()
                if (state == State.PAGE) showPage()
            } else view.destroy()
            return true
        }
    }

    private fun download(address: ServerAddress, url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        if (!address.isSameOrigin(url)) {
            toast("This file can't be saved from the app yet")
            return
        }
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            prefs.token?.let { request.addRequestHeader("Authorization", "Bearer $it") }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            else request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
            getSystemService(DownloadManager::class.java).enqueue(request)
            toast("Saving $name to Downloads")
        } catch (e: RuntimeException) {
            toast("Couldn't save $name: ${e.message}")
        }
    }

    private fun vibrate(ms: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        if (vibrator?.hasVibrator() != true) return
        vibrator.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ---- session --------------------------------------------------------------------------------------------

    /** Drops the current Outrider's token and session cookie; other saved Outriders stay signed in. */
    private fun forgetSession() {
        prefs.token = null
        prefs.cookieLines = emptyList()
        prefs.address?.let { address ->
            // CookieManager can't remove one site's cookies, so expire each of them by name
            val cookies = CookieManager.getInstance()
            val url = address.origin + "/"
            cookies.getCookie(url)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach {
                cookies.setCookie(url, "$it=; Max-Age=0; Path=/")
            }
            cookies.flush()
        }
        pageLoaded = false
    }

    /** Goes to another saved Outrider: its own session (if any) comes with it. */
    private fun switchTo(address: ServerAddress) {
        generation++
        askGeneration++   // an answer for the old Outrider must not act on the new one
        pageUnauthorized.clear()
        pendingSignIn = false
        pendingReconnect = false
        destroyWebView()
        lastVersion = null
        prefs.address = address
        connect()
    }

    /** Buttons for the other saved Outriders; a long press forgets one. */
    private fun savedChoices(): List<Screen.Button> {
        val current = prefs.address
        return prefs.saved.filter { it.address != current }.map { s ->
            Screen.Button(s.label, primary = false, onLongClick = {
                forgetOutrider(s.address)
                toast("Forgot ${s.address.display}")
                // refresh the row in place: rebuilding No link would also restart its retry countdown
                screen?.setChoices(savedChoices())
            }) { switchTo(s.address) }
        }
    }

    /** Forgets a saved Outrider and its session; its cookies go too unless another Outrider shares the host. */
    private fun forgetOutrider(address: ServerAddress) {
        prefs.saved = Servers.forget(prefs.saved, address)
        prefs.forgetSessionOf(address.origin)
        val sharesHost = prefs.address?.host == address.host || prefs.saved.any { it.address.host == address.host }
        if (!sharesHost) {
            val cookies = CookieManager.getInstance()
            val url = address.origin + "/"
            cookies.getCookie(url)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach {
                cookies.setCookie(url, "$it=; Max-Age=0; Path=/")
            }
            cookies.flush()
        }
    }

    private fun signIn(address: ServerAddress, password: String) {
        if (password.isEmpty()) {
            screen?.setError("Enter the password")
            return
        }
        screen?.setError(null)
        screen?.setBody("Signing in…")
        val gen = ++generation
        val api = OutriderApi(address, null, appVersion)
        background(io) {
            val result = api.signIn(password)
            onMain { if (gen == generation) onSignIn(address, result) }
        }
    }

    private fun onSignIn(address: ServerAddress, result: OutriderApi.Result<String>) {
        screen?.setBody(signInBody(address))
        when (result) {
            is OutriderApi.Result.Ok -> {
                prefs.token = result.value
                prefs.cookieLines = result.setCookies
                pageUnauthorized.clear()
                val cookies = CookieManager.getInstance()
                result.setCookies.forEach { cookies.setCookie(address.origin + "/", it) }
                cookies.flush()
                pageLoaded = false
                connect()
            }
            is OutriderApi.Result.Failed -> when (result.error.code) {
                "bad_password" -> screen?.setError("Wrong password")
                "rate_limited" -> screen?.setError("Too many tries. Wait ${result.error.retryAfter ?: 60} s and try again.")
                "app_too_old" -> showUpdate(app = true, result.error.message)
                else -> screen?.setError(result.error.message)
            }
            is OutriderApi.Result.Unreachable -> screen?.setError("Outrider isn't answering: ${result.reason}")
            is OutriderApi.Result.NotOutrider -> screen?.setError("Something answered, but it isn't Outrider")
        }
    }

    private fun signOut() {
        val address = prefs.address ?: return
        val api = OutriderApi(address, prefs.token, appVersion)
        background(io) { api.signOut() } // best effort: the token is forgotten here either way
        forgetSession()
        showSignIn("Signed out.")
    }

    // ---- the app's own screens ------------------------------------------------------------------------------

    private fun show(newState: State, spec: Screen.Spec): Screen {
        hideKeyboard()
        screen?.let { root.removeView(it.view) }
        main.removeCallbacks(retryTick)
        val s = Screen(this, AppTheme.named(prefs.theme), spec)
        root.addView(s.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        screen = s
        state = newState
        updateKeepScreenOn()
        voiceIdle()   // the wake word listens over the page only
        return s
    }

    private fun hideScreen() {
        hideKeyboard()
        main.removeCallbacks(retryTick)
        screen?.let { root.removeView(it.view) }
        screen = null
        main.post { voiceIdle() }   // after the caller has set the new state
    }

    private fun showSetup() {
        generation++ // drop any answer still on its way
        val current = prefs.address
        lateinit var s: Screen
        val save = save@{
            when (val parsed = ServerAddress.parse(s.field?.text?.toString() ?: "")) {
                is ServerAddress.Parsed.Invalid -> s.setError(parsed.reason)
                is ServerAddress.Parsed.Ok -> {
                    // each Outrider keeps its own session, so changing address signs nothing out
                    if (parsed.address != current) switchTo(parsed.address) else connect()
                }
            }
        }
        val buttons = mutableListOf(Screen.Button("Connect") { save() })
        if (current != null) buttons += Screen.Button("Cancel", primary = false) { connect() }
        s = show(State.SETUP, Screen.Spec(
            title = "Outrider's address",
            body = "The address of the computer running Outrider on your network (your gaming PC, or another machine), e.g. 192.168.1.20. " +
                "Outrider uses port ${ServerAddress.DEFAULT_PORT}; for another port add it: 192.168.1.20:8100.",
            field = Screen.Field(hint = "192.168.1.20", text = current?.display ?: "", onDone = { save() }),
            buttons = buttons,
            choices = savedChoices(),
            choicesLabel = "Or connect to one used before (a long press forgets it):",
            footer = "ED Outrider for Android $appVersion",
        ))
        focusField(s)
    }

    private fun signInBody(address: ServerAddress) =
        "Outrider at ${address.display} asks for a password: the one set as [server] password in Outrider's ed_outrider.toml. " +
            "This tablet stays signed in until that password changes."

    private fun showSignIn(note: String?) {
        val address = prefs.address ?: return showSetup()
        generation++
        lateinit var s: Screen
        val submit = { signIn(address, s.field?.text?.toString() ?: "") }
        s = show(State.SIGN_IN, Screen.Spec(
            title = "Sign in",
            body = signInBody(address),
            field = Screen.Field(hint = "Password", password = true, onDone = { submit() }),
            error = note,
            buttons = listOf(
                Screen.Button("Sign in") { submit() },
                Screen.Button("Settings", primary = false) { showSetup() },
            ),
            choices = savedChoices(),
            choicesLabel = "Or switch to:",
            footer = footer(),
        ))
        focusField(s)
    }

    private fun showUpdate(app: Boolean, reason: String) {
        show(State.UPDATE, Screen.Spec(
            title = if (app) "Update the app" else "Update Outrider",
            body = reason + "\n\n" + if (app) "Install the newest ED Outrider APK on this tablet." else "Update Outrider and restart it.",
            buttons = listOf(
                Screen.Button("Try again") { connect() },
                Screen.Button("Settings", primary = false) { showSetup() },
            ),
            footer = footer(),
        ))
    }

    private fun noLinkBody(address: ServerAddress, next: String) =
        "Outrider isn't answering at ${address.display}.\n$noLinkReason\n\n" +
            "Check that Outrider is running, that this tablet is on the same network, and that Outrider " +
            "listens on the network (host 0.0.0.0), not only on 127.0.0.1.\n\n$next"

    private fun showNoLink(reason: String) {
        val address = prefs.address ?: return showSetup()
        noLinkReason = reason
        val delay = RETRY_SECONDS[minOf(retryIndex, RETRY_SECONDS.size - 1)]
        retryIndex++
        retryAt = SystemClock.uptimeMillis() + delay * 1000L
        if (state != State.NO_LINK || screen == null) show(State.NO_LINK, Screen.Spec(
            title = "No link",
            body = "",
            buttons = listOf(
                Screen.Button("Retry now") { connect() },
                Screen.Button("Settings", primary = false) { showSetup() },
            ),
            choices = savedChoices(),
            choicesLabel = "Or switch to:",
            footer = footer(),
        ))
        main.removeCallbacks(retryTick)
        retryTick.run()
    }

    private val retryTick = object : Runnable {
        override fun run() {
            val address = prefs.address ?: return
            val left = ceil((retryAt - SystemClock.uptimeMillis()) / 1000.0).toInt()
            if (left <= 0) {
                connect(quiet = true)
                return
            }
            screen?.setBody(noLinkBody(address, "Trying again in $left s."))
            main.postDelayed(this, 250)
        }
    }

    private fun showMenu() {
        val buttons = mutableListOf(
            Screen.Button("Back to Outrider") { backToPage() },
            // through showPage: it recreates the WebView if its renderer died while the menu was up
            Screen.Button("Reload", primary = false) { showPage(reload = true) },
            Screen.Button("Settings", primary = false) { showSetup() },
        )
        buttons += Screen.Button("Ask", primary = false) {
            backToPage()
            if (state == State.PAGE) startAsk()
        }
        buttons += Screen.Button("Voice", primary = false) { showVoice() }
        if (lastVersion?.password == true && prefs.token != null) buttons += Screen.Button("Sign out", primary = false) { signOut() }
        show(State.MENU, Screen.Spec(
            title = "ED Outrider",
            body = "Connected to ${prefs.address?.display}." +
                if (lastVersion?.gamePc == false) "\nOutrider on a server: no game buttons (they need Outrider on the game PC)." else "",
            buttons = buttons,
            choices = savedChoices(),
            choicesLabel = "Switch to (a long press forgets one):",
            footer = footer(),
        ))
    }

    private fun footer(): String {
        val v = lastVersion
        val outrider = if (v != null) "Outrider ${v.outrider} (app contract ${v.api})" else "Outrider not reached yet"
        val dm = resources.displayMetrics
        val viewport = "screen ${(dm.widthPixels / dm.density).toInt()} × ${(dm.heightPixels / dm.density).toInt()} dp"
        return "$outrider · app $appVersion · $viewport"
    }

    private fun focusField(s: Screen) {
        val f = s.field ?: return
        f.requestFocus()
        f.setSelection(f.text.length)
        f.post { WindowInsetsControllerCompat(window, f).show(WindowInsetsCompat.Type.ime()) }
    }

    private fun hideKeyboard() {
        val focus = currentFocus ?: return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focus.windowToken, 0)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // ---- tap-to-ask --------------------------------------------------------------------------------------------

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun micState() = Speech.micState(micGranted(), prefs.micAsked,
        shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))

    private fun openAppSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: ActivityNotFoundException) {
            toast("Open Android's Settings → Apps → ED Outrider → Permissions")
        }
    }

    private fun requestMic(then: (Boolean) -> Unit) {
        prefs.micAsked = true
        afterMic = then
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** The first time the page shows with the wake word on, ask for the microphone (once; Voice can ask again). */
    private fun askMicOnce() {
        if (prefs.wakeEnabled && !micGranted() && !prefs.micAsked) {
            prefs.micAsked = true
            requestMic { voiceIdle() }
        }
    }

    /** Listens for one question (asking for the microphone the first time), then sends it to /api/ask. */
    private fun startAsk() {
        when (micState()) {
            Speech.Mic.BLOCKED -> return showOverlay("The microphone is blocked for ED Outrider: tap here for Android's settings",
                alert = true, opensSettings = true)
            Speech.Mic.ASKABLE -> return requestMic { granted ->
                if (granted) startAsk() else showOverlay("The microphone isn't allowed, so the tablet can't listen", alert = true)
            }
            Speech.Mic.GRANTED -> {}
        }
        if (asking) return cancelAsk()   // a second tap (or press) cancels
        stopWake()   // the recognizer needs the microphone
        asking = true
        askGeneration++
        tts?.stop()
        // "LISTENING" only once the recognizer is really ready (Listener.Event.Ready): words before it are lost
        showOverlay("One moment…", hold = true)
        listener.start()
    }

    /** The one way a question ends early: the overlay tap, a second Ask, leaving the app, a recognizer failure. */
    private fun cancelAsk() {
        listener.cancel()
        askGeneration++   // an answer still on its way only tidies up
        asking = false
        voiceOverlay.visibility = View.GONE
        main.removeCallbacks(hideOverlay)
        voiceIdle()
    }

    private fun onWake() {
        if (!resumed) return   // a match posted just before the app went to the background
        if (state != State.PAGE || asking) return voiceIdle()
        startAsk()
    }

    private fun onWakeEvent(event: WakeWord.Event) {
        when (event) {
            WakeWord.Event.Listening -> if (resumed && state == State.PAGE && !asking) showWakeIndicator()
            is WakeWord.Event.Heard -> onWake()
            is WakeWord.Event.Failed -> {
                wakeFailed = event.reason
                wakeIndicator.visibility = View.GONE
                if (state == State.PAGE) showOverlay("Wake word off: ${event.reason}", alert = true)
            }
        }
    }

    private fun onListen(event: Listener.Event) {
        when (event) {
            Listener.Event.Ready -> {
                if (prefs.wakeTone) playTone()
                showOverlay("LISTENING…", hold = true)
            }
            is Listener.Event.Partial -> showOverlay("“${event.text}”", hold = true)
            is Listener.Event.Heard -> ask(event.text)
            is Listener.Event.Failed -> {
                cancelAsk()
                showOverlay(event.reason, alert = true)
            }
        }
    }

    /** A short, quiet two-note tone: "listening now" (the tablet has no vibration motor for a buzz). */
    private fun playTone() {
        try {
            val t = tone ?: android.media.ToneGenerator(android.media.AudioManager.STREAM_SYSTEM, 40).also { tone = it }
            t.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 150)
        } catch (e: RuntimeException) {
            Log.w(TAG, "no tone", e)
        }
    }

    /** Reads [text] aloud on the tablet (an answer Outrider couldn't speak on the PC), then resumes the wake word. */
    private fun speakHere(text: String) {
        val existing = tts
        if (existing != null && ttsReady) {
            stopWake()   // don't listen to ourselves
            existing.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "answer")
            return
        }
        if (existing != null) return   // still starting: this answer stays on screen only
        tts = android.speech.tts.TextToSpeech(this) { status ->
            ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
            if (!ttsReady) return@TextToSpeech
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = onMain { voiceIdle() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = onMain { voiceIdle() }
            })
            stopWake()
            tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "answer")
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // a headset or Bluetooth play/pause button asks (or cancels), when that's switched on in Voice
        val media = event.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            event.keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK ||
            event.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY ||
            event.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
        if (media && prefs.mediaButton && state == State.PAGE) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) startAsk()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Starts or stops the wake word to match the moment: on over the page, while on screen, not mid-question. */
    private fun voiceIdle() {
        val model = wakeModel
        val speaking = tts?.isSpeaking == true
        val want = resumed && state == State.PAGE && prefs.wakeEnabled && !asking && !listener.active && micGranted() &&
            model != null && wakeFailed == null && !speaking
        if (want && !wake.active) {
            // the indicator appears on WakeWord.Event.Listening, once the microphone really delivers audio
            wake.start(WakePhrases.keywords(model!!.first, prefs.wakeWord, prefs.wakeSensitivity))
        } else if (!want) stopWake()
    }

    private fun showWakeIndicator() {
        run {
            val theme = AppTheme.named(prefs.theme)
            val modern = theme.frame == AppTheme.Frame.CARD   // plain sans-serif, as typed; the others condensed caps
            wakeIndicator.text = "◉ " + if (modern) prefs.wakeWord.trim() else Bpe.clean(prefs.wakeWord)
            wakeIndicator.typeface = if (modern) android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                else android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            wakeIndicator.setTextColor(theme.accent)
            wakeIndicator.visibility = View.VISIBLE
        }
    }

    private fun stopWake() {
        if (wake.active) wake.stop()
        wakeIndicator.visibility = View.GONE
    }

    /** The Voice screen's choices while it's open (applied by Save). */
    private data class VoiceDraft(
        val enabled: Boolean, val sensitivity: WakePhrases.Sensitivity, val word: String,
        val tone: Boolean, val speakHere: Boolean, val mediaButton: Boolean,
    )

    /** The Voice screen: the wake word on or off, the word itself, how readily it triggers, and the voice extras. */
    private fun showVoice(draft: VoiceDraft = VoiceDraft(prefs.wakeEnabled, prefs.wakeSensitivity, prefs.wakeWord,
        prefs.wakeTone, prefs.speakHere, prefs.mediaButton)) {
        lateinit var s: Screen
        val typed = { s.field?.text?.toString()?.trim() ?: draft.word }
        val again = { d: VoiceDraft -> showVoice(d.copy(word = typed())) }
        val save = {
            val model = wakeModel
            val problem = if (model == null) "The wake-word model is missing from this build" else WakePhrases.problem(model.first, model.second, typed())
            if (problem != null && draft.enabled) s.setError(problem)
            else {
                if (problem == null) prefs.wakeWord = typed()
                prefs.wakeEnabled = draft.enabled
                prefs.wakeSensitivity = draft.sensitivity
                prefs.wakeTone = draft.tone
                prefs.speakHere = draft.speakHere
                prefs.mediaButton = draft.mediaButton
                wakeFailed = null
                stopWake()   // restart with the new phrases
                if (draft.enabled && micState() == Speech.Mic.ASKABLE) requestMic { backToPage() } else backToPage()
            }
        }
        val mic = when (micState()) {
            Speech.Mic.GRANTED -> ""
            Speech.Mic.ASKABLE -> "\n\nThe microphone isn't allowed yet: Save asks for it."
            Speech.Mic.BLOCKED -> "\n\nThe microphone is blocked for ED Outrider: allow it in Android's settings."
        }
        val onOff = { b: Boolean -> if (b) "on" else "off" }
        val buttons = mutableListOf(
            Screen.Button("Save") { save() },
            Screen.Button("Wake word: " + onOff(draft.enabled), primary = false) { again(draft.copy(enabled = !draft.enabled)) },
            Screen.Button("Sensitivity: " + draft.sensitivity.label, primary = false) { again(draft.copy(sensitivity = draft.sensitivity.next())) },
            Screen.Button("Tone: " + onOff(draft.tone), primary = false) { again(draft.copy(tone = !draft.tone)) },
            Screen.Button("Speak answers here: " + onOff(draft.speakHere), primary = false) { again(draft.copy(speakHere = !draft.speakHere)) },
            Screen.Button("Media button: " + onOff(draft.mediaButton), primary = false) { again(draft.copy(mediaButton = !draft.mediaButton)) },
        )
        if (micState() == Speech.Mic.BLOCKED) buttons += Screen.Button("Open Android settings", primary = false) { openAppSettings() }
        buttons += Screen.Button("Cancel", primary = false) { backToPage() }
        s = show(State.VOICE, Screen.Spec(
            title = "Voice",
            body = "Say OK, Hey or Hello and the wake word, wait for LISTENING, then ask: a status report, fuel, " +
                "unsold, the next jump, what's left here, the nearest unvisited system, hush or unhush. Outrider " +
                "answers out loud in its own voice; when it can't (no PC window speaking), \"Speak answers here\" " +
                "reads the answer on the tablet.\n\nThe tablet listens for the wake word only while ED Outrider is on " +
                "screen, on the tablet itself; only the question you ask after it goes on to be understood. " +
                "\"Media button\" lets a headset's play/pause button ask instead." + mic,
            field = Screen.Field(hint = WakePhrases.DEFAULT_WORD, text = draft.word, onDone = { save() }),
            buttons = buttons,
            footer = "Higher sensitivity hears you through more game noise, and mistakes other words for it more often.",
        ))
    }

    private fun ask(text: String) {
        val address = prefs.address ?: return
        showOverlay("“$text” · asking Outrider…", hold = true)
        val api = OutriderApi(address, prefs.token, appVersion)
        val gen = askGeneration
        background(askIo) {
            val result = api.ask(text)
            onMain {
                if (gen != askGeneration || address != prefs.address) {
                    // cancelled, or the tablet moved to another Outrider meanwhile: only tidy up
                    voiceOverlay.visibility = View.GONE
                    return@onMain
                }
                asking = false
                main.postDelayed({ voiceIdle() }, 1500)   // a moment for the PC to start answering aloud
                when (result) {
                    is OutriderApi.Result.Ok -> {
                        showOverlay(Speech.answerLine(result.value), long = true)
                        if (!result.value.spoken && prefs.speakHere && result.value.answer.isNotBlank()) speakHere(result.value.answer)
                    }
                    is OutriderApi.Result.Failed -> when (result.error.status) {
                        404 -> showOverlay("This Outrider can't answer questions yet: update Outrider", alert = true)
                        401 -> {
                            forgetSession()
                            showSignIn("Outrider asked this tablet to sign in again.")
                        }
                        else -> showOverlay(result.error.message, alert = true)
                    }
                    is OutriderApi.Result.Unreachable -> showOverlay("Outrider isn't answering: ${result.reason}", alert = true)
                    is OutriderApi.Result.NotOutrider -> showOverlay("Something answered, but it isn't Outrider", alert = true)
                }
            }
        }
    }

    /** The overlay: [hold] stays until the next state; otherwise it hides after a few seconds. */
    private fun showOverlay(text: String, hold: Boolean = false, alert: Boolean = false, long: Boolean = false,
                            opensSettings: Boolean = false) {
        overlayOpensSettings = opensSettings
        val theme = AppTheme.named(prefs.theme)
        voiceOverlay.text = text
        voiceOverlay.setTextColor(if (alert) theme.alert else theme.onFill)
        (voiceOverlay.background as GradientDrawable).apply {
            setColor(if (alert) theme.background else theme.primary)
            setStroke(dp(2), if (alert) theme.alert else theme.primary)
        }
        voiceOverlay.visibility = View.VISIBLE
        voiceOverlay.bringToFront()
        main.removeCallbacks(hideOverlay)
        if (!hold) main.postDelayed(hideOverlay, if (long) 9000L else 4500L)
    }

    private fun makeWakeIndicator() = TextView(this).apply {
        visibility = View.GONE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply { setColor(0xB0000000.toInt()); cornerRadius = dp(12).toFloat() }
        contentDescription = "Listening for the wake word"
        setOnClickListener { showVoice() }
    }

    private fun makeVoiceOverlay() = TextView(this).apply {
        visibility = View.GONE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        setPadding(dp(28), dp(14), dp(28), dp(14))
        maxWidth = dp(900)
        background = GradientDrawable().apply { cornerRadius = dp(40).toFloat() }
        // a tap while asking cancels; on "microphone blocked" it opens Android's settings; otherwise it just closes
        setOnClickListener {
            when {
                overlayOpensSettings -> {
                    visibility = View.GONE
                    openAppSettings()
                }
                asking -> cancelAsk()
                else -> visibility = View.GONE
            }
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val TAG = "Outrider"
        val RETRY_SECONDS = intArrayOf(2, 4, 8, 15)
    }
}
