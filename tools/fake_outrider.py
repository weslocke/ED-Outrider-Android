#!/usr/bin/env python3
"""A stand-in for Outrider's native-facing contract, for trying the app before (or without) the real server.

Implements what the app relies on (PLAN-tablet-2026-10-02, "The API contract for the app"):
  GET  /api/version          open; {"outrider", "api", "min_app", "password", "signed_in"}
  POST /api/auth/signin      {"password"} -> {"ok", "token"} + Set-Cookie; 401 bad_password; 429 rate_limited
  POST /api/auth/signout     ends the session
  POST /api/ask              {"text"} -> {"answer", "spoken", "matched", "command"} (a few fixed phrases)
  GET  /tablet               a test page exercising window.OutriderApp, sessions, downloads and links
plus test helpers: GET /api/ping (needs a session), GET /api/export.csv (a download), POST /fake/expire (drops every
session, as a password change would).

Standard library only. Listens on 0.0.0.0:8026 by default so the tablet can reach it; never use 8025 (the real
Outrider's port). Requests from 127.0.0.1/::1 need no session, like the real Outrider.

    python3 tools/fake_outrider.py                    # password "test"
    python3 tools/fake_outrider.py --password ""      # no password
    python3 tools/fake_outrider.py --api 2            # "update the app"
    python3 tools/fake_outrider.py --old              # no /api/version: "update Outrider"
"""
import argparse
import hmac
import json
import secrets
import time
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COOKIE = "outrider_session"
OPEN_PATHS = {"/api/version", "/api/auth/signin", "/api/status"}
RATE_LIMIT = 5  # failed sign-ins per address per minute


class State:
    def __init__(self, args):
        self.args = args
        self.tokens = set()
        self.failures = {}  # address -> [timestamps]


class Handler(BaseHTTPRequestHandler):
    server_version = "FakeOutrider/1"
    state: State = None  # set in main()

    # ---- helpers ----------------------------------------------------------------------------------------------

    def log_message(self, fmt, *args):
        app = self.headers.get("X-Outrider-App", "-") if self.headers else "-"
        print(f"{self.address_string()} app={app} auth={self._auth_kind()} {fmt % args}", flush=True)

    def _token(self):
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            return auth[7:].strip()
        cookie = SimpleCookie(self.headers.get("Cookie", ""))
        return cookie[COOKIE].value if COOKIE in cookie else None

    def _auth_kind(self):
        if not getattr(self, "headers", None):
            return "-"
        if self.headers.get("Authorization", "").startswith("Bearer "):
            return "bearer"
        return "cookie" if COOKIE in SimpleCookie(self.headers.get("Cookie", "")) else "none"

    def _loopback(self):
        return self.client_address[0] in ("127.0.0.1", "::1", "::ffff:127.0.0.1")

    def _signed_in(self):
        return self._token() in self.state.tokens

    def _needs_session(self):
        return bool(self.state.args.password) and not self._loopback() and not self._signed_in()

    def _send(self, status, body, ctype="application/json", headers=()):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(status)
        self.send_header("Content-Type", ctype + ("; charset=utf-8" if ctype.startswith(("text/", "application/json")) else ""))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for k, v in headers:
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def _json(self, status, obj, headers=()):
        self._send(status, json.dumps(obj), headers=headers)

    def _error(self, status, code, words, headers=()):
        self._json(status, {"error": words, "code": code}, headers)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        try:
            return json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return None

    def _app_too_old(self):
        app = self.headers.get("X-Outrider-App")
        if not app:
            return False
        def parts(v):
            return [int("".join(c for c in p if c.isdigit()) or 0) for p in v.split("-")[0].split(".")]
        a, m = parts(app), parts(self.state.args.min_app)
        n = max(len(a), len(m))
        if a + [0] * (n - len(a)) < m + [0] * (n - len(m)):
            self._error(426, "app_too_old", f"This Outrider needs app {self.state.args.min_app} or newer.")
            return True
        return False

    # ---- routes -----------------------------------------------------------------------------------------------

    def do_GET(self):
        path = self.path.split("?")[0]
        args = self.state.args
        if path == "/api/version":
            if args.old:
                return self._send(404, "404: Not Found", "text/plain")
            if self._app_too_old():
                return
            return self._json(200, {"outrider": args.outrider, "api": args.api, "min_app": args.min_app,
                                    "password": bool(args.password), "signed_in": self._signed_in()})
        if path in ("/", "/tablet") and args.no_tablet:
            return self._send(404, "404: Not Found", "text/plain")
        if path not in OPEN_PATHS and self._needs_session():
            if path.startswith("/api/"):
                return self._error(401, "signin_required", "Sign in first.")
            return self._send(401, "<h1>Sign in required</h1>", "text/html")
        if path in ("/", "/tablet"):
            return self._send(200, TEST_PAGE.replace("__OUTRIDER__", args.outrider), "text/html")
        if path == "/api/ping":
            return self._json(200, {"ok": True, "time": time.strftime("%H:%M:%S")})
        if path == "/api/export.csv":
            stamp = time.strftime("%Y%m%d-%H%M%S")
            return self._send(200, "system,distance\nSmojooe AR-E b25-0,7.31\n", "text/csv",
                              [("Content-Disposition", f'attachment; filename="fake-export-{stamp}.csv"')])
        return self._error(404, "not_found", "No such route.")

    def do_POST(self):
        path = self.path.split("?")[0]
        args = self.state.args
        if path == "/api/auth/signin":
            if self._app_too_old():
                return
            ip = self.client_address[0]
            now = time.time()
            recent = [t for t in self.state.failures.get(ip, []) if now - t < 60]
            self.state.failures[ip] = recent
            if len(recent) >= RATE_LIMIT:
                wait = int(60 - (now - recent[0])) + 1
                return self._error(429, "rate_limited", f"Too many tries; wait {wait} s.", [("Retry-After", str(wait))])
            body = self._body()
            if body is None or not isinstance(body.get("password"), str):
                return self._error(400, "bad_request", "Send {\"password\": \"...\"}.")
            if not args.password or not hmac.compare_digest(body["password"].encode(), args.password.encode()):
                recent.append(now)
                return self._error(401, "bad_password", "Wrong password.")
            token = secrets.token_urlsafe(32)
            self.state.tokens.add(token)
            return self._json(200, {"ok": True, "token": token},
                              [("Set-Cookie", f"{COOKIE}={token}; HttpOnly; SameSite=Strict; Path=/")])
        if path == "/api/auth/signout":
            self.state.tokens.discard(self._token())
            return self._json(200, {"ok": True}, [("Set-Cookie", f"{COOKIE}=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/")])
        if path == "/api/ask":
            if self._needs_session():
                return self._error(401, "signin_required", "Sign in first.")
            body = self._body()
            text = body.get("text") if isinstance(body, dict) else None
            if not isinstance(text, str) or not text.strip() or len(text) > 500:
                return self._error(400, "bad_request", "Send {\"text\": \"...\"} (up to 500 characters).")
            t = text.lower()
            for words, command, answer in ASK_PHRASES:
                if any(w in t for w in words):
                    return self._json(200, {"answer": answer, "spoken": True, "matched": "fixed", "command": command})
            return self._json(200, {"answer": f"I don't know how to answer \"{text}\" yet.", "spoken": True,
                                    "matched": "none", "command": None})
        if path == "/fake/expire":
            self.state.tokens.clear()
            return self._json(200, {"ok": True, "note": "every session dropped"})
        return self._error(404, "not_found", "No such route.")


# /api/ask's stand-in fixed phrases (the real list lives in Outrider)
ASK_PHRASES = [
    (("status",), "status_report", "Fuel 100 percent, 23 jumps. 4 new systems this session."),
    (("fuel",), "fuel", "Fuel 160 of 160 tonnes, about 23 jumps."),
    (("unsold",), "unsold", "62.7 million credits unsold."),
    (("next jump", "next system"), "next_jump", "Next: Smojooe OR-N d6-121, a neutron star, 69 light years."),
]

TEST_PAGE = r"""<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Fake Outrider</title>
<style>
  body { margin: 0; background: #000; color: #FFCC99; font: 20px/1.4 sans-serif; }
  header { background: #FF9933; color: #000; font-weight: bold; padding: 10px 24px; border-radius: 0 0 0 40px; margin-left: 16px; }
  main { padding: 16px 24px; display: grid; grid-template-columns: 1fr 1fr; gap: 8px 32px; }
  button, a.btn { display: inline-block; background: #CC99CC; color: #000; border: 0; border-radius: 24px; padding: 12px 22px;
    margin: 4px 6px 4px 0; font: bold 18px sans-serif; text-decoration: none; min-height: 48px; }
  .k { color: #9999FF; } #log { white-space: pre-wrap; font: 15px monospace; color: #ddd; max-height: 260px; overflow: auto; }
  input { font-size: 20px; padding: 8px; background: #222; color: #FFCC99; border: 1px solid #9999FF; }
</style></head><body>
<header>FAKE OUTRIDER __OUTRIDER__ · TEST PAGE</header>
<main>
  <div>
    <div><span class="k">viewport</span> <span id="vp"></span></div>
    <div><span class="k">bridge</span> <span id="br"></span></div>
    <div><span class="k">link</span> <span id="link">?</span></div>
    <div><span class="k">user agent</span> <span id="ua" style="font-size:14px"></span></div>
    <div><span class="k">localStorage visits</span> <span id="visits"></span></div>
    <p><input placeholder="keyboard test"></p>
  </div>
  <div>
    <button onclick="haptic()">Haptic 30 ms</button>
    <button onclick="ping(true)">Ping (needs session)</button>
    <button onclick="expire()">Drop sessions</button>
    <button onclick="theme()">setTheme lcars</button>
    <button onclick="if (A && A.listen) A.listen(); else log('no OutriderApp.listen')">Ask</button><br>
    <a class="btn" href="/api/export.csv">Download CSV</a>
    <a class="btn" href="https://example.com/">External link</a>
    <a class="btn" id="otherport" href="#">Other port</a>
    <button onclick="if (confirm('Confirm dialog works?')) log('confirm: yes'); else log('confirm: no')">Confirm()</button>
    <div id="log"></div>
  </div>
</main>
<script>
  const $ = id => document.getElementById(id);
  function log(s) { $("log").textContent = new Date().toLocaleTimeString() + "  " + s + "\n" + $("log").textContent; }
  function vp() { $("vp").textContent = innerWidth + " × " + innerHeight + " CSS px, dpr " + devicePixelRatio; }
  addEventListener("resize", vp); vp();
  const A = window.OutriderApp;
  $("br").textContent = A ? ("OutriderApp v" + A.bridgeVersion + ", app " + (A.appVersion ? A.appVersion() : "?")) : "none (plain browser)";
  $("ua").textContent = navigator.userAgent;
  try { const n = +(localStorage.getItem("visits") || 0) + 1; localStorage.setItem("visits", n); $("visits").textContent = n; } catch (e) { $("visits").textContent = "unavailable"; }
  $("otherport").href = location.protocol + "//" + location.hostname + ":9/";
  function haptic() { if (A && A.haptic) { A.haptic(30); log("haptic(30) sent"); } else if (navigator.vibrate) { navigator.vibrate(30); log("navigator.vibrate"); } }
  function theme() { if (A && A.setTheme) { A.setTheme("lcars"); log("setTheme(lcars)"); } }
  let last = 0;
  async function ping(verbose) {
    try {
      const r = await fetch("/api/ping", {cache: "no-store"});
      if (r.status === 401) { log("ping: 401, session gone"); if (A && A.signInRequired) A.signInRequired(); return; }
      const j = await r.json(); last = Date.now(); if (verbose) log("ping ok " + j.time);
    } catch (e) { log("ping failed: " + e); }
  }
  async function expire() { await fetch("/fake/expire", {method: "POST"}); log("sessions dropped; next ping asks to sign in"); }
  setInterval(() => { ping(false); const s = last ? Math.round((Date.now() - last) / 1000) : null;
    $("link").textContent = s === null ? "NO LINK" : (s < 30 ? "LINKED · " + s + " S AGO" : "STALE · " + s + " S AGO"); }, 2000);
  ping(true);
</script></body></html>
"""


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8026)
    p.add_argument("--password", default="test", help='"" turns the password off')
    p.add_argument("--outrider", default="2026.10.3-fake")
    p.add_argument("--api", type=int, default=1)
    p.add_argument("--min-app", default="1.0.0")
    p.add_argument("--old", action="store_true", help="no /api/version (an Outrider from before the tablet)")
    p.add_argument("--no-tablet", action="store_true", help="no /tablet page")
    args = p.parse_args()
    if args.port == 8025:
        p.error("8025 is the real Outrider's port; pick another")
    Handler.state = State(args)
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"fake Outrider on http://{args.host}:{args.port}/tablet  password={args.password!r} api={args.api}", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
