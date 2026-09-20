# ebook-viewer-android

A port of [calibre](https://calibre-ebook.com)'s **ebook-viewer** to Android,
optimized for e-ink readers (with special attention to iReader / 掌阅 devices)
while working well on phones and tablets of any screen size.

## License

**GPL v3.** This project embeds and derives from calibre's viewer JavaScript
bundle (`viewer.js`, compiled from calibre's RapydScript sources), which is
Copyright © Kovid Goyal and calibre contributors, licensed under the GNU GPL
v3. Consequently the entire app is licensed under GNU GPL v3. See
[LICENSE](LICENSE) and the source offer in the in-app About screen.

## Architecture

```
:app          Compose shell + WebView host (URL interception + JS bridge)
:book-parser  Pure-Kotlin EPUB → calibre "exploded book" pipeline
              (calibre-book-manifest.json + JSON-serialized HTML)
:viewer-core  Persistence (session data, annotations, reading positions)
```

The reader UI is calibre's own `viewer.js` running inside an Android WebView,
served from `https://appassets.androidplatform.net` via
`WebViewClient.shouldInterceptRequest`, and driven through the same
`python_comm` signal bridge the desktop viewer uses.

## Building

Requirements: JDK 17, Android SDK (platform 35, build-tools), Node.js (only to
(re)build `viewer.js` from the calibre checkout — the compiled artifact is
committed).

```bash
./gradlew :app:assembleDebug
```

To rebuild the viewer bundle from a calibre source checkout:

```bash
python3 tools/build_viewer_js.py --calibre-src /path/to/calibre
```

## Status

Milestone 1 (walking skeleton) in progress. See the project history for the roadmap.

## Documentation

- [`docs/code-review-report-2026-09-20.md`](docs/code-review-report-2026-09-20.md) — full
  audit, 17-issue F-list, 9-fix commit timeline, e2e matrix results.
- [`docs/roadmap-2026-09-20.md`](docs/roadmap-2026-09-20.md) — improvement
  opportunities ranked by severity / cost-effectiveness / effort, with
  milestone suggestions.

## Testing

```bash
# JVM unit tests (Kotlin + JUnit 5, all modules)
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test

# End-to-end against a connected emulator (23-test matrix, 4 groups)
python3 tools/e2e.py
python3 tools/e2e.py --group A          # just startup
python3 tools/e2e.py --device emulator-5554 --book mathml.epub
```

Evidence (screenshots, JSON dumps, summary) lands in `build/e2e-evidence/`.
