#!/usr/bin/env python3
# License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
"""
Build viewer.js (and friends) from a calibre source checkout for the Android app.

Mirrors calibre's `compile_viewer()` (src/calibre/utils/rapydscript.py:660) with
three deliberate divergences, applied via the set_data placeholder map:

    __FAKE_PROTOCOL__  -> https
    __FAKE_HOST__      -> appassets.androidplatform.net
        (SANDBOX_HOST derives to appassets.androidplatform.sandbox in
         viewer-main.pyj, matching the hosts our WebViewClient intercepts)
    __EDITABLE_FORMATS__ -> []
        (no book editing on Android; hides the "edit book" affordances)
    runtime.is_standalone_viewer -> true
        (no calibre library to open a book from, no Qt host listening for
         overlay actions; setting this makes the JS delegate show_search,
         prefs, goto etc. through ui_operations instead of in-page overlays)

and two Android WebView compatibility adaptations:

    tools/compat.js -> start of viewer.js
        (ES5 shim for WebViews below Chromium 98 that lack
         window.structuredClone — frozen vendor WebViews on e-ink readers)

    dvh/dvw -> vh/vw in generated CSS strings and viewer.html
        (old vendor WebViews discard dynamic viewport-unit declarations;
         an embedded WebView has no browser chrome, so legacy viewport units
         have the same desired semantics and keep the reader full-height)

plus the e-ink adaptations (each an idempotent literal patch; a drifted
anchor fails the build rather than silently re-shipping color/motion):

    monochrome chrome palette — theme base constants, DEFAULT_COLORS
    chromatic entries and the primary-color default are neutralized to
    black/white (light and dark), so every derived surface and CSS variable
    renders grayscale on panels without color
    icon-select-all stroke -> currentColor (only hardcoded stroke color)
    1ms motion kill in index.html — never `none`: animationend listeners
    must still fire
    override_book_colors default "never" -> "always" — book content is
    binarized through apply_colors()'s override sheet (Preferences radio
    stays changeable; persisted user choices win)

Everything else — compiler invocation (external rapydscript CLI, beautified,
private scope, baselib included, tree-shaken, source-mapped), the single-pass
placeholder replacement of set_data(), and the icons/reset.css/base.css
composition of viewer.html — is byte-compatible with upstream so the output can
be regenerated from any future calibre commit without manual merging.

Usage:
    python3 tools/build_viewer_js.py [--calibre-src ~/workspace/calibre]

Requires: python3 + lxml, node + rapydscript-ng (npm i -g rapydscript-ng).
"""

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ASSET_DIR = REPO / 'app' / 'src' / 'main' / 'assets' / 'viewer'

# --- set_data() replacement table (mirror of rapydscript.py:617-629) ---------
# Values parsed from the calibre checkout at build time stay in sync with
# upstream automatically; the three overrides above are Android-specific.

SPECIAL_TITLE_RE = re.compile(r"^SPECIAL_TITLE_FOR_WEBENGINE_COMMS = '([^']+)'", re.M)
NO_SEARCH_LINK_RE = re.compile(r"^NO_SEARCH_LINK = '([^']+)'", re.M)
NUMERIC_VERSION_RE = re.compile(r'^numeric_version = \((\d+), (\d+), (\d+)\)', re.M)
CHARS_RE = re.compile(r'^CHARS_PER_LINE = (\d+)', re.M)
LINES_RE = re.compile(r'^LINES_PER_PAGE = (\d+)', re.M)
ANDROID_VIEWPORT_UNIT_RE = re.compile(r'(?<=\d)d(vh|vw)\b')
RUNTIME_TEMPLATE_PLACEHOLDERS = {
    '__ENTRY_POINT__', '__EXTRA__', '__FONT__', '__PRIMARY__', '__SCRIPT__', '__TOP__',
}


def die(msg: str) -> None:
    print(f'error: {msg}', file=sys.stderr)
    raise SystemExit(1)


def git_out(calibre: Path, *args: str) -> str:
    return subprocess.run(['git', '-C', str(calibre), *args],
                          capture_output=True, text=True, check=True).stdout.strip()


def parse_set_data_constants(calibre: Path) -> dict:
    src = calibre / 'src'
    constants = (src / 'calibre' / 'constants.py').read_text(encoding='utf-8')
    db_constants = (src / 'calibre' / 'db' / 'constants.py').read_text(encoding='utf-8')
    page_count = (src / 'calibre' / 'library' / 'page_count.py').read_text(encoding='utf-8')

    m = NUMERIC_VERSION_RE.search(constants)
    if not m:
        die('cannot find numeric_version in calibre/constants.py')
    version = '.'.join(m.groups())

    m = SPECIAL_TITLE_RE.search(constants)
    if not m:
        die('cannot find SPECIAL_TITLE_FOR_WEBENGINE_COMMS')
    special_title = m.group(1)

    m = NO_SEARCH_LINK_RE.search(db_constants)
    if not m:
        die('cannot find NO_SEARCH_LINK in calibre/db/constants.py')
    no_search_link = m.group(1)

    mc, ml = CHARS_RE.search(page_count), LINES_RE.search(page_count)
    if not (mc and ml):
        die('cannot find CHARS_PER_LINE/LINES_PER_PAGE in page_count.py')
    chars_per_page = int(mc.group(1)) * int(ml.group(1))

    # Extract the literal block dark_link_color .. builtin_decorations and exec
    # it in a scratch namespace (pure dict/str literals, no imports involved).
    start = constants.index('dark_link_color = ')
    end = constants.index('_osx_ver = None', start)
    ns: dict = {}
    exec(constants[start:end], ns)  # noqa: S102 - literal block we just sliced

    return {
        '__SPECIAL_TITLE__': special_title,
        '__FAKE_PROTOCOL__': 'https',                          # Android override
        '__FAKE_HOST__': 'appassets.androidplatform.net',      # Android override
        '__CALIBRE_VERSION__': version,
        '__DARK_LINK_COLOR__': ns['dark_link_color'],
        '__BUILTIN_COLORS_LIGHT__': json.dumps(ns['builtin_colors_light']),
        '__BUILTIN_COLORS_DARK__': json.dumps(ns['builtin_colors_dark']),
        '__BUILTIN_DECORATIONS__': json.dumps(ns['builtin_decorations']),
        '__NO_SEARCH_LINK__': no_search_link,
        '__CHARS_PER_PAGE__': str(chars_per_page),
        '__EDITABLE_FORMATS__': '[]',                          # Android override
    }


def merge_icons(calibre: Path) -> str:
    g: dict = {'__file__': str(calibre / 'imgsrc' / 'srv' / 'generate.py')}
    exec(open(g['__file__'], encoding='utf-8').read(), g)  # noqa: S102 - upstream build script
    return g['merge']()


def build_field_metadata(calibre: Path) -> dict:
    """Serialize FieldMetadata().all_metadata() (web_view.py:728 passes exactly
    this into create_view) without a working calibre runtime: the module only
    needs tweaks / icu.lower / gettext, which we stub with stdlib equivalents."""
    import types
    from collections import OrderedDict  # noqa: F401 - used by the exec'd source

    if 'calibre' not in sys.modules:
        pkg = types.ModuleType('calibre'); pkg.__path__ = []
        utils = types.ModuleType('calibre.utils'); utils.__path__ = []
        # defaults from calibre resources/default_tweaks.py:188-190
        config_base = types.ModuleType('calibre.utils.config_base')
        config_base.tweaks = {
            'gui_pubdate_display_format': 'MMM yyyy',
            'gui_timestamp_display_format': 'dd MMM yyyy',
            'gui_last_modified_display_format': 'dd MMM yyyy',
        }
        icu = types.ModuleType('calibre.utils.icu'); icu.lower = str.lower
        loc = types.ModuleType('calibre.utils.localization')
        loc._ = lambda s: s
        loc.ngettext = lambda s, p, n: s if n == 1 else p
        sys.modules.update({
            'calibre': pkg, 'calibre.utils': utils,
            'calibre.utils.config_base': config_base,
            'calibre.utils.icu': icu, 'calibre.utils.localization': loc,
        })

    src = (calibre / 'src' / 'calibre' / 'library' / 'field_metadata.py').read_text(encoding='utf-8')
    ns: dict = {'__name__': 'field_metadata_standalone'}
    exec(src, ns)  # noqa: S102 - calibre source we just read from the pinned checkout
    return ns['FieldMetadata']().all_metadata()


def compile_viewer(calibre: Path, rapydscript: str) -> tuple[str, str]:
    """Run the external rapydscript compiler exactly as compile_fast() does."""
    fname = calibre / 'src' / 'pyj' / 'viewer-main.pyj'
    with tempfile.TemporaryDirectory() as tdir:
        smap, js = os.path.join(tdir, 'smap'), os.path.join(tdir, 'js')
        args = [rapydscript, 'compile', '--cache-dir', tdir,
                '--filename-for-stdin', str(fname), '--tree-shaking',
                '--source-map', smap, '--source-map-line-offset', '0',
                '--output', js]
        p = subprocess.run(args, capture_output=True, input=fname.read_bytes())
        if p.returncode != 0:
            sys.stderr.write(p.stderr.decode('utf-8', 'replace'))
            die('rapydscript compile failed')
        return (Path(js).read_text(encoding='utf-8'),
                Path(smap).read_text(encoding='utf-8'))


def apply_set_data(code: str, table: dict) -> str:
    """Mirror set_data(): one replacement per placeholder, then sourceMappingURL."""
    for k, v in table.items():
        code = code.replace(k, v, 1)
    # Guard against upstream growing a placeholder we do not know about: an
    # unreplaced __SOME_CONSTANT__ token would ship silently inside viewer.js.
    placeholders = set(re.findall(r'__[A-Z][A-Z_]{2,}__', code))
    leftovers = placeholders - RUNTIME_TEMPLATE_PLACEHOLDERS
    if leftovers:
        die(f'unreplaced placeholder in viewer.js: {sorted(leftovers)[0]} '
            '(upstream set_data() grew — extend parse_set_data_constants)')
    code = code.rstrip()
    code = code.removesuffix('//# sourceMappingURL=smap')
    return code + '\n//# sourceMappingURL=viewer.js.map'


def use_android_viewport_units(text: str) -> str:
    """Replace dynamic viewport units unsupported by frozen e-ink WebViews.

    Unlike a mobile browser, the app-owned WebView has no collapsing address
    bar, so vh/vw describe exactly the viewport calibre needs here.
    """
    return ANDROID_VIEWPORT_UNIT_RE.sub(r'\1', text)


def patch_literal(js: str, old: str, new: str, what: str) -> str:
    """Replace exactly one occurrence of an upstream literal; die otherwise.

    The e-ink monochrome patches must never silently revert because upstream
    renamed a constant: a missing (or duplicated) anchor is a build failure,
    not a no-op that quietly ships color again.
    """
    count = js.count(old)
    if count != 1:
        die(f'{what}: anchor found {count} times (expected 1) — upstream changed it')
    return js.replace(old, new, 1)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()[:16]


def main() -> None:
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument('--calibre-src', default=os.path.expanduser('~/workspace/calibre'))
    ap.add_argument('--rapydscript', default='rapydscript')
    ap.add_argument('--expect-commit', default=None,
                    help='fail unless the calibre checkout is at this commit '
                         '(defaults to the commit recorded in BUILD_INFO.txt, '
                         'so rebuilding against a moved master is an error)')
    args = ap.parse_args()

    calibre = Path(args.calibre_src).resolve()
    if not (calibre / 'src' / 'pyj' / 'viewer-main.pyj').exists():
        die(f'{calibre} does not look like a calibre checkout')

    commit = git_out(calibre, 'rev-parse', 'HEAD')
    expected = args.expect_commit
    if expected is None and (ASSET_DIR / 'BUILD_INFO.txt').exists():
        for line in (ASSET_DIR / 'BUILD_INFO.txt').read_text(encoding='utf-8').splitlines():
            if line.startswith('calibre_commit='):
                expected = line.split('=', 1)[1].strip()
    if expected and commit != expected:
        die(f'calibre checkout is at {commit[:12]} but the pinned build expects '
            f'{expected[:12]} — pass --expect-commit {commit} to intentionally move')
    describe = git_out(calibre, 'describe', '--tags', '--always')
    rs_version = subprocess.run([args.rapydscript, '--version'],
                                capture_output=True, text=True).stdout.strip()

    print(f'calibre @ {describe} ({commit[:12]})')
    print(f'compiler: {args.rapydscript} {rs_version}')

    table = parse_set_data_constants(calibre)
    print(f'placeholders: version={table["__CALIBRE_VERSION__"]} '
          f'chars_per_page={table["__CHARS_PER_PAGE__"]}')

    code, source_map = compile_viewer(calibre, args.rapydscript)
    js = use_android_viewport_units(apply_set_data(code, table))
    # Preamble first so it is in place before any viewer.js code runs; see
    # the docstring for why this must stay ES5.
    compat = (REPO / 'tools' / 'compat.js').read_text(encoding='utf-8')
    js = compat + '\n' + js

    reset = use_android_viewport_units(
        (calibre / 'resources' / 'content-server' / 'reset.css').read_text(encoding='utf-8'))
    base_css = use_android_viewport_units(
        (calibre / 'resources' / 'content-server' / 'base.css').read_text(encoding='utf-8'))
    icons = merge_icons(calibre)
    # icon-select-all is the only symbol in the merged sprite with a hardcoded
    # stroke color (green); everything else inherits currentColor. E-ink
    # panels render it as an opaque mid-gray smudge — inherit like its peers.
    if icons.count('stroke:#30a82d') != 1:
        die('icon-select-all green stroke #30a82d not found exactly once in merged sprite '
            '(upstream changed it)')
    icons = icons.replace('stroke:#30a82d', 'stroke:currentColor', 1)
    # E-ink: every UI transition/animation completes in 1ms. Never
    # `transition: none` — viewer.js listens for animationend (link-hint
    # cleanup), and a suppressed animation never fires its end event; 1ms
    # keeps those listeners working while being visually instant on a panel
    # that repaints in ~100ms anyway. animation-iteration-count:1 freezes the
    # only infinite animation (the cs_spin_bd loading spinner).
    eink_motion_css = (
        '<style id="android-eink-motion">'
        ':root{--cs-dur-1:1ms !important;--cs-dur-2:1ms !important;--cs-dur-3:1ms !important;}'
        '*,*::before,*::after{transition-duration:1ms !important;'
        'animation-duration:1ms !important;animation-iteration-count:1 !important;}'
        '</style>'
    )
    # Same composition as compile_viewer()'s viewer.html, plus the two scripts
    # the Qt host would inject (translations placeholder, then viewer.js).
    #
    # Inject the Android WebView root font-size up front. Otherwise the
    # default Android root size (~32px on a 420dpi panel) gives every
    # 1.5em×n em descendant a "too big then small" flash before viewer.js's
    # create_view() sets documentElement.style.fontSize = "10pt" — the home
    # pitch and toolbar render at 48px for a few hundred ms, then collapse
    # to 20px. Mirror UiData.kt:put("ui_font_sz", "10pt") here.
    index_html = (
        '<!DOCTYPE html>\n<html><head>'
        '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">'
        '<style>html{font-size:10pt !important}</style>'
        f'<style>{reset}</style><style>{base_css}</style>{eink_motion_css}'
        '<script>window.calibre_translations_data=null</script>'
        f'</head><body>{icons}<script src="/viewer.js"></script></body></html>'
    )

    # We run as a standalone viewer (no calibre library, no Qt host).
    # The runtime object in viewer.js hard-codes is_standalone_viewer=false
    # because upstream ships a Qt browser shim that drives it; flipping it
    # makes the JS delegate overlays back to ui_operations — the bridge
    # channel the Android side uses for show_search, toggle_toc, prefs etc.
    if 'is_standalone_viewer"] = false' in js:
        js = js.replace('is_standalone_viewer"] = false', 'is_standalone_viewer"] = true', 1)

    # ---- toolbar actions: re-route Highlights / Bookmarks / Profiles / Bookmark this page to the host ----
    # Upstream wires these to JS-side overlays (bookmarks overlay, profiles
    # overlay, etc.). The host is the source of truth, so re-route each to
    # its ui_operations handler. Idempotent: each replacement only fires on
    # the original upstream literal.

    # Highlights toolbar item → ui_operations.toggle_highlights (with setTimeout fallback for late IIFE binds).
    if 'ac(_("Highlights"), _("Browse all highlights"), (function() {\n' in js and 'ui_operations.toggle_highlights' not in js.split('ac(_("Highlights")', 1)[1].split('})(), "image");', 1)[0]:
        js = js.replace(
            'ac(_("Highlights"), _("Browse all highlights"), (function() {\n'
            '                var ρσ_anonfunc = function () {\n'
            '                    self.overlay.show_highlights();\n'
            '                };\n'
            '                if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, {\n'
            '                    __module__ : {value: "read_book.overlay"}\n'
            '                });\n'
            '                return ρσ_anonfunc;\n'
            '            })(), "image");',
            'ac(_("Highlights"), _("Browse all highlights"), (function() {\n'
            '                var ρσ_anonfunc = function () {\n'
            '                    if (typeof ui_operations !== "undefined" && typeof ui_operations.toggle_highlights === "function") {\n'
            '                        ui_operations.toggle_highlights();\n'
            '                    } else {\n'
            '                        window.setTimeout(function () {\n'
            '                            if (typeof ui_operations !== "undefined" && typeof ui_operations.toggle_highlights === "function") {\n'
            '                                ui_operations.toggle_highlights();\n'
            '                            }\n'
            '                        }, 0);\n'
            '                    }\n'
            '                };\n'
            '                if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, {\n'
            '                    __module__ : {value: "read_book.overlay"}\n'
            '                });\n'
            '                return ρσ_anonfunc;\n'
            '            })(), "image");',
            1,
        )

    # Bookmarks toolbar item → ui_operations.toggle_bookmarks. Upstream
    # uses a direct method ref here (no IIFE), unlike Highlights/Profiles;
    # keep the `ac(...)` wrapper intact and only replace the inner arg.
    bookmarks_anchor = 'bookmarks_action = ac(_("Bookmarks")'
    if bookmarks_anchor in js:
        bookmarks_block = js.split(bookmarks_anchor, 1)[1].split(', reading_stats_action', 1)[0]
        if 'self.overlay.show_bookmarks, "bookmark");' in bookmarks_block and 'ui_operations.toggle_bookmarks' not in bookmarks_block:
            js = js.replace(
                'ac(_("Bookmarks"), _("Browse all bookmarks"), self.overlay.show_bookmarks, "bookmark");',
                'ac(_("Bookmarks"), _("Browse all bookmarks"), (function () {\n'
                '                var ρσ_anonfunc = function () {\n'
                '                    if (typeof ui_operations !== "undefined" && typeof ui_operations.toggle_bookmarks === "function") {\n'
                '                        ui_operations.toggle_bookmarks();\n'
                '                    } else {\n'
                '                        window.setTimeout(function () {\n'
                '                            if (typeof ui_operations !== "undefined" && typeof ui_operations.toggle_bookmarks === "function") {\n'
                '                                ui_operations.toggle_bookmarks();\n'
                '                            }\n'
                '                        }, 0);\n'
                '                    }\n'
                '                };\n'
                '                if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, { __module__ : {value: "read_book.overlay"} });\n'
                '                return ρσ_anonfunc;\n'
                '            })(), "bookmark");',
                1,
            )

    # "Bookmark this page" toolbar item — inserts a new action between
    # bookmarks_action and reading_stats_action. Idempotent: only when the
    # original `bookmarks_action, reading_stats_action` adjacent pair is
    # present.
    if ', bookmarks_action, reading_stats_action' in js and 'Bookmark this page' not in js:
        js = js.replace(
            'bookmarks_action, reading_stats_action',
            'bookmarks_action, ac(_("Bookmark this page"), _("Add a bookmark to the current page"), (function () {\n'
            '                var ρσ_anonfunc = function () {\n'
            '                    self.overlay.hide();\n'
            '                    self.overlay.view.new_bookmark();\n'
            '                };\n'
            '                if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, { __module__ : {value: "read_book.overlay"} });\n'
            '                return ρσ_anonfunc;\n'
            '            })(), "plus"), reading_stats_action',
            1,
        )

    # Profiles toolbar item → ui_operations.toggle_profiles (host ProfileStore
    # is the source of truth; the JS ProfilesOverlay reads a JS-local list).
    # Upstream wraps this `ac(...)` in `actions_div.appendChild(E.ul(...))`,
    # which yields THREE closing parens on the call site — match the
    # surrounding literal and rewrite the inner handler only.
    if 'ac(_("Profiles"), _("Quickly switch between different settings"), (function() {\n' in js and 'ui_operations.toggle_profiles' not in js.split('ac(_("Profiles")', 1)[1].split('})(), "convert")));', 1)[0]:
        js = js.replace(
            'ac(_("Profiles"), _("Quickly switch between different settings"), (function() {\n'
            '                var ρσ_anonfunc = function () {\n'
            '                    self.overlay.show_profiles();\n'
            '                };\n'
            '                if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, {\n'
            '                    __module__ : {value: "read_book.overlay"}\n'
            '                });\n'
            '                return ρσ_anonfunc;\n'
            '            })(), "convert")));',
            'ac(_("Profiles"), _("Quickly switch between different settings"), (function() {\n'
            '                var ρσ_anonfunc = function () {\n'
            '                    if (typeof ui_operations !== "undefined" && typeof ui_operations.toggle_profiles === "function") {\n'
            '                        ui_operations.toggle_profiles();\n'
            '                    } else {\n'
            '                        window.setTimeout(function () {\n'
            '                            if (typeof ui_operations !== "undefined" && typeof ui_operations.toggle_profiles === "function") {\n'
            '                                ui_operations.toggle_profiles();\n'
            '                            }\n'
            '                        }, 0);\n'
            '                    }\n'
            '                };\n'
            '                if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, {\n'
            '                    __module__ : {value: "read_book.overlay"}\n'
            '                });\n'
            '                return ρσ_anonfunc;\n'
            '            })(), "convert")));',
            1,
        )

    # ---- handle_shortcut: re-route toggle_bookmarks / toggle_highlights / show_profiles ----
    def shortcut_redirect(op: str) -> str:
        return (
            '            } else if (data.name === "%s") {\n'
            '                if (typeof ui_operations !== "undefined" && typeof ui_operations.%s === "function") {\n'
            '                    ui_operations.%s();\n'
            '                } else {\n'
            '                    window.setTimeout(function () {\n'
            '                        if (typeof ui_operations !== "undefined" && typeof ui_operations.%s === "function") {\n'
            '                            ui_operations.%s();\n'
            '                        }\n'
            '                    }, 0);\n'
            '                }'
        ) % (op, op, op, op, op)

    if 'data.name === "toggle_bookmarks"' in js:
        js = js.replace(
            '            } else if (data.name === "toggle_bookmarks") {\n                ui_operations.toggle_bookmarks();',
            shortcut_redirect('toggle_bookmarks'),
            1,
        )

    if 'data.name === "show_profiles"' in js and 'self.overlay.show_profiles();' in js:
        js = js.replace(
            '            } else if (data.name === "show_profiles") {\n                self.overlay.show_profiles();',
            shortcut_redirect('toggle_profiles'),
            1,
        )

    if 'data.name === "toggle_highlights"' in js:
        js = js.replace(
            '            } else if (data.name === "toggle_highlights") {\n                ui_operations.toggle_highlights();',
            shortcut_redirect('toggle_highlights'),
            1,
        )

    # ---- blank-tap closes secondary panels ----
    # Overlay.container_clicked dispatches to the current panel's
    # on_container_click when the panel defines one; MainOverlay (the top
    # menu) doesn't, so blank-area taps fall through to hide_current_panel()
    # and the menu closes. Upstream defines the secondary panels'
    # on_container_click as an empty no-op (desktop habit), which swallows
    # blank taps on touch. Give them the MainOverlay behaviour, but ignore
    # taps that landed on interactive content — those bubble up from form
    # controls to the container and must not dismiss. The guard selector
    # covers every clickable thing these panels render: form controls and
    # tables (Go to / prefs sub-pages), <a class="calibre-push-button"> push
    # buttons (create_button), <li> item-list rows (build_list attaches the
    # row action to the li — prefs categories, open-book recents, profiles),
    # <option> font-size quick marks (set_quick_size keeps the panel open),
    # svg bar charts (reading statistics) and the .main-top-bar close row.
    def blank_tap_close(cls: str, preguard: str = '') -> tuple[str, str]:
        old = (
            f'        {cls}.prototype.on_container_click = function on_container_click(evt) {{\n'
            f'            var self = this;\n'
            f'        }};'
        )
        new = (
            f'        {cls}.prototype.on_container_click = function on_container_click(evt) {{\n'
            f'            var self = this;\n'
            f'{preguard}'
            f'            if (evt && evt.target && evt.target.closest && evt.target.closest("form,table,input,select,textarea,button,a,option,li,svg,.main-top-bar,.simple-link")) {{\n'
            f'                return;\n'
            f'            }}\n'
            f'            self.overlay.hide_current_panel();\n'
            f'        }};'
        )
        return old, new

    for _cls in ('GotoOverlay', 'SimpleOverlay', 'PrefsOverlay', 'ReadingStatsOverlay'):
        _old, _new = blank_tap_close(_cls)
        if _old in js:
            js = js.replace(_old, _new, 1)

    # OpenBook: same blank-tap close, but only when a book is open behind it
    # (closeable=true — the toolbar "Open book" item). On the home page
    # (closeable=false) the panel is the only content; hiding it would leave
    # a blank screen, so keep the upstream no-op there.
    _old, _new = blank_tap_close('OpenBook', preguard='            if (!self.closeable) {\n                return;\n            }\n')
    if _old in js:
        js = js.replace(_old, _new, 1)

    # ---- toolbar layout: flatten the per-group <ul> wrappers ----
    # create_actions groups the buttons into several <ul> chunks (nav,
    # search/goto, reload, toc, bookmarks…, font/prefs, profiles, quit).
    # Under flex-wrap a row can only break between <ul>s, so some rows end
    # up sparse and others crowded. Lift every <li> out of its <ul> so all
    # buttons become direct children of the actions div and wrap
    # button-by-button (rows fill evenly). DOM-level, so it works on old
    # e-ink WebViews that lack display:contents.
    #
    # Critically, the next add_extra_css block must REWRITE main_overlay_css's
    # `...> ul > li > .action-control` selectors to `...> li > .action-control`
    # — otherwise the flatten leaves buttons outside any <ul>, the selector
    # chain misses, and Android WebView's UA <button> background (the
    # top-white-bottom-grey gradient the user sees) plus the unstyled icon
    # vertical alignment sneak back in.
    create_actions_return_literal = (
        '            return actions_div;\n'
        '        };\n'
        '        if (!MainOverlay.prototype.create_actions.__argnames__) Object.defineProperties(MainOverlay.prototype.create_actions, {'
    )
    create_actions_return_replacement = (
        '            (function () {\n'
        '                var uls = actions_div.querySelectorAll("ul");\n'
        '                for (var i = 0; i < uls.length; i++) {\n'
        '                    var ul = uls[i];\n'
        '                    while (ul.firstChild) {\n'
        '                        actions_div.appendChild(ul.firstChild);\n'
        '                    }\n'
        '                    if (ul.parentNode) {\n'
        '                        ul.parentNode.removeChild(ul);\n'
        '                    }\n'
        '                }\n'
        '            })();\n'
        '            return actions_div;\n'
        '        };\n'
        '        if (!MainOverlay.prototype.create_actions.__argnames__) Object.defineProperties(MainOverlay.prototype.create_actions, {'
    )
    if create_actions_return_literal in js:
        js = js.replace(create_actions_return_literal, create_actions_return_replacement, 1)

    # ---- menu cleanup: drop Full Screen, Print, Inspector, bottom Close/Help ----
    # Out of scope for the e-ink reader. Each deletion uses a concrete
    # upstream-anchored string; idempotent (matches only when the literal
    # is still present).

    # Drop the entire standalone-viewer Full Screen + Print block, plus
    # the else-branch desktop Full Screen block. Keep the autoscroll push
    # that follows. The exact upstream shape (line numbers vary across
    # calibre revisions, so we anchor on the trailing `full_screen_actions.push(ac(_("Print")`).
    standalone_fullscreen_block = (
        '            full_screen_actions = [];\n'
        '            if (runtime.is_standalone_viewer) {\n'
        '                text = (runtime.viewer_in_full_screen) ? _("Exit full screen") : _("Enter full screen");\n'
        '                full_screen_actions.push(ac(text, _("Toggle full screen mode"), (function() {\n'
        '                    var ρσ_anonfunc = function () {\n'
        '                        [self.overlay.hide(), ui_operations.toggle_full_screen()];\n'
        '                    };\n'
        '                    if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, {\n'
        '                        __module__ : {value: "read_book.overlay"}\n'
        '                    });\n'
        '                    return ρσ_anonfunc;\n'
        '                })(), "full-screen"));\n'
        '                full_screen_actions.push(ac(_("Print"), _("Print book to PDF"), (function() {\n'
        '                    var ρσ_anonfunc = function () {\n'
        '                        [self.overlay.hide(), ui_operations.print_book()];\n'
        '                    };\n'
        '                    if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, {\n'
        '                        __module__ : {value: "read_book.overlay"}\n'
        '                    });\n'
        '                    return ρσ_anonfunc;\n'
        '                })(), "print"));\n'
        '            } else {\n'
        '                if (!is_ios && full_screen_supported()) {\n'
        '                    text = (full_screen_element()) ? _("Exit full screen") : _("Enter full screen");\n'
        '                    full_screen_actions.push(ac(text, _("Toggle full screen mode"), (function() {\n'
        '                        var ρσ_anonfunc = function () {\n'
        '                            [self.overlay.hide(), ui_operations.toggle_full_screen()];\n'
        '                        };\n'
        '                        if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, {\n'
        '                            __module__ : {value: "read_book.overlay"}\n'
        '                        });\n'
        '                        return ρσ_anonfunc;\n'
        '                    })(), "full-screen"));\n'
        '                }\n'
        '            }\n'
    )
    if 'full_screen_actions = [];' in js and 'full_screen_actions.push(ac(_("Print")' in js:
        js = js.replace(standalone_fullscreen_block, '            full_screen_actions = [];\n', 1)

    # Drop BOTH the Inspector and Reset interface entries from the
    # standalone footer E.ul(...). The only survivor is the Quit item that
    # follows. Match a literal block anchored on the leading
    # `actions_div.appendChild(E.ul(ac(_("Inspector")...` so this only fires
    # on the unpatched upstream build (idempotent).
    inspector_and_reset_literal = (
        '                actions_div.appendChild(E.ul(ac(_("Inspector"), _("Show the content inspector"), (function() {\n'
        '                    var ρσ_anonfunc = function () {\n'
        '                        [self.overlay.hide(), ui_operations.toggle_inspector()];\n'
        '                    };\n'
        '                    if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, {\n'
        '                        __module__ : {value: "read_book.overlay"}\n'
        '                    });\n'
        '                    return ρσ_anonfunc;\n'
        '                })(), "bug"), ac(_("Reset interface"), _("Reset E-book viewer panels, toolbars and scrollbars to defaults"), (function() {\n'
        '                    var ρσ_anonfunc = function () {\n'
        '                        question_dialog(_("Are you sure?"), _("Are you sure you want to reset the viewer interface to its default appearance?"), (function() {\n'
        '                            var ρσ_anonfunc = function (yes) {\n'
        '                                var sd;\n'
        '                                if (yes) {\n'
        '                                    self.overlay.hide();\n'
        '                                    ui_operations.reset_interface();\n'
        '                                    sd = get_session_data();\n'
        '                                    sd.set("skipped_dialogs", session_defaults().skipped_dialogs);\n'
        '                                }\n'
        '                            };\n'
        '                            if (!ρσ_anonfunc.__argnames__) Object.defineProperties(ρσ_anonfunc, {\n'
        '                                __argnames__ : {value: ["yes"]},\n'
        '                                __module__ : {value: "read_book.overlay"}\n'
        '                            });\n'
        '                            return ρσ_anonfunc;\n'
        '                        })());\n'
        '                    };\n'
        '                    if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, {\n'
        '                        __module__ : {value: "read_book.overlay"}\n'
        '                    });\n'
        '                    return ρσ_anonfunc;\n'
        '                })(), "window-restore"), ac(_("Quit")'
    )
    if inspector_and_reset_literal in js:
        js = js.replace(
            inspector_and_reset_literal,
            '                actions_div.appendChild(E.ul(ac(_("Quit")',
            1,
        )

    # Drop the entire create_main_overlay_footer footer shell — there is no
    # bottom Close/Help on Android. Replace with empty-body stub so callers
    # that reference the function still get the closure return value.
    if 'create_main_overlay_footer_action("close"' in js:
        js = re.sub(
            r'        function create_main_overlay_footer\(close_action, help_action\) \{.*?\n        \};',
            '        function create_main_overlay_footer(close_action, help_action) {\n'
            '            var middle = ρσ_interpolate_kwargs.call(E, E.div, [" "].concat([ρσ_desugar_kwargs({class_: " ".join([MAIN_OVERLAY_FOOTER_SLOT_CLASS, MAIN_OVERLAY_FOOTER_MIDDLE_SLOT_CLASS])})]));\n'
            '            var right = ρσ_interpolate_kwargs.call(E, E.div, [" "].concat([ρσ_desugar_kwargs({class_: " ".join([MAIN_OVERLAY_FOOTER_SLOT_CLASS, MAIN_OVERLAY_FOOTER_RIGHT_SLOT_CLASS])})]));\n'
            '            return (function () {\n'
            '                var ρσ_d = Object.create(null);\n'
            '                ρσ_d["footer"] = middle;\n'
            '                ρσ_d["middle_slot"] = middle;\n'
            '                ρσ_d["right_slot"] = right;\n'
            '                return ρσ_d;\n'
            '            }).call(this);\n'
            '        };',
            js,
            count=1,
            flags=re.DOTALL,
        )

    # ---- recents list cap: 25 → 20 ----
    js = js.replace('sd.set("standalone_recently_opened", ans.slice(0, 25));',
                    'sd.set("standalone_recently_opened", ans.slice(0, 20));', 1)

    # ---- font-size chooser: 7 marks, range 8–56, linear step ----
    # Replaces upstream's [10, 12, 14, 16, 18, 20, 22] (narrow 10–22 range)
    # with [8, 16, 24, 32, 40, 48, 56] (7 marks, min 8, max 56). Anchored by
    # the VALUE LIST, not the ρσ_Iter<N> name — that counter is compiler-
    # generated and shifts between calibre checkouts, which would make an
    # exact-match anchor silently no-op. Idempotent: the pattern only
    # matches the original 7-value literal.
    js, _marks_patched = re.subn(
        r'(var ρσ_Iter\d+ = \[) 10, 12, 14, 16, 18, 20, 22 (\];)',
        r'\g<1> 8, 16, 24, 32, 40, 48, 56 \g<2>',
        js,
        count=1,
    )

    # ---- default base_font_size: 32 (was 16) for the Android reader ----
    # The page's standalone_font_settings["base_font_size"].default is the
    # starting font size used by Viewer.bump_font_size and the Font size
    # chooser. Bumping it from 16 to 32 makes the page text start at 2× the
    # platform default (the WebView sets a 10pt base; 32px ≈ 24pt rendered,
    # comfortable for e-ink readers). Idempotent by anchor literal.
    base_font_default_literal = '            ρσ_d["base_font_size"] = (function(){\n                var ρσ_d = Object.create(null);\n                ρσ_d["default"] = 16;\n                ρσ_d["category"] = "read_book";\n                ρσ_d["is_local"] = true;\n                return ρσ_d;\n            }).call(this);'
    base_font_default_replacement = '            ρσ_d["base_font_size"] = (function(){\n                var ρσ_d = Object.create(null);\n                ρσ_d["default"] = 32;\n                ρσ_d["category"] = "read_book";\n                ρσ_d["is_local"] = true;\n                return ρσ_d;\n            }).call(this);'
    if base_font_default_literal in js:
        js = js.replace(base_font_default_literal, base_font_default_replacement, 1)

    # ---- Go-to / Preferences secondary-list row alignment ----
    # Both screens are built with book_list.item_list. Upstream aligns each
    # row to flex-start and adds top padding to the subtitle, which makes the
    # left title and right description look vertically staggered. Give only
    # these two lists semantic hooks so the Android CSS below can align the
    # contents inside each row. The lists themselves retain upstream's normal
    # top-to-bottom flow and remain aligned to the top of the secondary page.
    js = patch_literal(
        js,
        '            panel = create_goto_list(onclick, current_position_data, book.manifest.page_list, show_sub_panel);\n'
        '            ρσ_interpolate_kwargs.call(this, set_css, [container].concat([ρσ_desugar_kwargs({display: "flex", flex_direction: "column"})]));',
        '            panel = create_goto_list(onclick, current_position_data, book.manifest.page_list, show_sub_panel);\n'
        '            panel.classList.add("android-aligned-secondary-list");\n'
        '            ρσ_interpolate_kwargs.call(this, set_css, [container].concat([ρσ_desugar_kwargs({display: "flex", flex_direction: "column"})]));',
        'Go-to secondary-list hooks',
    )
    js = patch_literal(
        js,
        '            self.set_top_title(self.main_title);\n'
        '            c = E.div();\n'
        '            container.appendChild(c);',
        '            self.set_top_title(self.main_title);\n'
        '            ρσ_interpolate_kwargs.call(this, set_css, [container].concat([ρσ_desugar_kwargs({display: "flex", flex_direction: "column"})]));\n'
        '            c = E.div();\n'
        '            c.classList.add("android-aligned-secondary-list");\n'
        '            container.appendChild(c);',
        'Preferences secondary-list hooks',
    )

    # ---- Declare set_css in the read_book.prefs.main IIFE ----
    # The Preferences secondary-list hook (above) calls `set_css` unqualified,
    # but the module-scope declarations of this RapydScript IIFE only import
    # `clear` and `ensure_id` from ρσ_modules.dom — `set_css` was missing.
    # Without this declaration, clicking Preferences throws
    # `ReferenceError: set_css is not defined` inside Prefs.prototype.display_top.
    # Every other RapydScript module that calls set_css has its own
    # `var set_css = ρσ_modules.dom.set_css;` at the top; we mirror that.
    js = patch_literal(
        js,
        '        var clear = ρσ_modules.dom.clear;\n'
        '        var ensure_id = ρσ_modules.dom.ensure_id;\n'
        '\n'
        '        var runtime = ρσ_modules["read_book.globals"].runtime;',
        '        var clear = ρσ_modules.dom.clear;\n'
        '        var ensure_id = ρσ_modules.dom.ensure_id;\n'
        '        var set_css = ρσ_modules.dom.set_css;\n'
        '\n'
        '        var runtime = ρσ_modules["read_book.globals"].runtime;',
        'prefs.main IIFE — declare set_css',
    )

    # ---- global JS-side UI scale: 1.5× everything that viewer.js renders ----
    # Native Compose panels are already scaled via UiScale.kt; this matches
    # that for the toolbar overlay, prefs panel, search/lookup widgets,
    # font-size marks, reading-stats overlay, profile overlay, etc. Inject
    # through the same `add_extra_css(function(){ return "..." })` channel
    # as the existing flex-wrap rule so it parses through `get_widget_css`.
    # Idempotent by sentinel.
    if '.read-book-main-overlay-actions,.read-book-main-overlay-actions>ul>li{font-size:1em !important;}' not in js:
        # Strip any prior UI-scale injection (1.5em variants) so the new
        # single-root rule can land cleanly on a fresh build.
        js = re.sub(
            r'        // __android_js_ui_scale__\n'
            r'        add_extra_css\(function \(\) \{.*?\n        \}\);',
            '',
            js,
            count=1,
            flags=re.DOTALL,
        )
        ui_scale_rule = (
            '        // __android_js_ui_scale__\n'
            '        add_extra_css(function () {\n'
            '            return [\n'
            '                // Two independent 1.5× roots: #book-overlay (the Overlay\n'
            '                // container getter — the home open-book page AND the reader\n'
            '                // top-menu / secondary panels all render inside it) and the\n'
            '                // body-level modal-container (popup dialogs). The toolbar\n'
            '                // top-section is deliberately NOT a root: it is always a\n'
            '                // descendant of #book-overlay, so scaling it here as well\n'
            '                // would multiply (1.5 × 1.5). Every nested rule below uses\n'
            '                // 1em so font-size inherits exactly once.\n'
            '                "#book-overlay,.modal-container{font-size:1.5em !important;}",\n'
            '                ".read-book-main-overlay-action-icon{width:1.5em !important;height:1.5em !important;}",\n'
            '                // Each toolbar button (icon + label) — center them vertically\n'
            '                // so the 175%-sized icon and the regular label align at\n'
            '                // their middles, not their baselines (which made the row\n'
            '                // blow up and the icon float above the text).\n'
            '                ".read-book-main-overlay-action-control{display:inline-flex !important;align-items:center !important;justify-content:center !important;gap:0.4em !important;}",\n'
            '                // The rest are 1em (or smaller structural touches) so they\n'
            '                // inherit each root\'s 1.5× exactly once.\n'
            '                ".read-book-main-overlay-actions,.read-book-main-overlay-actions>ul>li{font-size:1em !important;}",\n'
            '                ".read-book-main-overlay-title{font-size:1em !important;}",\n'
            '                ".read-book-main-overlay-top-row,.read-book-main-overlay-footer{font-size:1em !important;}",\n'
            '                ".simple-panel{font-size:1em !important;}",\n'
            '                // Every calibre push button (home pitch "Open a book", form OK/Cancel, etc.).\n'
            '                ".calibre-push-button{font-size:1em !important;padding:0.5em 1em !important;}",\n'
            '                // Standalone home open-book pitch + recents list rows + tree lists.\n'
            '                ".calibre-container,.calibre-tree,.simple-tree,.tree-item-title,.simple-link{font-size:1em !important;}",\n'
            '                // Title bar (used by home + every secondary page).\n'
            '                ".main-top-bar,.top-bar-title{font-size:1em !important;}",\n'
            '                // Modal dialogs (confirm/quit prompts).\n'
            '                ".button-box{font-size:1em !important;}",\n'
            '                // Font-size chooser marks: enlarge the Aa glyphs so each is tappable on e-ink.\n'
            '                ".read-book-font-size-quick>option{padding:0.5em 0.6em !important;margin:0 0.25em !important;}",\n'
            '                // Reading stats overlay + auto-scroll message use this class.\n'
            '                ".read-book-overlay-aux-text{font-size:1em !important;}",\n'
            '                // Form elements inside overlay containers (Go to, Preferences,\n'
            '                // Font size, Reading statistics, Lookup). These have no stable\n'
            '                // class — they\'re plain <input>/<select>/<button>/<td> elements.\n'
            '                // Force them to inherit the 1.5× from the root and bump their\n'
            '                // touch targets.\n'
            '                "#book-overlay input,#book-overlay select,#book-overlay textarea,#book-overlay button,#book-overlay td,#book-overlay th,#book-overlay label,#book-overlay h1,#book-overlay h2,#book-overlay h3{font-size:1em !important;padding:0.25em 0.5em !important;}",\n'
            '                ".modal-container input,.modal-container select,.modal-container textarea,.modal-container button,.modal-container td,.modal-container th,.modal-container label,.modal-container h1,.modal-container h2,.modal-container h3{font-size:1em !important;padding:0.25em 0.5em !important;}",\n'
            '                // Keep label/control cells centered within real form rows.\n'
            '                "#book-overlay td,#book-overlay th{vertical-align:middle !important;padding:0.4em 0.6em !important;}",\n'
            '                // Go-to and Preferences use the same generic title/subtitle\n'
            '                // list. Keep every card in normal top-aligned document flow,\n'
            '                // while aligning each row\'s two columns by their middles.\n'
            '                // Do not change #book-overlay\'s display: its inline block/none\n'
            '                // value is the reader\'s visibility state machine.\n'
            '                ".android-aligned-secondary-list .item-first-row{align-items:center !important;}",\n'
            '                ".android-aligned-secondary-list .item-subtitle{padding-top:0 !important;}",\n'
            '                // The list itself, top-aligned in its (now flex-column) parent.\n'
            '                // flex-grow:0 overrides Go-to\'s inline flex_grow:10 so the list\n'
            '                // shrinks to its content height instead of stretching and pushing\n'
            '                // itself off-screen. No margin-top/bottom auto: the user wants\n'
            '                // the entries anchored to the top of the panel, not centered.\n'
            '                ".android-aligned-secondary-list{flex-grow:0 !important;}",\n'
            '                // Generic fallback for any leftover viewer.js element.\n'
            '                "body .read-book-overlay,body .read-book-overlay *{font-size:inherit;}",\n'
            '                // Universal bump: every non-input, non-script element inside\n'
            '                // the reader containers inherits the root\'s 1.5× via this\n'
            '                // 1em chain. Re-stating with the wildcard guarantees every\n'
            '                // bare <div>/<span>/<td>/<li>/<a>/<button>/<p> gets the\n'
            '                // inheritance — the only safe way to catch the no-class\n'
            '                // elements (secondary-page forms, the open-book pitch, the\n'
            '                // Go-to list rows, etc.).\n'
            '                ".read-book-main-overlay-top-section *:not(input):not(select):not(textarea):not(script),#book-overlay *:not(input):not(select):not(textarea):not(script),.modal-container *:not(input):not(select):not(textarea):not(script){font-size:1em !important;}",\n'
            '            ].join("\\n");\n'
            '        });'
        )
        # Anchor on `add_extra_css(main_overlay_css);` (the always-present
        # upstream call) and append BOTH the flex-wrap rule (rewritten for
        # the flattened DOM — `> li >` instead of `> ul > li >`) and the
        # global JS-side UI scale rule in one replace.
        #
        # Three things were broken by the earlier `> ul > li >` selectors
        # after the flatten:
        #   1. button background fell back to the Android WebView UA
        #      default (the top-white/bottom-grey gradient the user sees).
        #      `.read-book-main-overlay-action-control` styles never
        #      matched because the <ul> wrapper was stripped.
        #   2. Aa text-icon (a <span> instead of <svg>) sat at the
        #      baseline of the row instead of vertically centered, because
        #      its parent flex lost the upstream `align-items: center`.
        #   3. The top-row clock (max-width: 9%) got squeezed off the
        #      right edge once the title takes ~90%; bumped to 22%.
        js = js.replace(
            'add_extra_css(main_overlay_css);',
            'add_extra_css(main_overlay_css);\n'
            '        add_extra_css(function () {\n'
            '            return [\n'
            '                // The flattened DOM: <li> is now a direct child\n'
            '                // of the actions div (no <ul>). Rewriting the\n'
            '                // selector chain is what makes the button\n'
            '                // background/border/icon-alignment CSS match\n'
            '                // again — without this the Android WebView UA\n'
            '                // button background bleeds through.\n'
            '                ".read-book-main-overlay-actions{display:flex !important;flex-wrap:wrap !important;align-items:stretch !important;gap:0 !important;padding:0 4px !important;margin:0 !important;}",\n'
            '                ".read-book-main-overlay-actions>ul{display:inline-flex !important;flex-wrap:wrap !important;flex:0 0 auto !important;min-width:0 !important;align-items:stretch !important;border:0 !important;border-bottom:0 !important;padding:0 !important;margin:0 !important;}",\n'
            '                ".read-book-main-overlay-actions>ul>li,.read-book-main-overlay-actions>li{display:inline-flex !important;flex:0 0 auto !important;border:0 !important;margin:0 !important;}",\n'
            '                ".read-book-main-overlay-action-control{padding:0.5em 1ex !important;}",\n'
            '                // Top-row clock: give the time enough room that\n'
            '                // it is not clipped at the right edge.\n'
            '                ".read-book-main-overlay-timer{max-width:22% !important;}",\n'
            '                // Aa text-icon: vertical-center inside its flex button\n'
            '                // parent so the 175% bold Latin glyph aligns\n'
            '                // with the regular-weight CJK label text on\n'
            '                // its middle, not on its baseline. line-height:1\n'
            '                // + inline-block keep the glyph bounding box\n'
            '                // exactly font-size tall so the flex center\n'
            '                // calculation lines up.\n'
            '                ".read-book-main-overlay-action-control{align-items:center !important;display:inline-flex !important;gap:0.4em !important;}",\n'
            '                ".read-book-main-overlay-text-icon{align-self:center !important;line-height:1 !important;display:inline-block !important;}",\n'
            '                ".read-book-main-overlay-action-icon{align-self:center !important;}",\n'
            '                // Toolbar buttons: neutralize the Android WebView\n'
            '                // UA <button> chrome (default grey gradient\n'
            '                // background, inherited color, native appearance)\n'
            '                // that bleeds through once the upstream selector\n'
            '                // chain (which assumed an <ul> wrapper) misses\n'
            '                // after the flatten. Color inherits so hover /\n'
            '                // active rules upstream drive the per-button tint.\n'
            '                // A 1px black outline + the same padding the\n'
            '                // colored-version buttons used to have so the\n'
            '                // tappable area stays unchanged.\n'
            '                ".read-book-main-overlay-action-control{appearance:none !important;-webkit-appearance:none !important;background:transparent !important;background-image:none !important;color:inherit !important;border:1px solid #000 !important;padding:0.4em 1ex !important;margin:2px !important;}",\n'
            '            ].join("");\n'
            '        });\n' + ui_scale_rule,
            1,
        )

    # ---- Read aloud removal ----
    # Drop the "Read aloud" item from the toolbar actions list. Out of
    # scope for the Android shell; would need AndroidTts reader-flow
    # integration that punts to a follow-up milestone. Idempotent: the
    # literal exists only in the unpatched upstream build. The block is
    # wrapped in `actions_div.appendChild(E.ul(ac(...), <NEIGHBOR>))` — match
    # just the Read aloud `ac(...)` plus the trailing comma and whitespace
    # so the neighbor (Profiles) is not damaged.
    if 'actions_div.appendChild(E.ul(ac(_("Read aloud")' in js:
        # The block ends with `})(), "bullhorn"), ` (closing the ac() call,
        # then the E.ul argument list's trailing comma). Drop exactly that.
        read_aloud_ac = (
            'ac(_("Read aloud"), _("Read the book aloud"), (function() {\n'
            '                var ρσ_anonfunc = function () {\n'
            '                    self.overlay.hide();\n'
            '                    self.overlay.view.start_read_aloud();\n'
            '                };\n'
            '                if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, {\n'
            '                    __module__ : {value: "read_book.overlay"}\n'
            '                });\n'
            '                return ρσ_anonfunc;\n'
            '            })(), "bullhorn"), '
        )
        js = js.replace(read_aloud_ac, '', 1)
    # Also strip the single-letter shortcut entry (idempotent: only fires
    # when the un-commented literal is present).
    js = js.replace(
        'ρσ_d["read_aloud"] = desc("Ctrl+s", "ui", _("Read aloud"));',
        '// ρσ_d["read_aloud"] = desc("Ctrl+s", "ui", _("Read aloud"));  // removed for Android',
        1,
    )

    # ---- ui_operations.toggle_profiles binding ----
    # Upstream binds `ui_operations.toggle_bookmarks`, `toggle_highlights`,
    # `toggle_toc` etc. via the same IIFE pattern, but does NOT bind
    # `toggle_profiles` — that one is defined inside `ui_operations` only
    # when the JS-side ProfilesOverlay is opened. The Android toolbar / shortcut
    # patches therefore find `typeof ui_operations.toggle_profiles === "function"`
    # always false and the panel never opens. Add the binding alongside
    # toggle_highlights so the existing re-routes (toolbar item + handle_shortcut
    # branch) can call it. Idempotent: the literal is unique.
    if 'ui_operations.toggle_highlights = (function() {' in js and 'ui_operations.toggle_profiles = (function() {' not in js:
        js = js.replace(
            '            ui_operations.toggle_highlights = (function() {\n'
            '                var ρσ_anonfunc = function () {\n'
            '                    to_python.toggle_highlights();\n'
            '                };\n'
            '                if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, {\n'
            '                    __module__ : {value: null}\n'
            '                });\n'
            '                return ρσ_anonfunc;\n'
            '            })();\n'
            '            ui_operations.new_bookmark',
            '            ui_operations.toggle_highlights = (function() {\n'
            '                var ρσ_anonfunc = function () {\n'
            '                    to_python.toggle_highlights();\n'
            '                };\n'
            '                if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, {\n'
            '                    __module__ : {value: null}\n'
            '                });\n'
            '                return ρσ_anonfunc;\n'
            '            })();\n'
            '            ui_operations.toggle_profiles = (function() {\n'
            '                var ρσ_anonfunc = function () {\n'
            '                    to_python.toggle_profiles();\n'
            '                };\n'
            '                if (!ρσ_anonfunc.__module__) Object.defineProperties(ρσ_anonfunc, {\n'
            '                    __module__ : {value: null}\n'
            '                });\n'
            '                return ρσ_anonfunc;\n'
            '            })();\n'
            '            ui_operations.new_bookmark',
            1,
        )

    # ---- JS-side profile save wrapper ----
    # The JS ProfilesOverlay opens with `ui_operations.save_profile = save_profile`
    # (a JS-local callback that prints "Saved: <name>" inline). Wrap it so
    # every save/delete also forwards `to_python.profile_op("save-profile",
    # name, settings)` to the host ProfileStore. Host is the source of
    # truth; delete_profile on the JS side passes null as settings, which
    # the host interprets as delete. Idempotent — only fires when the
    # original assignment literal is still present in viewer.js.
    if 'ui_operations.save_profile = save_profile;' in js:
        js = js.replace(
            (
                '            ui_operations.get_all_profiles = get_all_profiles;\n'
                '            ui_operations.save_profile = save_profile;'
            ),
            (
                '            ui_operations.get_all_profiles = get_all_profiles;\n'
                '            // Android host override: forward save/delete to the host\n'
                '            // ProfileStore via profile_op; the original JS save still\n'
                '            // runs alongside so the inline "Saved: <name>" confirmation\n'
                '            // renders for the JS panel.\n'
                '            ui_operations.save_profile = (function () {\n'
                '                var orig = save_profile;\n'
                '                var host_save = function (container_id, hide_panel) {\n'
                '                    var container = document.getElementById(container_id);\n'
                '                    if (!container) { return; }\n'
                '                    var name = container.querySelector("[name=profile_name]").value;\n'
                '                    var sd = get_session_data();\n'
                '                    var settings = settings_for_reader_profile(sd);\n'
                '                    var payload = (settings === null) ? null : settings;\n'
                '                    to_python.profile_op("save-profile", name, payload);\n'
                '                };\n'
                '                var wrap = function (container_id, hide_panel) {\n'
                '                    host_save(container_id, hide_panel);\n'
                '                    return orig.apply(this, arguments);\n'
                '                };\n'
                '                if (!wrap.__argnames__) Object.defineProperties(wrap, {\n'
                '                    __argnames__ : {value: ["container_id", "hide_panel"]},\n'
                '                    __module__ : {value: "read_book.profiles"}\n'
                '                });\n'
                '                return wrap;\n'
                '            })();'
            ),
            1,
        )


    # ---- e-ink: monochrome chrome palette ----
    # The target devices are grayscale panels: calibre's warm sepia chrome
    # (cream #F6F3E9 / brown #39322B) and its green primary rasterize as
    # muddy mid-grays. Neutralize the compiled-in defaults so every derived
    # surface (window/dialog/button/input colors, hover tints via
    # light_mode_primary_tints, --calibre-color-primary* variables driving
    # hover/focus/::selection/accent-color/progress bars) becomes pure
    # black-on-white (light) / white-on-black (dark). The book-content sepia
    # color-scheme presets elsewhere in the bundle use ρσ_d["..."] forms and
    # are deliberately untouched. Every anchor below is unique in the
    # compiled output (verified); a drifted anchor fails the build.

    # Base constants of book_list.theme — light fg/bg pair and dark pair.
    js = patch_literal(
        js,
        '        DARK = "#39322B";\n'
        '        LIGHT = "#F6F3E9";\n'
        '        LIGHT_DARKER = "#b6b3a8";\n'
        '        DT_DARK = "#2d2d2d";\n'
        '        DT_DARK_DARKER = "black";\n'
        '        DT_DARK_LIGHTER = "#777";\n'
        '        DT_DARK_LIGTHER_CONTRAST = "#1d1d1d";\n'
        '        DT_LIGHT = "#ddd";\n',
        '        DARK = "#000000";\n'
        '        LIGHT = "#ffffff";\n'
        '        LIGHT_DARKER = "#d8d8d8";\n'
        '        DT_DARK = "#000000";\n'
        '        DT_DARK_DARKER = "black";\n'
        '        DT_DARK_LIGHTER = "#777";\n'
        '        DT_DARK_LIGTHER_CONTRAST = "#1d1d1d";\n'
        '        DT_LIGHT = "#ffffff";\n',
        'theme base constants',
    )

    # Chromatic entries in DEFAULT_COLORS. c(light, dark) references the
    # module constants patched above, so error/hover/link/heart/outline all
    # become fg-colored. bar-highlight must differ from both the (black) bar
    # track and the (white) bar fill in light mode — mid-gray does; white in
    # dark mode (track #777, fill #1d1d1d). input-* drop the deprecated CSS
    # system-color keywords ("field"/"fieldtext") for deterministic values.
    for _old, _new, _what in (
        ('            ρσ_d["window-error-foreground"] = c("red", "#C40233");\n',
         '            ρσ_d["window-error-foreground"] = c(DARK, DT_LIGHT);\n',
         'window-error-foreground'),
        ('            ρσ_d["window-hover-foreground"] = c("red", "#C40233");\n',
         '            ρσ_d["window-hover-foreground"] = c(DARK, DT_LIGHT);\n',
         'window-hover-foreground'),
        ('            ρσ_d["link-foreground"] = c("blue", "#6cb4ee");\n',
         '            ρσ_d["link-foreground"] = c(DARK, DT_LIGHT);\n',
         'link-foreground'),
        ('            ρσ_d["bar-highlight"] = c("yellow");\n',
         '            ρσ_d["bar-highlight"] = c("#888888", "#ffffff");\n',
         'bar-highlight'),
        ('            ρσ_d["heart"] = c("#B92111");\n',
         '            ρσ_d["heart"] = c(DARK, DT_LIGHT);\n',
         'heart'),
        ('            ρσ_d["button-end"] = c("#49423B", "#666");\n',
         '            ρσ_d["button-end"] = c("#333333", "#666");\n',
         'button-end'),
        ('            ρσ_d["input-background"] = c("field", DT_DARK_DARKER);\n',
         '            ρσ_d["input-background"] = c("#ffffff", DT_DARK_DARKER);\n',
         'input-background'),
        ('            ρσ_d["input-foreground"] = c("fieldtext", DT_LIGHT);\n',
         '            ρσ_d["input-foreground"] = c("#000000", DT_LIGHT);\n',
         'input-foreground'),
        ('            ρσ_d["input-focus-outline-color"] = c("#4D90FE", DT_LIGHT);\n',
         '            ρσ_d["input-focus-outline-color"] = c(DARK, DT_LIGHT);\n',
         'input-focus-outline-color'),
    ):
        js = patch_literal(js, _old, _new, _what)

    # Primary color default. In standalone mode both set_ui_colors call
    # sites read ui_highlight_colors.default directly; this single literal
    # therefore drives --calibre-color-primary* everywhere AND the light-mode
    # tint math (light_mode_primary_tints mixes white with the primary to
    # recolor window/hover/input/button surfaces — the source of today's
    # green cast). Black primary in light mode tints those surfaces to
    # neutral grays (window bg #f1f1f1, hover #d4d4d4); white primary in
    # dark mode does the mirrored thing.
    js = patch_literal(
        js,
        '                    ρσ_d["light"] = "#437000";\n'
        '                    ρσ_d["dark"] = "#6cb4ee";\n',
        '                    ρσ_d["light"] = "#000000";\n'
        '                    ρσ_d["dark"] = "#ffffff";\n',
        'ui_highlight_colors default primary',
    )

    # ---- e-ink: force book-content color binarization by default ----
    # apply_colors() writes the * { color: fg !important; background-color:
    # bg !important; border-color: fg !important } override sheet whenever
    # override_book_colors != 'never'; fg/bg come from the selected scheme,
    # and the host already pushes a pure black/white "system" palette. The
    # Preferences radio (never / dark / always) reads this same default and
    # stays changeable; users who explicitly chose otherwise have the key
    # persisted in session_data.json and are unaffected. Same pattern as the
    # base_font_size 16→32 default bump above.
    js = patch_literal(
        js,
        '            ρσ_d["override_book_colors"] = (function(){\n'
        '                var ρσ_d = Object.create(null);\n'
        '                ρσ_d["default"] = "never";\n'
        '                ρσ_d["category"] = "read_book";\n'
        '                return ρσ_d;\n'
        '            }).call(this);\n',
        '            ρσ_d["override_book_colors"] = (function(){\n'
        '                var ρσ_d = Object.create(null);\n'
        '                ρσ_d["default"] = "always";\n'
        '                ρσ_d["category"] = "read_book";\n'
        '                return ρσ_d;\n'
        '            }).call(this);\n',
        'override_book_colors default',
    )


    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    (ASSET_DIR / 'viewer.js').write_text(js, encoding='utf-8')
    (ASSET_DIR / 'viewer.js.map').write_text(source_map, encoding='utf-8')
    (ASSET_DIR / 'index.html').write_text(index_html, encoding='utf-8')
    (ASSET_DIR / 'field_metadata.json').write_text(
        json.dumps(build_field_metadata(calibre), ensure_ascii=False), encoding='utf-8')

    info = (
        f'calibre_commit={commit}\n'
        f'calibre_describe={describe}\n'
        f'rapydscript={rs_version}\n'
        f'built_by=tools/build_viewer_js.py\n'
        f'viewer.js.sha256_16={sha256(ASSET_DIR / "viewer.js")}\n'
        f'field_metadata.json.sha256_16={sha256(ASSET_DIR / "field_metadata.json")}\n'
    )
    (ASSET_DIR / 'BUILD_INFO.txt').write_text(info, encoding='utf-8')

    size = (ASSET_DIR / 'viewer.js').stat().st_size
    print(f'wrote viewer.js ({size / 1024:.0f} KiB), viewer.js.map, index.html, '
          f'field_metadata.json, BUILD_INFO.txt -> {ASSET_DIR}')


if __name__ == '__main__':
    main()
