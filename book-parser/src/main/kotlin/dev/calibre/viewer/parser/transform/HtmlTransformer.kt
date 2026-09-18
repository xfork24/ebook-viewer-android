// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Port of calibre/src/calibre/srv/render_book.py:409-487 transform_html (the
// non-virtualize_resources branch — the Android shell runs the viewer in
// standalone mode and fetches images / styles via the embedded content server
// intercepting two hosts, so we don't rewrite file URLs to clbr:// schemes).
//
// What this does per HTML file:
//   1. data-calibre-src on <img src> and <svg:image xlink:href> — viewer.js
//      reads this to resolve the raster after link virtualization turns the
//      original href into a token.
//   2. Strips <link type!=text/css> / <link rel!=stylesheet> (only links that
//      HAVE an href — res_link_xpath is //h:link[@href]). The viewer renders
//      the stylesheets itself; loading a non-stylesheet via the WebView would
//      hang the resource-load check (see upstream comment).
//   3. Inline-CSS transform on <style> (any namespace, local-name match) and
//      style="" attributes via CssTransformer. No url_callback: standalone
//      mode doesn't rewrite file URLs into the content server's namespace.
//   4. Link virtualization: <a href>/<area href> → href="javascript:void(0)"
//      + data-<linkUid> JSON. link_to_map[dest][frag] += src records every
//      resolved cross-reference for the manifest writer.
//
// Final step: serialize the tree through html_as_json (the byte-exact Kotlin
// port) and replace the file with those bytes.

package dev.calibre.viewer.parser.transform

import dev.calibre.viewer.parser.dom.DomElement
import dev.calibre.viewer.parser.dom.SVG_NS
import dev.calibre.viewer.parser.dom.XHTML_NS
import dev.calibre.viewer.parser.dom.XLINK_NS
import dev.calibre.viewer.parser.epub.BookContainer
import dev.calibre.viewer.parser.json.serializeHtmlAsJson
import dev.calibre.viewer.parser.util.pyJsonString
import java.util.Base64

/** link_to_map: dest → frag → set of source file names that referenced it. */
typealias LinkToMap = MutableMap<String, MutableMap<String, MutableSet<String>>>

object HtmlTransformer {

    /**
     * @param linkUid a per-book random hex string (caller chooses, e.g. via
     *   java.util.UUID). The same uid must be used for every file in the book.
     * @param presentNames files that exist inside the container. hrefs pointing
     *   outside this set are tagged missing: instead of becoming link tokens.
     */
    fun transformHtml(
        container: BookContainer,
        name: String,
        linkUid: String,
        linkToMap: LinkToMap,
        presentNames: Set<String> = container.namePathMap.keys,
    ) {
        val root = container.parsed(name)

        // 1. data-calibre-src on imgs (XHTML + SVG) — used by the viewer to
        // resolve rasters after link virtualization mangles the original src.
        // Both xpaths carry a predicate, so attr-less imgs are never touched.
        for (img in root.findElements(XHTML_NS, "img")) {
            val src = img.getAttr("src") ?: continue
            container.hrefToName(src, name)?.let { img.setAttr("data-calibre-src", it) }
        }
        for (img in root.findElements(SVG_NS, "image")) {
            // svg_img_xpath is //svg:image[@xl:href] — xlink:href only, no
            // bare href fallback.
            val href = img.getAttr("{$XLINK_NS}href") ?: continue
            container.hrefToName(href, name)?.let { img.setAttr("data-calibre-src", it) }
        }

        // 2. Strip non-CSS link elements (that carry an href). Without the
        // type/rel guard we'd touch <link rel="icon"> etc., which the viewer
        // ignores but the resource-load watchdog still waits on.
        for (link in root.findElements(XHTML_NS, "link")) {
            if (link.getAttr("href") == null) continue
            val ltype = (link.getAttr("type") ?: "text/css").lowercase()
            val rel = (link.getAttr("rel") ?: "stylesheet").lowercase()
            if (ltype != "text/css" || rel != "stylesheet") link.clearAttrs()
        }

        // 3. Inline-CSS rewrite (transform_inline_styles): <style> matched by
        // local name in ANY namespace, style="" on any element. Standalone
        // mode does NOT rewrite URLs — the viewer's stylesheet resolver
        // fetches them through the content server.
        for (style in root.walk().filterIsInstance<DomElement>().filter { it.localName == "style" }) {
            val t = style.text ?: continue
            if ((style.getAttr("type") ?: "text/css").lowercase() != "text/css") continue
            val n = CssTransformer.transformProperties(t, isDeclaration = false)
            if (n != t) style.text = n
        }
        for (elem in root.walk().filterIsInstance<DomElement>()) {
            val t = elem.getAttr("style") ?: continue
            val n = CssTransformer.transformProperties(t, isDeclaration = true)
            if (n != t) elem.setAttr("style", n)
        }

        // 4. Link virtualization. link_xpath is //h:*[@href and (self::h:a or
        // self::h:area)] — href attr must EXIST (empty string counts); svg
        // links are //svg:a with NO href predicate. handle_link is a literal
        // port: a missing href leaves the element untouched, an empty href
        // only disables the link, and a bare uid (a '#'-only anchor) gets no
        // data attribute and no link_to_map entry.
        fun handleLink(a: DomElement, attr: String) {
            var href = a.getAttr(attr)
            if (href != null && href.isNotEmpty()) {
                href = linkReplace(container, name, href, linkUid, presentNames)
            } else if (a.attrs.containsKey(attr)) {
                a.setAttr(attr, "javascript:void(0)")
            }
            if (href.isNullOrEmpty()) return
            if (href.startsWith(linkUid)) {
                a.setAttr(attr, "javascript:void(0)")
                val parts = href.split('|')
                if (parts.size > 1) {
                    val (lname, lfrag) = decodeUrl(parts[1])
                    linkToMap.getOrPut(lname) { LinkedHashMap() }
                        .getOrPut(lfrag.ifEmpty { "" }) { LinkedHashSet() }
                        .add(name)
                    a.setAttr("data-$linkUid", linkDataJson(lname, lfrag))
                }
            } else if (href.startsWith("missing:")) {
                a.setAttr(attr, "javascript:void(0)")
                a.setAttr(
                    "data-$linkUid",
                    linkDataJson(href.removePrefix("missing:"), "", missing = true),
                )
            }
            // External http(s) / mailto / etc. — leave href unchanged.
        }

        for (a in root.findElements(XHTML_NS, "a").filter { it.getAttr("href") != null }) {
            handleLink(a, "href")
        }
        for (a in root.findElements(XHTML_NS, "area").filter { it.getAttr("href") != null }) {
            handleLink(a, "href")
        }
        for (a in root.findElements(SVG_NS, "a")) {
            handleLink(a, "{$XLINK_NS}href")
        }

        // 5. Serialize via html_as_json and overwrite the file. Future calls
        // to container.parsed(name) will pick up the JSON form because
        // replaceFile invalidates the cache.
        container.replaceFile(name, serializeHtmlAsJson(root))
    }

    // ---- link_replacer (render_book.py:72-98) -----------------------------

    /**
     * Returns one of:
     *   - original href (external / unparseable → caller leaves it alone)
     *   - linkUid                      ('#'-only anchor, empty frag)
     *   - "linkUid|b64(name)#frag|"    (token; frag raw, percent-decoded)
     *   - "missing:name"               (target not in presentNames)
     */
    private fun linkReplace(
        container: BookContainer,
        base: String,
        href: String,
        linkUid: String,
        presentNames: Set<String>,
    ): String {
        // Fragment-only: '#frag' — the target is the current file. The frag
        // is percent-decoded unconditionally (urlunquote never raises).
        if (href.startsWith("#")) {
            val frag = urlUnquote(href.substring(1))
            if (frag.isEmpty()) return linkUid
            return "$linkUid|${encodeUrl(base, frag)}|"
        }
        // Parse scheme / netloc / query / path / fragment. Mirrors the small
        // subset of urlparse the upstream replacer relies on.
        val parsed = parseHref(href) ?: return href
        if (parsed.netloc.isNotEmpty() || parsed.query.isNotEmpty()) return href
        if (parsed.scheme.isNotEmpty() && parsed.scheme != "file") return href
        if (parsed.path.isEmpty() || parsed.path.startsWith("/")) return href

        val name = container.hrefToName(parsed.path, base) ?: return href
        return if (name in presentNames) {
            // Frag is percent-decoded only on the resolved branch; the
            // missing: branch discards it entirely.
            val frag = urlUnquote(parsed.fragment)
            "$linkUid|${encodeUrl(name, frag)}|"
        } else {
            "missing:$name"
        }
    }

    // ---- encode_url / decode_url (render_book.py:57-67) -------------------

    /**
     * encode_url(name, frag): encode_component = polyglot.as_base64_unicode =
     * standard base64 (padded, +/) of the UTF-8 bytes; the frag is appended
     * RAW after '#'.
     */
    private fun encodeUrl(name: String, frag: String): String {
        val b64 = Base64.getEncoder().encodeToString(name.toByteArray(Charsets.UTF_8))
        return if (frag.isNotEmpty()) "$b64#$frag" else b64
    }

    /** decode_url(x): split on '#' once; b64-decode the name, frag returned raw. */
    private fun decodeUrl(encoded: String): Pair<String, String> {
        val parts = encoded.split('#', limit = 2)
        // Python's standard_b64decode silently discards junk; Java's decoder
        // throws. The input is always our own encoder's output in practice,
        // but a hostile book must not crash the whole render.
        val name = runCatching { String(Base64.getDecoder().decode(parts[0]), Charsets.UTF_8) }
            .getOrElse { parts[0] }
        val frag = if (parts.size > 1) parts[1] else ""
        return name to frag
    }

    /**
     * Python urllib.parse.unquote(s) with errors='replace': contiguous %XX
     * runs collect into bytes and decode as UTF-8 (invalid sequences become
     * U+FFFD); a '%' not followed by two hex digits stays literal; '+' is
     * untouched (that is unquote_plus territory). Never throws.
     */
    private fun urlUnquote(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }
            // Collect the whole contiguous %XX run so multi-byte UTF-8
            // sequences survive, then decode with replacement.
            val bytes = ArrayList<Byte>(8)
            var j = i
            while (j < s.length && s[j] == '%' && j + 2 < s.length) {
                val h = hexVal(s, j + 1)
                val l = hexVal(s, j + 2)
                if (h == -1 || l == -1) break
                bytes.add(((h shl 4) or l).toByte())
                j += 3
            }
            if (bytes.isEmpty()) {
                out.append('%')
                i++
            } else {
                val arr = ByteArray(bytes.size)
                for (k in bytes.indices) arr[k] = bytes[k]
                out.append(String(arr, Charsets.UTF_8)) // malformed input → U+FFFD
                i = j
            }
        }
        return out.toString()
    }

    private fun hexVal(s: String, idx: Int): Int {
        if (idx >= s.length) return -1
        return when (val c = s[idx]) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }

    // ---- href parser ------------------------------------------------------

    private data class HrefParts(
        val scheme: String = "",
        val netloc: String = "",
        val path: String = "",
        val query: String = "",
        val fragment: String = "",
    )

    /** Tiny subset of urlparse sufficient for the link replacer. */
    private fun parseHref(href: String): HrefParts? {
        var rest = href
        var scheme = ""
        var netloc = ""
        // scheme://
        val colon = rest.indexOf(':')
        if (colon > 0) {
            val cand = rest.substring(0, colon)
            if (cand.isNotEmpty() && cand[0].isLetter() &&
                cand.all { it.isLetterOrDigit() || it in "+-." }
            ) {
                scheme = cand
                rest = rest.substring(colon + 1)
            }
        }
        if (rest.startsWith("//")) {
            rest = rest.substring(2)
            val endIdx = rest.indexOfAny(charArrayOf('/', '?', '#'))
            val end = if (endIdx == -1) rest.length else endIdx
            netloc = rest.substring(0, end)
            rest = rest.substring(end)
        }
        val fragIdx = rest.indexOf('#')
        val pathAndQuery = if (fragIdx == -1) rest else rest.substring(0, fragIdx)
        val fragment = if (fragIdx == -1) "" else rest.substring(fragIdx + 1)
        val queryIdx = pathAndQuery.indexOf('?')
        val path = if (queryIdx == -1) pathAndQuery else pathAndQuery.substring(0, queryIdx)
        val query = if (queryIdx == -1) "" else pathAndQuery.substring(queryIdx + 1)
        return HrefParts(scheme, netloc, path, query, fragment)
    }

    // ---- data-<linkUid> JSON ----------------------------------------------

    /**
     * Python json.dumps(..., ensure_ascii=False) with default separators —
     * note the SPACE after ':' and ',': `{"name": "x", "frag": "y"}`.
     */
    private fun linkDataJson(name: String, frag: String, missing: Boolean = false): String {
        val sb = StringBuilder(64)
        sb.append("{\"name\": ").append(pyJsonString(name))
        sb.append(", \"frag\": ").append(pyJsonString(frag))
        if (missing) sb.append(", \"missing\": true")
        sb.append('}')
        return sb.toString()
    }
}
