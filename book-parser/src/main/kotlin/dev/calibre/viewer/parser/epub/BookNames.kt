// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Canonical-name <-> href conversion mirroring calibre's
// ebooks/oeb/polish/container.py module-level helpers. A "name" is a
// POSIX-style relative path from the book root; an href is what appears in
// the book's markup (possibly %-quoted, possibly relative to the containing
// file, possibly carrying a fragment).

package dev.calibre.viewer.parser.epub

import java.text.Normalizer

private const val HEX_DIGITS = "0123456789ABCDEF"

object BookNames {

    /** posixpath.normpath(): collapse '//', '.' and '..' (leading '..' preserved). */
    fun normpath(path: String): String {
        val initialSlashes = path.startsWith("/")
        val comps = path.split("/")
        val newComps = ArrayList<String>()
        for (comp in comps) {
            if (comp.isEmpty() || comp == ".") continue
            if (comp != ".." || (!initialSlashes && newComps.isEmpty()) ||
                (newComps.isNotEmpty() && newComps.last() == "..")
            ) {
                newComps.add(comp)
            } else if (newComps.isNotEmpty()) {
                newComps.removeAt(newComps.size - 1)
            }
        }
        val joined = newComps.joinToString("/")
        val ans = if (initialSlashes) "/$joined" else joined
        return ans.ifEmpty { if (initialSlashes) "/" else "." }
    }

    /** posixpath.dirname() for relative names ("a/b" -> "a", "a" -> ""). */
    fun dirname(name: String): String {
        val i = name.lastIndexOf('/')
        return if (i == -1) "" else name.substring(0, i)
    }

    fun basename(name: String): String = name.substringAfterLast('/')

    /** posixpath.join(base, part): absolute part wins. */
    fun join(base: String, part: String): String =
        if (part.startsWith("/")) normpath(part) else normpath("$base/$part")

    /** posixpath.relpath(path, base) over relative names. */
    fun relpath(path: String, base: String): String {
        val startList = base.split('/').filter { it.isNotEmpty() && it != "." }
        val pathList = path.split('/').filter { it.isNotEmpty() && it != "." }
        var i = 0
        while (i < startList.size && i < pathList.size && startList[i] == pathList[i]) i++
        val relList = List(startList.size - i) { ".." } + pathList.subList(i, pathList.size)
        return relList.joinToString("/").ifEmpty { "." }
    }

    /** urllib.parse.quote(path) with the default safe="/". */
    fun quote(path: String): String {
        val sb = StringBuilder(path.length + 16)
        for (b in path.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val unreserved = c in 0x41..0x5A || c in 0x61..0x7A || c in 0x30..0x39 ||
                c == 0x2E || c == 0x5F || c == 0x2D || c == 0x7E || c == 0x2F
            if (unreserved) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(HEX_DIGITS[c ushr 4]).append(HEX_DIGITS[c and 0xf])
            }
        }
        return sb.toString()
    }

    /** urllib.parse.unquote(): %XX decoded, malformed sequences kept verbatim. */
    fun unquote(s: String): String {
        if (!s.contains('%')) return s
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val h = hexValue(s[i + 1])
                val l = hexValue(s[i + 2])
                if (h != -1 && l != -1) {
                    bytes.add(((h shl 4) or l).toByte())
                    i += 3
                    continue
                }
            }
            for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes.add(b)
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private class Url(val scheme: String, val path: String)

    /** The subset of urllib.parse.urlparse() that href_to_name() relies on. */
    private fun parseUrl(href: String): Url {
        var rest = href
        var scheme = ""
        // scheme must appear before any '/', '?' or '#'
        val colon = rest.indexOf(':')
        if (colon > 0) {
            val candidate = rest.substring(0, colon)
            val looksLikeScheme = candidate.isNotEmpty() &&
                candidate[0].isLetter() &&
                candidate.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
            if (looksLikeScheme) {
                scheme = candidate
                rest = rest.substring(colon + 1)
            }
        }
        if (rest.startsWith("//")) {
            // netloc ends at the first '/', '?' or '#'
            var end = -1
            for (ch in charArrayOf('/', '?', '#')) {
                val i = rest.indexOf(ch, 2)
                if (i != -1 && (end == -1 || i < end)) end = i
            }
            rest = if (end == -1) "" else rest.substring(end)
        }
        // urlparse(): fragment first, then query — the path stops at whichever
        // of '#'/'?' appears first
        val h = rest.indexOf('#')
        if (h != -1) rest = rest.substring(0, h)
        val q = rest.indexOf('?')
        if (q != -1) rest = rest.substring(0, q)
        return Url(scheme, rest)
    }

    /**
     * container.href_to_name(): href relative to [base] (a name, or null for the
     * book root) to a canonical name. Returns null for absolute URLs and
     * fragment-only hrefs, mirroring upstream.
     */
    fun hrefToName(href: String, base: String?): String? {
        val url = parseUrl(href)
        if (url.scheme.isNotEmpty() || url.path.isEmpty()) return null
        // os.path.join(base, *href.split('/')) upstream: empty segments are
        // dropped, so a root-absolute href ("/a/b") is resolved BASE-relative
        // (a deliberate calibre divergence from the EPUB spec we mirror), and
        // '..' collapses against the base directory. A path escaping the book
        // root keeps its leading '..' segments, as os.path.relpath() produces.
        val segs = unquote(url.path).split('/').filter { it.isNotEmpty() }
        val baseDir = if (base == null) "" else dirname(normpath(base))
        val joined = (listOf(baseDir) + segs).filter { it.isNotEmpty() }.joinToString("/")
        return nfc(if (joined.isEmpty()) "." else normpath(joined))
    }

    /** container.name_to_href(): canonical name to a %-quoted href relative to [base]. */
    fun nameToHref(name: String, base: String?): String {
        val baseDir = if (base == null) "" else dirname(normpath(base))
        return quote(relpath(normpath(name), baseDir))
    }

    fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)
}
