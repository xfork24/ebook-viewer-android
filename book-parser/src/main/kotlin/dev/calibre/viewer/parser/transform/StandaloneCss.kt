// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Standalone-mode port of calibre/src/calibre/srv/render_book.py:247-287
// transform_style_sheet + transform_svg_image. In standalone mode
// (virtualize_resources=False) the only work these functions do is:
//   - run CssTransformer over the raw stylesheet (no url_callback — the
//     viewer's stylesheet resolver fetches assets via the embedded content
//     server, which resolves them against the file's location itself)
//   - prepend `@charset "UTF-8";` if missing (the renderer's C++ font table
//     and the html_as_json CDATA inserter both expect a UTF-8 declaration)
//   - for SVG images: do nothing — the SVG <image> xlink:href rewrite only
//     runs in virtualize_resources mode, where every URL becomes a token.

package dev.calibre.viewer.parser.transform

import dev.calibre.viewer.parser.epub.BookContainer
import java.io.File

object StandaloneCss {

    /**
     * Rewrite a single CSS file in place, mirroring render_book.py:247-268:
     * run the property rewriter, strip leading whitespace unconditionally,
     * then prepend `@charset "UTF-8";` when missing. The file is written
     * directly — upstream does not mark CSS dirty in the parsed cache.
     * Returns true if the file changed.
     */
    fun transformStyleSheet(container: BookContainer, name: String): Boolean {
        val path: File = container.namePathMap[name] ?: return false
        val raw = path.readBytes().toString(Charsets.UTF_8)
        val rewritten = CssTransformer.transformProperties(raw, urlCallback = null, isDeclaration = false)
        var changed = rewritten != raw
        // raw.lstrip() happens upstream even when the rewriter made no change.
        var out = rewritten.dropWhile { it.isWhitespace() }
        if (!out.startsWith("@charset")) {
            out = "@charset \"UTF-8\";\n$out"
            changed = true
        }
        // Upstream quirk: a file that already has @charset and needed no
        // property rewrite is left untouched even though lstrip() would have
        // trimmed its leading whitespace — `changed` is not set by lstrip.
        if (!changed) return false
        path.writeText(out, Charsets.UTF_8)
        return true
    }

    /** Standalone SVG pass: no-op (xlink:href is resolved server-side). */
    fun transformSvgImage(container: BookContainer, name: String): Boolean = false
}
