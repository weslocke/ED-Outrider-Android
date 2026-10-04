# Changelog

## 1.2.0 (unreleased)

- The app's settings are in Outrider's own Settings: a **Tablet app** section (Outrider 2026.10.4 or newer) opens the
  app's Server, Voice and menu screens through three new bridge calls, `openServer()`, `openVoice()` and
  `openMenu()`. Back still opens the menu.
- The app's "Settings" button (the Back menu, Connecting, No link, sign-in, update) is now **Server**, so it isn't
  confused with the page's Settings.
- Cancel or Back on the Server screen returns straight to the page when it is still loaded, instead of reconnecting.
- The page may play any sound without a tap first. Outrider's **Play alerts here** (its alerts spoken on the tablet,
  e.g. when it runs on a server with no PC window open) uses Web Audio, which Android's WebView already allowed;
  `<audio>` elements were blocked until the first touch, and now play too.

## 1.1.0 (2026-10-04)

**New**

- Outrider can run on any computer on the network (a home server, a service, Docker), not just the gaming PC; when it
  says it runs on a server, the Back menu leaves out the game buttons.
- Remembers up to four Outriders (labelled game PC or server) and switches between them from the Back menu, the
  address screen, sign-in or "No link"; each keeps its own sign-in, cookies and theme, and a long press forgets one.
- Four more themes for the app's own screens: Narn, Sith, Alliance and a modern Dark.
- Voice settings: a soft two-note tone when it starts listening (on), answers read out on the tablet when Outrider
  couldn't speak them on the PC (on), and a headset's play/pause button to ask (off).
- A blocked microphone is told apart from one Android can still ask for, with a way to Android's settings.

**Fixed**

- Leaving the app while it was connecting could crash it.
- Tapping LISTENING to cancel stopped the wake word for the rest of the session; a dead audio server could spin the
  CPU; the online speech fallback asked for offline-only recognition.
- Two Outriders on one PC looped on sign-in (cookies are per host); a page 401 that Outrider contradicts no longer
  reloads forever; a slow answer no longer holds up connecting or switching; an answer to an old question or another
  Outrider is ignored.
- A long-form IPv6 address silently disabled the bridge, downloads and same-origin links.
- Android 8-9: exports go to Downloads after asking for storage once (or to the app's own folder, which the message
  names).
- Back on the Voice screen returns to the page; the screen stays on only over the page and while connecting.

**Security and privacy**

- Every request that isn't the configured Outrider is blocked inside the page too (images, scripts, fetch), not only
  navigations and downloads.
- The password field is excluded from autofill; the debug-only question hook answers adb only.

**Accessibility and looks**

- Readable buttons and hints in every theme (WCAG contrast, checked by tests); LCARS colours as in Outrider.
- Screen readers announce each screen's title and status; buttons read as buttons; the long press is named.
- Button rows wrap on narrow screens, fields fit them, and a camera cutout is kept clear.

**Build**

- A release build without the signing key stops instead of producing an unsigned APK under the signed one's name
  (`-PallowUnsigned` on purpose); lint's release-blocking checks are on again.
- APKs are copied to `app/build/dist/`; the version code follows the version (1.1.0 is 10100).
- Downloads of the wake-word engine and model time out and retry; the extracted model is checked against a manifest.

## 1.0.0 (2026-10-03)

The first version: a cockpit tablet for [ED Outrider](https://github.com/weslocke/ED-Outrider).

- Outrider's tablet layout full screen, landscape only (also on Android 16 tablets), the screen kept on.
- The PC's address as a setting (port 8025 unless given); a version check that says "update the app" or "update
  Outrider" instead of a broken page.
- Signs in once when Outrider asks for a password; signs in again when the page reports the session gone.
- A "No link" screen that retries by itself; recovers after the tablet sleeps.
- Loads only the configured Outrider; other links open in the browser. Exports save to Downloads.
- The page <-> app bridge (`window.OutriderApp`, version 1): haptic, sign-in, app version, theme, listen.
- Voice: the wake word "OK / Okay / Hey / Hello Vespa" (the word and its sensitivity are settings), or Ask; the
  question goes to Outrider, which answers out loud on the PC.
- The app's own screens follow the page's theme: LCARS, Elite or Babylon 5.
