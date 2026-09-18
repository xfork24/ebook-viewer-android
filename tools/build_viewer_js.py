#!/usr/bin/env python3
# License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
"""
Build viewer.js (and friends) from a calibre source checkout for the Android app.

Mirrors calibre's `compile_viewer()` (src/calibre/utils/rapydscript.py:660) with
two deliberate divergences, applied via the set_data placeholder map:

    __FAKE_PROTOCOL__  -> https
    __FAKE_HOST__      -> appassets.androidplatform.net
        (SANDBOX_HOST derives to appassets.androidplatform.sandbox in
         viewer-main.pyj, matching the hosts our WebViewClient intercepts)
    __EDITABLE_FORMATS__ -> []
        (no book editing on Android; hides the "edit book" affordances)

and one prepended preamble:

    tools/compat.js -> start of viewer.js
        (ES5 shim for WebViews below Chromium 98 that lack
         window.structuredClone — frozen vendor WebViews on e-ink readers)

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
    leftover = re.search(r'__[A-Z][A-Z_]{2,}__', code)
    if leftover:
        die(f'unreplaced placeholder in viewer.js: {leftover.group(0)} '
            '(upstream set_data() grew — extend parse_set_data_constants)')
    code = code.rstrip()
    code = code.removesuffix('//# sourceMappingURL=smap')
    return code + '\n//# sourceMappingURL=viewer.js.map'


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
    js = apply_set_data(code, table)
    # Preamble first so it is in place before any viewer.js code runs; see
    # the docstring for why this must stay ES5.
    compat = (REPO / 'tools' / 'compat.js').read_text(encoding='utf-8')
    js = compat + '\n' + js

    reset = (calibre / 'resources' / 'content-server' / 'reset.css').read_text(encoding='utf-8')
    base_css = (calibre / 'resources' / 'content-server' / 'base.css').read_text(encoding='utf-8')
    icons = merge_icons(calibre)
    # Same composition as compile_viewer()'s viewer.html, plus the two scripts
    # the Qt host would inject (translations placeholder, then viewer.js).
    index_html = (
        '<!DOCTYPE html>\n<html><head>'
        f'<style>{reset}</style><style>{base_css}</style>'
        '<script>window.calibre_translations_data=null</script>'
        f'</head><body>{icons}<script src="/viewer.js"></script></body></html>'
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
