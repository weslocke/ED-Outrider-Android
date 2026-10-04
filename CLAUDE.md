# Claude Code instructions

ED Outrider for Android: a client for [ED Outrider](https://github.com/weslocke/ED-Outrider). A single-activity
WebView shell around Outrider's `/tablet` page, plus the native parts a page can't do (sign-in, reconnecting, the
wake word and spoken questions, landscape and screen-on). The README describes the contract with Outrider and the
page <-> app bridge; the design history is in the author's (unpublished) Outrider planning notes,
`project/PLAN-tablet-2026-10-02.md`.

## Code map

`app/src/main/java/io/github/weslocke/outrider/`:

- `MainActivity.kt`: the flow (connect → version check → sign-in / update / page), the WebView and its guards, the
  Back menu, the Voice screen, and when the wake word runs (`voiceIdle`).
- `Contract.kt`: contract versions, `/api/version`, `/api/ask` and error parsing, version compatibility.
- `OutriderApi.kt`: the app's own HTTP calls (blocking; run off the main thread).
- `ServerAddress.kt`: parsing the typed address, and `isSameOrigin`, the guard on everything loaded or called.
- `Bridge.kt`: `window.OutriderApp` (a document-start script + an origin-restricted web message listener).
- `Prefs.kt`: what the app keeps (address, token, theme, wake-word settings); never the password.
- `Ui.kt`, `AppTheme.kt`: the app's own screens, coloured and framed by the page's theme (lcars / elite / babylon5 /
  narn / sith / alliance, the same palettes as Outrider's `static/themes/`).
- `Voice.kt`: one spoken question through Android's speech recognizer, and its pure helpers (`Speech`).
- `WakeWord.kt`: the wake word ("OK / Okay / Hey / Hello <word>") on sherpa-onnx's keyword spotter, and the pure
  `WakePhrases` (phrase list, sensitivity, word checks).
- `Bpe.kt`: splits the configured word into the model's pieces. The model's "bpe.model" is really a sentencepiece
  UNIGRAM model (Viterbi); tested against sentencepiece's own output in `src/test/resources/bpe_gold.json`.

Elsewhere:

- `app/build.gradle.kts`: the `fetchVoice` task downloads the sherpa-onnx AAR and the keyword-spotting model into
  `app/voice/` (git-ignored, SHA-256 pinned) before every build; release signing from `~/.android/`.
- `tools/fake_outrider.py`: a stand-in Outrider (contract, `/api/ask`, a test page) on port 8026.
- `design/launcher-icon.svg`: the icon's source; `app/src/main/res/drawable/ic_launcher_*.xml` are hand-converted
  from it (keep them in step).
- `docs/images/`: README images.

## Rules

- `./gradlew testDebugUnitTest assembleDebug` must pass. Pure logic lives outside Android classes so it can be
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
