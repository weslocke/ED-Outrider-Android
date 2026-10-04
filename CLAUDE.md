# Claude Code instructions

ED Outrider for Android: a client for [ED Outrider](https://github.com/weslocke/ED-Outrider). A single-activity
WebView shell around Outrider's `/tablet` page, plus the native parts a page can't do (sign-in, reconnecting, the
wake word and spoken questions, landscape and screen-on). The README describes the contract with Outrider and the
page <-> app bridge; the design history is in the author's (unpublished) Outrider planning notes,
`project/PLAN-tablet-2026-10-02.md`.

## Code map

`app/src/main/java/io/github/weslocke/outrider/`:

- `MainActivity.kt`: carries out the flow (connect → version check → sign-in / update / page), the WebView and its
  guards (`shouldInterceptRequest` answers 403 to anything not on the Outrider's origin), the Back menu, the Voice
  screen, and when the wake word runs (`voiceIdle`). Work off the main thread goes through `background()`, answers
  come back through `onMain()`, which drops them after `onDestroy`; `/api/ask` has its own executor.
- `Flow.kt`: the pure decisions: what to show after `/api/version`, and what a page HTTP error means (with the
  401-loop guard).
- `Contract.kt`: contract versions, `/api/version`, `/api/ask` and error parsing, version compatibility.
- `OutriderApi.kt`: the app's own HTTP calls (blocking; run off the main thread).
- `ServerAddress.kt`: parsing the typed address, and `isSameOrigin`, the guard on everything loaded or called.
- `Bridge.kt`: `window.OutriderApp` (a document-start script + an origin-restricted web message listener). Additions
  that a page feature-detects (like `openServer`/`openVoice`/`openMenu` in 1.2) keep `bridgeVersion` 1.
- `Prefs.kt`: what the app keeps (address, saved Outriders, and per Outrider its token, sign-in cookie lines and
  theme; voice settings); never the password.
- `Servers.kt`: the saved Outriders (most recent first, at most 4, game PC or server), the per-Outrider tokens and
  cookies (JSON), and the 1.0.0 token migration.
- `Ui.kt`, `FlowRow.kt`, `AppTheme.kt`: the app's own screens (button rows wrap), coloured and framed by the page's theme (lcars / elite / babylon5 /
  narn / minbari / centauri / sith / alliance / dark, the same palettes as Outrider's `static/themes/`). `ContrastTest`
  holds every theme to WCAG contrast, the accent too, as a line colour (3:1). The emblems are the page's alone.
- `Voice.kt`: one spoken question through Android's speech recognizer (on-device first, online fallback), and its
  pure helpers (`Speech`: error wording, the microphone permission's state, the answer line).
- `WakeWord.kt`: the wake word ("OK / Okay / Hey / Hello <word>") on sherpa-onnx's keyword spotter, one audio thread
  per run token (an older run never reports or keeps the microphone); the pure `AudioReads` (what a read result
  means) and `WakePhrases` (phrase list, sensitivity, word checks).
- `Bpe.kt`: splits the configured word into the model's pieces. The model's "bpe.model" is really a sentencepiece
  UNIGRAM model (Viterbi); tested against sentencepiece's own output in `src/test/resources/bpe_gold.json`.

Elsewhere:

- `app/build.gradle.kts`: the version (the version code is derived from it); the `fetchVoice` task downloads the
  sherpa-onnx AAR and the keyword-spotting model into `app/voice/` (git-ignored, SHA-256 pinned; the extracted model
  is checked against `app/voice/assets/kws/.manifest`) before every build; release signing from `~/.android/` (a
  release without it fails unless `-PallowUnsigned`); APKs copied to `app/build/dist/`. Lint blocks a release on
  anything not in `app/lint-baseline.xml`.
- `app/src/test/`: JVM unit tests (junit 4, the real org.json). `TestHttpServer` is a small socket server for
  `OutriderApiTest` (`com.sun.net.httpserver` isn't on the Android unit-test classpath).
- `tools/fake_outrider.py`: a stand-in Outrider (contract, `/api/ask`, a test page) on port 8026; `--server-mode`,
  `--slow N`, `--unspoken`, `--ask-429`, `--redirect` for the awkward cases, `--theme NAME` for the app's screens, `--selftest` to check itself.
- `design/launcher-icon.svg`: the icon's source; `app/src/main/res/drawable/ic_launcher_*.xml` are hand-converted
  from it (keep them in step).
- `docs/images/`: README images. `themes-rail.webp` and `themes-server.webp` are built from the `rail-*.webp` and
  `server-*.webp` screenshots (Nearby in every theme, from the author's game PC and Docker server) by
  `python3 tools/theme_carousel.py` (Pillow): re-run it after changing one. GitHub shrinks wide tables and strips
  scripts, so an animated image is the only way a README can page through screenshots.

## Rules

- `./gradlew testDebugUnitTest assembleDebug lint` and `python3 tools/fake_outrider.py --selftest` must pass. Pure logic lives outside Android classes so it can be
  unit-tested; every fix gets a test where it can.
- The native-facing contract and the bridge are versioned: change them only together with Outrider (talk to the
  Outrider side first). Ignore unknown fields; never require new ones.
- Never hard-code a PC address. Never load or call anything that fails `ServerAddress.isSameOrigin`.
- Never store the password; only the session token.
- Test against `tools/fake_outrider.py`, or a scratch Outrider on a spare port with copied data. Never use port 8025
  for tests, and never touch the player's running Outrider, its `data/` or `ed_outrider.toml`.
- The release key and its properties file live in `~/.android/`, never in the repo; never print the password.
- No commits, pushes or remotes without the author asking.

## Things that bit before

- Android 16 ignores an app's orientation lock on large screens for targetSdk 36; the manifest's
  `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` opts out. It goes away with targetSdk 37.
- The PC may have Java runtimes only: the foojay resolver and `gradle/gradle-daemon-jvm.properties` give Gradle a
  JDK 21; there is no `BuildConfig` (it would need javac).
- The keyword spotter's phrases compete in one search: `maxActivePaths = 16` (not the default 4) or "OK Vespa" loses
  to "Hey"/"Hello". The "-mobile" KWS model crashes in sherpa-onnx 1.13.8; use the standard one.
- `adb shell am start ... --es debug_ask` needs inner quotes for spaces (adb runs it through the tablet's shell).
- Cookies are per host, not per port: two Outriders on one PC overwrite each other's session cookie, so the app keeps
  each one's cookie lines and puts them back before loading its page.
- Chromium hands back IPv6 hosts compressed and lower-cased (`fd00::20`), and leaves `|`, `^`, `{` unescaped in URLs;
  compare canonical hosts, and don't parse page URLs with `java.net.URI`.
- `HttpURLConnection` with fixed-length streaming lost the body of a 401 answer to a POST; the app doesn't use it.
- A stroked outline in a `Drawable` washed the whole window out on the tablet; draw borders as filled shapes.
- The WebView's no-tap audio rule (`mediaPlaybackRequiresUserGesture`) only ever blocked `<audio>`/`<video>`: Web Audio
  (which Outrider's page uses) ran without a tap even before the app turned the rule off in 1.2.
- `uiautomator dump` doesn't see the app's own screens while the keyboard is up; tap by coordinates from a screenshot.
- `pkill -f <pattern>` also matches the shell running it and kills that; stop the fake Outrider by its PID.
- Never pipe `adb shell run-as <pkg> cat` into another `run-as` write of the same file: it emptied the prefs. Back
  the file up first, edit a local copy, then `cat local | adb shell run-as <pkg> sh -c "'cat > shared_prefs/outrider.xml'"`,
  and put the author's own settings back after testing.
