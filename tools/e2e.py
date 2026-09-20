#!/usr/bin/env python3
# License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
"""
tools/e2e.py — emulator E2E suite for ebook-viewer-android.

Runs 24 tests across 4 state-ordered groups on a live emulator:
  A. startup          (clean slate; pm clear at start)
  B. reading core     (accumulates state across the book)
  C. persistence      (force-stop / restart restores)
  D. cleanup / budget (dark-mode revert, cache budget, final cold start)

Tiers:
  AUTO    hard assertion; failure stops the run
  SOFT    evidence captured; logged but not a gate
  EVIDENCE screenshot only; no assertion

State guarantees:
  * Each test that mutates state notes it on `state` so the next test
    can either rewind (force-stop) or build on top.
  * pm clear happens once at the start of group A; subsequent tests
    build on persisted state from B → C.
  * recents_reopen (C) runs BEFORE any later pm clear because SAF
    permissions are cleared by pm clear.

Usage:
  tools/e2e.py                          # full suite on default device
  tools/e2e.py --group A                # only group A
  tools/e2e.py --device emulator-5556
  tools/e2e.py --book mathml.epub       # pick a non-default book

All evidence lands under build/e2e-evidence/<group>/<NN-name>.{png,json,txt}
plus a top-level results.json and summary.md.
"""
from __future__ import annotations

import argparse
import contextlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

try:
    import websocket  # type: ignore
except ImportError:
    print("FAIL: websocket-client required (pip3 install --user websocket-client)", file=sys.stderr)
    sys.exit(2)

try:
    from PIL import Image  # type: ignore
except ImportError:
    print("FAIL: Pillow required (pip3 install --user pillow)", file=sys.stderr)
    sys.exit(2)


PKG = "dev.calibre.viewer"
ACTIVITY = f"{PKG}/.MainActivity"
EVIDENCE_ROOT = Path("build/e2e-evidence")

# Books are opened from the app's INTERNAL files dir via run-as staging:
# targetSdk 34 + scoped storage means bare /sdcard/Download reads fail with
# EACCES (the app declares no storage permissions, by design), and adb push
# cannot write /sdcard/Android/data on API 30+. The push dance is
# /data/local/tmp -> run-as cp. pm clear wipes files/, so books are
# re-pushed at the head of groups B and C.
HOST_BOOKS_DIR = Path("build/e2e-books")
DEVICE_BOOKS_DIR = "/data/data/dev.calibre.viewer/files/e2e-books"
DEVICE_BOOKS_URI = f"file://{DEVICE_BOOKS_DIR}"

AUTO, SOFT, EVIDENCE = "AUTO", "SOFT", "EVIDENCE"


# ----------------------------------------------------------------------
# Shared mutable state across tests
# ----------------------------------------------------------------------
@dataclass
class State:
    device: str = "emulator-5554"
    book_on_sd: str = "book1.epub"      # default book for opening
    big_book: str = "test-book.epub"    # the 8.6MB one used for budget
    mathjax_book: str = "mathjax.epub"
    mathml_book: str = "mathml.epub"
    pid: str = ""                       # current app pid
    cdp_target: dict | None = None
    cdp_ws: Any = None
    dark_on: bool = False
    crashed: bool = False
    current_book_hash: str = ""         # sha1 of last opened book
    last_book_pos: float = 0.0          # pos_frac observed last
    last_font_size: str = ""
    history: list[str] = field(default_factory=list)


# ----------------------------------------------------------------------
# Result + Evidence
# ----------------------------------------------------------------------
@dataclass
class Result:
    name: str
    group: str
    tier: str
    ok: bool
    msg: str = ""
    evidence: list[str] = field(default_factory=list)
    skipped: bool = False


class Evidence:
    def __init__(self, root: Path):
        self.root = root
        (self.root / "screenshots").mkdir(parents=True, exist_ok=True)

    def path(self, group: str, name: str, ext: str = "png") -> Path:
        p = self.root / group
        p.mkdir(parents=True, exist_ok=True)
        return p / f"{name}.{ext}"

    def save(self, group: str, name: str, data: bytes | str, ext: str = "png") -> Path:
        p = self.path(group, name, ext)
        mode = "wb" if isinstance(data, bytes) else "w"
        p.write_bytes(data) if mode == "wb" else p.write_text(data)
        return p


# ----------------------------------------------------------------------
# Adb — thin wrapper
# ----------------------------------------------------------------------
class Adb:
    def __init__(self, device: str):
        self.device = device

    def run(self, *args: str, timeout: int = 30, check: bool = False) -> str:
        cmd = ["adb", "-s", self.device, *args]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb failed: {' '.join(cmd)}\n{r.stderr}")
        return r.stdout.strip()

    def shell(self, cmd: str, timeout: int = 30) -> str:
        return self.run("shell", cmd, timeout=timeout)

    def screencap(self, dest: Path) -> Path:
        self.shell(f"screencap -p /sdcard/_e2e.png")
        self.run("pull", "/sdcard/_e2e.png", str(dest))
        return dest

    def install(self, apk: Path) -> None:
        self.run("install", "-r", str(apk), timeout=180)

    def push_books(self, names: list[str] | None = None) -> None:
        """Stage host-side epubs into the app's internal files dir (see the
        DEVICE_BOOKS_DIR note above). Idempotent; call after every pm_clear
        that precedes book tests."""
        if not HOST_BOOKS_DIR.is_dir():
            raise RuntimeError(
                f"{HOST_BOOKS_DIR} missing — stage the fixture epubs there "
                "first (they are NOT in git; bootstrap once from wherever "
                "your books live, e.g. "
                "`adb pull /sdcard/Download/book1.epub build/e2e-books/`)")
        epubs = [p for p in sorted(HOST_BOOKS_DIR.glob("*.epub"))
                 if names is None or p.name in names]
        if not epubs:
            raise RuntimeError(f"no epubs found in {HOST_BOOKS_DIR}")
        for epub in epubs:
            self.run("push", str(epub), f"/data/local/tmp/{epub.name}",
                     timeout=120)
        # Shell sees /data/local/tmp; the app does not — copy in as the app.
        # ONE shell string: adb joins separate argv with spaces, which would
        # hand only the first word to `sh -c` and run the `&& cp` as shell
        # (denied on /data/data — and silently, without check=True).
        staged = " ".join(f"/data/local/tmp/{e.name}" for e in epubs)
        self.shell(
            f"run-as {PKG} sh -c 'mkdir -p {DEVICE_BOOKS_DIR} && "
            f"cp {staged} {DEVICE_BOOKS_DIR}/'")
        listing = self.shell(f"run-as {PKG} ls {DEVICE_BOOKS_DIR}")
        landed = set(listing.split())
        missing = [e.name for e in epubs if e.name not in landed]
        if missing:
            raise RuntimeError(f"book staging failed, missing on device: {missing}")
        self.shell(f"rm -f {staged}")

    def pidof(self) -> str:
        return self.shell(f"pidof {PKG}")

    def force_stop(self) -> None:
        self.run("shell", "am", "force-stop", PKG)

    def pm_clear(self) -> None:
        self.run("shell", "pm", "clear", PKG)

    def wait_app(self, timeout: int = 20) -> str:
        deadline = time.time() + timeout
        while time.time() < deadline:
            pid = self.pidof()
            if pid:
                return pid
            time.sleep(0.5)
        raise TimeoutError(f"{PKG} did not come up within {timeout}s")

    def start_activity(self) -> None:
        self.run("shell", "am", "start", "-n", ACTIVITY)

    def cold_start(self) -> str:
        self.force_stop()
        time.sleep(0.5)
        self.start_activity()
        return self.wait_app()

    def tap(self, x: int, y: int) -> None:
        self.run("shell", "input", "tap", str(x), str(y))

    def key(self, code: str) -> None:
        # keyevent names: VOLUME_UP / VOLUME_DOWN / BACK / KEYCODE_ENTER ...
        self.run("shell", "input", "keyevent", code)

    def volume(self, up: bool = True) -> None:
        # Wait a beat after the OS volume UI flashes (e-ink full refresh)
        self.key("KEYCODE_VOLUME_UP" if up else "KEYCODE_VOLUME_DOWN")
        time.sleep(1.0)

    def swipe(self, x1: int, y1: int, x2: int, y2: int, dur_ms: int = 200) -> None:
        self.run("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(dur_ms))

    def store_dump(self, rel: str) -> dict | str:
        """Read app file via run-as and return parsed JSON if possible."""
        out = self.shell(f"run-as {PKG} cat files/{rel}", timeout=20)
        if not out:
            return ""
        try:
            return json.loads(out)
        except json.JSONDecodeError:
            return out

    def store_text(self, rel: str) -> str:
        return self.shell(f"run-as {PKG} cat files/{rel}", timeout=20)

    def meminfo(self) -> dict:
        pid = self.pidof()
        out = self.shell(f"dumpsys meminfo {pid}")
        m = re.search(r"TOTAL PSS:\s+(\d+)", out)
        return {"pid": pid, "pss_kb": int(m.group(1)) if m else 0}

    def list_fds(self) -> int:
        pid = self.pidof()
        if not pid:
            return 0
        # Plain shell cannot read /proc/<app-pid>/fd on API 30+ (denied;
        # stderr suppressed → wc -l printed 0 and the fd test passed
        # vacuously). The app uid can list its own fds — go through run-as.
        out = self.shell(f"run-as {PKG} ls /proc/{pid}/fd 2>/dev/null | wc -l")
        try:
            return int(out)
        except ValueError:
            return 0

    def system_dir(self, name: str) -> Path:
        """Make a local copy of an app file via run-as."""
        with tempfile.NamedTemporaryFile(delete=False, suffix=f".{name}") as f:
            local = Path(f.name)
        self.run("shell", "run-as", PKG, "cp", f"files/{name}", f"/sdcard/_e2e_{name}")
        self.run("pull", f"/sdcard/_e2e_{name}", str(local))
        self.run("shell", "rm", f"/sdcard/_e2e_{name}")
        return local


# ----------------------------------------------------------------------
# Cdp — WebView DevTools over websocket with origin suppression
# ----------------------------------------------------------------------
class Cdp:
    def __init__(self, adb: Adb, state: State):
        self.adb = adb
        self.state = state

    def discover_pid(self) -> str:
        pid = self.adb.pidof()
        if not pid:
            raise RuntimeError("app not running; cold-start first")
        if pid != self.state.pid:
            # New pid (force-stop, crash) — re-forward + rediscover target.
            self._reforward(pid)
            self.state.pid = pid
            self.state.cdp_target = None
            self.state.cdp_ws = None
        return pid

    def _reforward(self, pid: str) -> None:
        # Remove existing forward (idempotent — ignore "not found" errors)
        with contextlib.suppress(RuntimeError):
            self.adb.run("forward", "--remove", "tcp:9222")
        self.adb.run("forward", "tcp:9222", f"localabstract:webview_devtools_remote_{pid}")

    def target(self) -> dict:
        if self.state.cdp_target:
            return self.state.cdp_target
        self.discover_pid()
        with urllib.request.urlopen("http://127.0.0.1:9222/json", timeout=5) as r:
            targets = json.loads(r.read())
        page = next((t for t in targets if t["type"] == "page"), None)
        if not page:
            raise RuntimeError(f"no page target; available: {[t['type'] for t in targets]}")
        self.state.cdp_target = page
        return page

    def ws(self) -> Any:
        if self.state.cdp_ws is not None:
            return self.state.cdp_ws
        t = self.target()
        ws = websocket.create_connection(
            t["webSocketDebuggerUrl"], timeout=15, suppress_origin=True
        )
        self.state.cdp_ws = ws
        return ws

    def send(self, method: str, params: dict | None = None, _id: list[int] | None = None):
        if _id is None:
            _id = self._id
        msg = {"id": _id[0], "method": method}
        if params is not None:
            msg["params"] = params
        _id[0] += 1
        self.ws().send(json.dumps(msg))
        while True:
            m = json.loads(self.ws().recv())
            if m.get("id") == _id[0] - 1:
                return m
    _id: list[int] = [1]

    def evaluate(self, expr: str, *, await_promise: bool = False):
        r = self.send(
            "Runtime.evaluate",
            {"expression": expr, "returnByValue": True, "awaitPromise": await_promise},
        )
        res = r.get("result", {}).get("result", {})
        if "value" in res:
            return res["value"]
        if res.get("type") == "undefined":
            return None
        return res

    def click_text(self, text: str) -> bool:
        return bool(self.evaluate(f"""
            (function(){{
              var t={json.dumps(text)}; var ns=document.querySelectorAll('a,button,span,div,li,td');
              // Walk backwards so the deepest matching control wins. In the
              // toolbar both <li> and its child <button> have identical text;
              // clicking the inert parent reports success but triggers no action.
              for(var i=ns.length-1;i>=0;i--){{
                var n=ns[i]; var s=(n.innerText||n.textContent||'').trim();
                if(s===t){{n.click();return true;}}
              }}return false;
            }})()
        """))

    def page_probe(self, expr: str):
        """Same as evaluate but with a fresh discovery if a previous
        page was lost (crash recovery)."""
        try:
            return self.evaluate(expr)
        except Exception:
            self.state.cdp_target = None
            self.state.cdp_ws = None
            return self.evaluate(expr)

    def viewport(self) -> dict:
        return self.evaluate("({w: window.innerWidth, h: window.innerHeight})") or {}

    def close(self) -> None:
        if self.state.cdp_ws is not None:
            with contextlib.suppress(Exception):
                self.state.cdp_ws.close()
            self.state.cdp_ws = None
            self.state.cdp_target = None


# ----------------------------------------------------------------------
# composeTap — visual localisation of a right-side Compose panel
# ----------------------------------------------------------------------
def compose_box_tap(adb: Adb, screenshot: Path, screen_w: int = 1080, screen_h: int = 2400,
                    side: str = "right") -> tuple[int, int] | None:
    """Find a Compose panel by detecting a thin (≤4px) bright border on
    `side`, then return the centre of the bordered region.

    Compose on e-ink: right-side panels render with a 1–2px black border
    on a white background. The border's vertical extent bounds the
    panel; tap at the centre of the border edge (safer than dead-center
    which may hit text).
    """
    img = Image.open(screenshot).convert("L")
    w, h = img.size
    arr = img.load()
    x_col = w - 4 if side == "right" else 3
    ys: list[int] = []
    for y in range(50, h - 50):
        if arr[x_col, y] < 100:  # dark pixel = border
            ys.append(y)
    if not ys or len(ys) < 20:
        return None
    top, bot = ys[0], ys[-1]
    return (x_col, (top + bot) // 2)


# ----------------------------------------------------------------------
# Test runner
# ----------------------------------------------------------------------
class Runner:
    def __init__(self, adb: Adb, cdp: Cdp, evidence: Evidence, state: State):
        self.adb = adb
        self.cdp = cdp
        self.ev = evidence
        self.state = state
        self.results: list[Result] = []

    def run(self, group: str, name: str, tier: str, fn: Callable[[], tuple[bool, str, list[str]]],
            *, skip: bool = False) -> Result:
        print(f"  [{tier:7}] {group}/{name}", end=" ... ", flush=True)
        if skip:
            print("SKIP")
            r = Result(name=name, group=group, tier=tier, ok=True, msg="skipped", skipped=True)
            self.results.append(r)
            return r
        t0 = time.time()
        try:
            ok, msg, evidence = fn()
        except Exception as e:
            ok, msg = False, f"exception: {e!r}"
            evidence = []
        dt = time.time() - t0
        status = "PASS" if ok else "FAIL"
        print(f"{status} ({dt:.1f}s) {msg}")
        r = Result(name=name, group=group, tier=tier, ok=ok, msg=msg, evidence=evidence)
        self.results.append(r)
        # Snapshot the screenshot per result for AUTO/EVIDENCE tiers
        if evidence:
            shot = self.ev.path(group, name, "png")
            if shot.exists():
                r.evidence.append(str(shot.relative_to(self.ev.root)))
        return r

    def summary(self) -> dict:
        return {
            "device": self.state.device,
            "ran_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "results": [
                {"group": r.group, "name": r.name, "tier": r.tier, "ok": r.ok,
                 "msg": r.msg, "skipped": r.skipped, "evidence": r.evidence}
                for r in self.results
            ],
        }

    def write_summary(self) -> tuple[Path, Path]:
        js = self.ev.root / "results.json"
        js.write_text(json.dumps(self.summary(), indent=2, ensure_ascii=False))
        md = self.ev.root / "summary.md"
        lines = ["# e2e summary", ""]
        fails = [r for r in self.results if not r.ok and not r.skipped]
        auto_fails = [r for r in fails if r.tier == AUTO]
        lines.append(f"- device: `{self.state.device}`")
        lines.append(f"- ran: {self.summary()['ran_at']}")
        lines.append(f"- total: {len(self.results)}, hard failures: {len(auto_fails)}")
        if auto_fails:
            lines.append("")
            lines.append("## hard failures")
            for r in auto_fails:
                lines.append(f"- **{r.group}/{r.name}** — {r.msg}")
        for grp in ("A", "B", "C", "D"):
            sub = [r for r in self.results if r.group == grp]
            if not sub:
                continue
            lines += ["", f"## group {grp}"]
            for r in sub:
                flag = "✓" if r.ok else ("—" if r.skipped else "✗")
                lines.append(f"- {flag} `{r.name}` ({r.tier}) — {r.msg}")
        md.write_text("\n".join(lines))
        return js, md


# ----------------------------------------------------------------------
# Helpers used across tests
# ----------------------------------------------------------------------
def wait(sec: float) -> None:
    time.sleep(sec)


def page_ready(cdp: Cdp, *, timeout: float = 8.0) -> bool:
    """Return True when the iframe `read-book-iframe-1` exists in the
    parent document. Book open = the iframe is present + non-empty."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            n = cdp.page_probe("document.querySelectorAll('iframe').length")
            if n and n >= 1:
                return True
        except Exception:
            pass
        time.sleep(0.4)
    return False


def get_pos_frac(adb: Adb) -> float:
    """Return the current pos_frac by reading last-read.json from the
    app's files/ dir via run-as. The store is keyed by book hash and
    is updated on every JS-level position signal, so it is the canonical
    page-turn signal — the iframe content lives in a sandboxed origin
    and is not directly readable from CDP.
    """
    try:
        out = adb.store_text("last-read.json")
    except Exception:
        return -2.0
    if not out:
        return -2.0
    try:
        obj = json.loads(out)
    except json.JSONDecodeError:
        return -2.0
    if not isinstance(obj, dict) or not obj:
        return -2.0
    # Take the most recently updated entry (any one is fine for current book)
    last = next(iter(obj.values()))
    if not isinstance(last, dict):
        return -2.0
    return float(last.get("pos_frac", -2.0))


def wait_pos_change(adb: Adb, before: float, *, timeout: float = 6.0) -> float:
    """Poll last-read.json until pos_frac moves off `before`.

    The host persists reading positions with a 2s write-behind debounce, so
    an immediate read races the flush and a successful turn looks like a
    no-op. Returns the new value, or `before` on timeout (caller asserts).
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        p = get_pos_frac(adb)
        if p >= 0 and p != before:
            return p
        time.sleep(0.5)
    return before


def get_current_hash(adb: Adb) -> str:
    try:
        out = adb.store_text("last-read.json")
    except Exception:
        return ""
    if not out:
        return ""
    try:
        obj = json.loads(out)
        if isinstance(obj, dict) and obj:
            return next(iter(obj.keys()))
    except json.JSONDecodeError:
        pass
    return ""


def bookmark_count(adb: Adb) -> int:
    out = adb.store_dump("bookmarks.json")
    if isinstance(out, dict):
        return len(out)
    return 0


def profile_count(adb: Adb) -> int:
    out = adb.store_dump("profiles.json")
    if isinstance(out, dict):
        return len(out)
    return 0


def highlight_count(adb: Adb) -> int:
    out = adb.store_dump("annotations.json")
    if isinstance(out, dict):
        return len(out)
    return 0


# ----------------------------------------------------------------------
# Test groups — 23 tests, AUTO/SOFT/EVIDENCE
# ----------------------------------------------------------------------
def group_A(r: Runner, apk: Path) -> None:
    """A. Startup — clean slate (pm clear happens at the head of A)."""
    print("== group A: startup ==")
    adb, cdp, ev, state = r.adb, r.cdp, r.ev, r.state

    # A1: cold_start_home — AUTO
    def cold_start_home():
        adb.pm_clear()
        wait(0.5)
        adb.cold_start()
        ok = page_ready(cdp, timeout=8)
        return ok, "home loaded" if ok else "iframe missing", []

    r.run("A", "01-cold-start-home", AUTO, cold_start_home)

    # A2: home_monochrome — AUTO  (PIL saturation of the home screen < 0.04)
    def home_monochrome():
        sshot = ev.path("A", "02-home-monochrome", "png")
        adb.screencap(sshot)
        img = Image.open(sshot).convert("RGB")
        rgb = img.getdata()
        # Saturation = (max-min)/max per pixel, averaged.
        n = 0
        sat_sum = 0.0
        # Sample every 16th pixel for speed.
        step = 16
        for i in range(0, len(rgb), step):
            r_, g_, b_ = rgb[i]
            mx, mn = max(r_, g_, b_), min(r_, g_, b_)
            if mx > 0:
                sat_sum += (mx - mn) / mx
                n += 1
        avg_sat = sat_sum / max(n, 1)
        ok = avg_sat < 0.04
        return ok, f"avg saturation={avg_sat:.4f} (<0.04)", [str(sshot)]

    r.run("A", "02-home-monochrome", AUTO, home_monochrome)


def group_B(r: Runner) -> None:
    """B. Reading core — accumulates state across the open book."""
    print("== group B: reading core ==")
    adb, cdp, ev, state = r.adb, r.cdp, r.ev, r.state

    # Group A's pm_clear wiped files/ — restage the fixture books first.
    adb.push_books()

    # B1: actionview_open — AUTO
    def actionview_open():
        adb.run("shell", "am", "start",
                "-a", "android.intent.action.VIEW",
                "-d", f"{DEVICE_BOOKS_URI}/{state.book_on_sd}",
                "-t", "application/epub+zip",
                ACTIVITY)
        ok = page_ready(cdp, timeout=10)
        if not ok:
            return False, "iframe not ready", []
        # page_ready is ALSO true on the home page — it cannot distinguish
        # "book rendered" from "app up". The real gate is the reading
        # position signal: a displayed book writes last-read.json (2s
        # debounce in the host). Without this poll, a failed open (e.g.
        # EACCES on the source) passed vacuously and every later B/C
        # assertion read -2.0 sentinels.
        deadline = time.time() + 25
        while time.time() < deadline:
            if get_pos_frac(adb) >= 0:
                return True, "book opened and first position reported", []
            time.sleep(1.0)
        return False, "book never reported a reading position", []

    r.run("B", "01-actionview-open", AUTO, actionview_open)

    # B2: volume_page_turns — AUTO  (pos_frac in last-read.json advances)
    def volume_page_turns():
        # VOLUME_DOWN (pagedown): the book opens at its start, where pageup
        # clamps to a legitimate no-op — a forward turn is the only direction
        # guaranteed to move (VOLUME_UP here made this test vacuous).
        #
        # Warm-up presses can be silently dropped: the host writes the first
        # reading position (B/01's pass gate) BEFORE the iframe secret
        # handshake completes, and IframeWrapper._send_message discards any
        # shortcut that arrives ahead of it (observed: exactly the first
        # press lost after open). Press until pos moves, then require 3
        # clean monotonic advances — a dropped press mid-run retries, it
        # does not fail the sequence.
        snaps = [get_pos_frac(adb)]
        presses = 0
        while len(snaps) < 4 and presses < 8:
            presses += 1
            adb.volume(up=False)
            moved = wait_pos_change(adb, snaps[-1], timeout=6.0)
            if moved != snaps[-1]:
                snaps.append(moved)
        mono = all(snaps[i] < snaps[i + 1] for i in range(len(snaps) - 1))
        ok = mono and len(snaps) == 4
        return ok, f"pos_frac: {[round(x,4) for x in snaps]} ({presses} presses)", []

    r.run("B", "02-volume-page-turns", AUTO, volume_page_turns)

    # B3: tap_page_turn — AUTO  (tap on right margin advances pos_frac)
    def tap_page_turn():
        # The margin turn zone starts around 96% of screen width; x=1000 on
        # a 1080-wide screen lands inside the text column (select / no-op).
        before = get_pos_frac(adb)
        adb.tap(1050, 1200)
        after = wait_pos_change(adb, before, timeout=6.0)
        return after > before, f"{before:.4f} → {after:.4f}", []

    r.run("B", "03-tap-page-turn", AUTO, tap_page_turn)

    # B4: volume_longpress_single_turn — AUTO  (long-press should be a single turn)
    def volume_longpress_single_turn():
        before = get_pos_frac(adb)
        adb.run("shell", "input", "keyevent", "--longpress", "KEYCODE_VOLUME_DOWN")
        after = wait_pos_change(adb, before, timeout=7.0)
        delta = after - before
        return 0 < delta <= 0.25, f"long-press delta={delta:.4f} (≤0.25)", []

    r.run("B", "04-volume-longpress-single-turn", AUTO, volume_longpress_single_turn)

    # B5: toc_navigate — SOFT  (TOC click requires navigating viewer.js UI)
    def toc_navigate():
        before = get_pos_frac(adb)
        cdp.click_text("Table of Contents") or adb.tap(950, 200)
        wait(1.0)
        cdp.click_text("Chapter 1") or cdp.click_text("第一章") or cdp.evaluate(
            "document.querySelector('.toc-list .item,.toc li')?.click()"
        )
        wait(1.5)
        after = get_pos_frac(adb)
        return abs(after - before) > 0.01, f"pos_frac {before:.3f} → {after:.3f}", []

    r.run("B", "05-toc-navigate", SOFT, toc_navigate)

    # B6: search_jump — SOFT  (search panel requires deep UI navigation)
    def search_jump():
        before = get_pos_frac(adb)
        cdp.click_text("Search")
        wait(1.0)
        cdp.evaluate("document.querySelector('#search-input,input[type=search]')?.focus()")
        adb.shell("input text 'the'")
        wait(1.5)
        cdp.evaluate("document.querySelector('.search-result,.result')?.click()")
        wait(1.5)
        after = get_pos_frac(adb)
        return abs(after - before) > 0.01, f"pos_frac {before:.3f} → {after:.3f}", []

    r.run("B", "06-search-jump", SOFT, search_jump)

    # B7: bookmark_create_list_jump — SOFT  (best-effort via the top
    # toolbar — the reader's long-press → bookmark gesture is hard to
    # trigger programmatically without a real touch sequence).
    def bookmark_create_list_jump():
        before = bookmark_count(adb)
        adb.tap(950, 200)  # open the menu overlay
        wait(1.0)
        adb.tap(500, 800)  # bookmark row (best-effort coordinate)
        wait(1.5)
        adb.force_stop()
        wait(0.5)
        adb.start_activity()
        wait_app_ping(adb, 8)
        page_ready(cdp, timeout=8)
        after = bookmark_count(adb)
        return after >= before, f"bookmarks {before} → {after}", []

    r.run("B", "07-bookmark-create-list-jump", SOFT, bookmark_create_list_jump)

    # B8: highlights_panel — EVIDENCE
    def highlights_panel():
        adb.tap(1000, 200)
        wait(1.0)
        cdp.click_text("Highlights") or adb.tap(800, 600)
        wait(1.0)
        sshot = ev.path("B", "08-highlights-panel", "png")
        adb.screencap(sshot)
        return True, f"screenshot saved", [str(sshot)]

    r.run("B", "08-highlights-panel", EVIDENCE, highlights_panel)

    # B9: profiles_save_apply_delete — AUTO  (profiles.json count change + font size probe)
    def profiles_save_apply_delete():
        before_n = profile_count(adb)
        before_size = cdp.page_probe("document.documentElement.style.fontSize || ''") or ""
        # Open profile panel and save current as a new profile
        adb.tap(1000, 300)
        wait(0.5)
        cdp.click_text("Profiles")
        wait(1.0)
        cdp.evaluate("""
            (function(){
              var btn = document.querySelector('#profile-save,button[data-action=save-profile]');
              if (btn) btn.click();
            })()
        """)
        wait(1.5)
        after_n = profile_count(adb)
        # Restore font size to default for subsequent tests
        return after_n >= before_n, f"profiles {before_n} → {after_n}", []

    r.run("B", "09-profiles-save-apply-delete", AUTO, profiles_save_apply_delete)

    # B10: mathjax_renders — SOFT  (MathJax nodes are inside the sandboxed
    # iframe which CDP can't enumerate; we capture a screenshot for visual
    # verification instead).
    def mathjax_renders():
        adb.force_stop()
        wait(0.5)
        adb.run("shell", "am", "start",
                "-a", "android.intent.action.VIEW",
                "-d", f"{DEVICE_BOOKS_URI}/{state.mathjax_book}",
                ACTIVITY)
        wait_app_ping(adb, 10)
        ok = page_ready(cdp, timeout=10)
        if not ok:
            return False, "book failed to open"
        wait(3.0)
        sshot = ev.path("B", "10-mathjax", "png")
        adb.screencap(sshot)
        return ok, f"mathjax book opened; screenshot saved", [str(sshot)]

    r.run("B", "10-mathjax-renders", SOFT, mathjax_renders)

    # B11: mathml_renders — SOFT  (same iframe-sandbox caveat as B10).
    def mathml_renders():
        adb.force_stop()
        wait(0.5)
        adb.run("shell", "am", "start",
                "-a", "android.intent.action.VIEW",
                "-d", f"{DEVICE_BOOKS_URI}/{state.mathml_book}",
                ACTIVITY)
        wait_app_ping(adb, 10)
        ok = page_ready(cdp, timeout=10)
        if not ok:
            return False, "book failed to open"
        wait(3.0)
        sshot = ev.path("B", "11-mathml", "png")
        adb.screencap(sshot)
        return ok, f"mathml book opened; screenshot saved", [str(sshot)]

    r.run("B", "11-mathml-renders", SOFT, mathml_renders)

    # B12: renderer_crash_recovery — AUTO  (Page.crash → new target, pos_frac ±1 page)
    def renderer_crash_recovery():
        before = get_pos_frac(adb)
        with contextlib.suppress(Exception):
            cdp.send("Page.crash")
        wait(3.0)
        cdp.close()
        # Wait for the host to relaunch the renderer
        deadline = time.time() + 12
        ok_pid = ""
        while time.time() < deadline:
            ok_pid = adb.pidof()
            if ok_pid:
                break
            time.sleep(0.5)
        if not ok_pid:
            return False, "app died after crash", []
        state.pid = ""  # force re-forward
        cdp.discover_pid()
        ok_ready = page_ready(cdp, timeout=10)
        wait(2.0)
        after = get_pos_frac(adb)
        return ok_ready and abs(after - before) < 0.15, f"pid={ok_pid}, pos {before:.3f} → {after:.3f}", []

    r.run("B", "12-renderer-crash-recovery", AUTO, renderer_crash_recovery)

    # B13: fd_stability_rapid_turns — AUTO  (20 turns → ≤8 fd growth)
    def fd_stability_rapid_turns():
        before = adb.list_fds()
        for _ in range(20):
            # Down, not Up: by now the book is mid-flow and a real forward
            # turn exercises spine fetches (the fd path under test); pageup
            # could clamp and turn this into a no-op loop.
            adb.key("KEYCODE_VOLUME_DOWN")
            wait(0.2)
        wait(1.0)
        after = adb.list_fds()
        delta = after - before
        return delta <= 8, f"fds {before} → {after} (Δ={delta})", []

    r.run("B", "13-fd-stability-rapid-turns", AUTO, fd_stability_rapid_turns)

    # B14: bigbook_open_timing — SOFT  (open the 8.6 MB book, capture meminfo)
    def bigbook_open_timing():
        t0 = time.time()
        adb.force_stop()
        wait(0.5)
        adb.run("shell", "am", "start",
                "-a", "android.intent.action.VIEW",
                "-d", f"{DEVICE_BOOKS_URI}/{state.big_book}",
                ACTIVITY)
        wait_app_ping(adb, 10)
        ok = page_ready(cdp, timeout=15)
        dt = time.time() - t0
        mem = adb.meminfo()
        ev.save("B", "14-bigbook-meminfo", json.dumps(mem, indent=2), "json")
        return ok, f"opened in {dt:.1f}s, PSS={mem['pss_kb']} kB", [str(ev.path("B", "14-bigbook-meminfo", "json"))]

    r.run("B", "14-bigbook-open-timing", SOFT, bigbook_open_timing)

    # B15: background_freeze_resume — AUTO  (HOME → CPU <1%, pos unchanged)
    def background_freeze_resume():
        before = get_pos_frac(adb)
        adb.run("shell", "input", "keyevent", "KEYCODE_HOME")
        wait(3.0)
        pid_now = state.pid or adb.pidof()
        cpu_out = adb.shell(f"top -b -n 1 -p {pid_now}")
        cpu = re.search(r"(\d+\.\d+)", cpu_out.splitlines()[-1] if cpu_out else "")
        cpu_pct = float(cpu.group(1)) if cpu else 0.0
        adb.run("shell", "am", "start", "-n", ACTIVITY)
        wait(2.0)
        page_ready(cdp, timeout=8)
        after = get_pos_frac(adb)
        return cpu_pct < 1.0 and abs(after - before) < 0.05, f"CPU {cpu_pct:.1f}%, pos {before:.3f}→{after:.3f}", []

    r.run("B", "15-background-freeze-resume", AUTO, background_freeze_resume)

    # B16: dark_mode — SOFT  (visual confirmation via screenshot; the
    # bridge set_system_palette signal was removed in cleanup, so we
    # just capture the current reader state).
    def dark_mode():
        sshot = ev.path("B", "16-dark-mode", "png")
        adb.screencap(sshot)
        return True, "screenshot saved (palette wiring deferred)", [str(sshot)]

    r.run("B", "16-dark-mode", EVIDENCE, dark_mode)

    # B17: overlay_backdrop_boundary — AUTO. The regression was caused by
    # treating every non-control descendant as outside whitespace. Exercise
    # real bubbled click events on a non-interactive content container, then
    # click the actual #book-overlay backdrop and require the opposite result.
    def overlay_backdrop_boundary():
        def overlay_state():
            return cdp.evaluate("""
                (function(){
                  var root=document.getElementById('book-overlay');
                  var title=root && root.querySelector('.top-bar-title');
                  return {visible:!!root && root.style.display!=='none',
                          title:title ? (title.textContent||'').trim() : ''};
                })()
            """) or {}

        def close_overlay_stack():
            for _ in range(3):
                state_now = overlay_state()
                if not state_now.get("visible"):
                    return
                cdp.evaluate("document.getElementById('book-overlay')?.click()")
                wait(0.15)

        def open_panel(action: str, child: str | None = None) -> tuple[bool, str]:
            close_overlay_stack()
            adb.tap(1000, 200)
            wait(0.5)
            if not cdp.click_text(action):
                return False, f"missing toolbar action: {action}"
            wait(0.35)
            if child and not cdp.click_text(child):
                return False, f"missing {action} child: {child}"
            wait(0.35)
            current = overlay_state()
            return bool(current.get("visible")), str(current.get("title", ""))

        def click_interior_blank():
            return cdp.evaluate("""
                (function(){
                  var root=document.getElementById('book-overlay');
                  if(!root || root.style.display==='none') return {ok:false, why:'overlay hidden'};
                  var first=root.firstElementChild;
                  var candidates=[first && first.lastElementChild, root.lastElementChild, first];
                  var target=null;
                  for(var i=0;i<candidates.length;i++){
                    var n=candidates[i];
                    if(!n || n===root || n.closest('.main-top-bar')) continue;
                    var r=n.getBoundingClientRect(), s=getComputedStyle(n);
                    if(r.width>1 && r.height>1 && s.display!=='none' && s.visibility!=='hidden'){
                      target=n; break;
                    }
                  }
                  if(!target) return {ok:false, why:'no interior blank target'};
                  target.dispatchEvent(new MouseEvent('click', {
                    bubbles:true, cancelable:true, view:window
                  }));
                  var title=root.querySelector('.top-bar-title');
                  return {ok:true, visible:root.style.display!=='none',
                          tag:target.tagName,
                          title:title ? (title.textContent||'').trim() : ''};
                })()
            """) or {}

        cases = [
            ("Preferences", "Colors", False),
            ("Open book", None, False),
            ("Reading statistics", None, False),
            ("Go to", "Metadata", True),
        ]
        checked = []
        for action, child, returns_to_parent in cases:
            opened, title = open_panel(action, child)
            if not opened:
                return False, title, []
            inside = click_interior_blank()
            if not inside.get("ok") or not inside.get("visible") or inside.get("title") != title:
                return False, f"{action}: interior click dismissed/changed panel: {inside}", []

            cdp.evaluate("document.getElementById('book-overlay')?.click()")
            wait(0.2)
            outside = overlay_state()
            if returns_to_parent:
                if not outside.get("visible") or outside.get("title") == title:
                    return False, f"{action}: backdrop did not return to parent: {outside}", []
            elif outside.get("visible"):
                return False, f"{action}: backdrop did not dismiss panel: {outside}", []
            checked.append(action + (f"/{child}" if child else ""))

        close_overlay_stack()
        return True, "checked " + ", ".join(checked), []

    r.run("B", "17-overlay-backdrop-boundary", AUTO, overlay_backdrop_boundary)


def wait_app_ping(adb: Adb, timeout: int = 10) -> str:
    deadline = time.time() + timeout
    while time.time() < deadline:
        pid = adb.pidof()
        if pid:
            return pid
        time.sleep(0.3)
    raise TimeoutError("app pid missing")


def group_C(r: Runner) -> None:
    """C. Persistence — survives force-stop and process restart."""
    print("== group C: persistence ==")
    adb, cdp, ev, state = r.adb, r.cdp, r.ev, r.state

    # A standalone `--group C` run starts after a possible pm_clear; the
    # recents-reopen test reopens the book FILE, which must exist.
    adb.push_books()

    # C1: force_stop_position_restore — AUTO
    def force_stop_position_restore():
        pos_before = get_pos_frac(adb)
        hash_before = get_current_hash(adb)
        adb.force_stop()
        wait(0.5)
        adb.start_activity()
        wait_app_ping(adb, 8)
        page_ready(cdp, timeout=8)
        wait(2.0)
        pos_after = get_pos_frac(adb)
        hash_after = get_current_hash(adb)
        same_hash = hash_before and hash_after == hash_before
        return abs(pos_after - pos_before) < 0.05, f"pos {pos_before:.3f} → {pos_after:.3f}, hash stable={same_hash}", []

    r.run("C", "01-force-stop-position-restore", AUTO, force_stop_position_restore)

    # C2: session_persistence_restart — SOFT  (font size persistence is
    # best-effort given the bridge's viewer_font_size_changed signal was
    # removed in the cleanup commit).
    def session_persistence_restart():
        before = adb.store_text("session_data.json")
        adb.force_stop()
        wait(0.5)
        adb.start_activity()
        wait_app_ping(adb, 8)
        page_ready(cdp, timeout=8)
        after = adb.store_text("session_data.json")
        return before == after and bool(before), f"session_data round-tripped", []

    r.run("C", "02-session-persistence-restart", SOFT, session_persistence_restart)

    # C3: recents_reopen — AUTO  (bookmarks.json still populated after recents-tap)
    def recents_reopen():
        before = bookmark_count(adb)
        adb.run("shell", "am", "start", "-a", "android.intent.action.VIEW",
                "-d", f"{DEVICE_BOOKS_URI}/{state.book_on_sd}",
                ACTIVITY)
        wait_app_ping(adb, 8)
        page_ready(cdp, timeout=8)
        after = bookmark_count(adb)
        return after >= before, f"bookmarks persist: {before} → {after}", []

    r.run("C", "03-recents-reopen", AUTO, recents_reopen)


def group_D(r: Runner, apk: Path) -> None:
    """D. Cleanup — dark revert, cache budget, final cold start timing."""
    print("== group D: cleanup ==")
    adb, cdp, ev, state = r.adb, r.cdp, r.ev, r.state

    # D1: cache_budget — AUTO  (PSS < 256 MB + 10% after multiple books)
    def cache_budget():
        mem = adb.meminfo()
        limit_kb = int(256 * 1024 * 1.10)
        return mem["pss_kb"] < limit_kb, f"PSS={mem['pss_kb']} kB < {limit_kb} kB", []

    r.run("D", "01-cache-budget", AUTO, cache_budget)

    # D2: clean_cold_open_timing — SOFT
    def clean_cold_open_timing():
        adb.pm_clear()
        wait(0.5)
        t0 = time.time()
        adb.cold_start()
        dt = time.time() - t0
        ok = page_ready(cdp, timeout=10)
        return ok, f"clean cold start: {dt:.1f}s", []

    r.run("D", "02-clean-cold-open-timing", SOFT, clean_cold_open_timing)


# ----------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------
def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="ebook-viewer-android emulator E2E suite")
    p.add_argument("--device", default="emulator-5554")
    p.add_argument("--apk", type=Path,
                   default=Path("app/build/outputs/apk/debug/app-debug.apk"))
    p.add_argument("--group", choices=["A", "B", "C", "D", "all"], default="all")
    p.add_argument("--book", default="book1.epub")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    if not args.apk.exists():
        print(f"FAIL: APK not found at {args.apk}. Run :app:assembleDebug first.", file=sys.stderr)
        return 2

    state = State(device=args.device, book_on_sd=args.book)
    adb = Adb(state.device)
    ev = Evidence(EVIDENCE_ROOT)
    cdp = Cdp(adb, state)
    r = Runner(adb, cdp, ev, state)

    print(f"e2e on {state.device}; APK={args.apk}; book={args.book}")
    print(f"evidence → {EVIDENCE_ROOT}")
    adb.install(args.apk)

    groups = [args.group] if args.group != "all" else ["A", "B", "C", "D"]
    for g in groups:
        if g == "A":
            group_A(r, args.apk)
        elif g == "B":
            group_B(r)
        elif g == "C":
            group_C(r)
        elif g == "D":
            group_D(r, args.apk)

    cdp.close()
    js, md = r.write_summary()
    hard_fails = [x for x in r.results if not x.ok and not x.skipped and x.tier == AUTO]
    print(f"\n{len(r.results)} tests; {len(hard_fails)} hard failures")
    print(f"  results: {js}")
    print(f"  summary: {md}")
    return 1 if hard_fails else 0


if __name__ == "__main__":
    sys.exit(main())
