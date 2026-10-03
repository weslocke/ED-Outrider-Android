# Changelog

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
