// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The device's real font families for the Fonts preferences panel. The page
// renders ui_data.all_font_families verbatim (viewer.js:36183) and Android
// registers system families exactly as fonts.xml names them (SkFontMgr_android
// / the WebView font stack), so the config file is both the source of truth
// and enough for CSS font-family resolution in the WebView. javax.xml (not
// XmlPullParser) keeps this class runnable in plain JVM unit tests.

package dev.calibre.viewer.host

import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

object SystemFontFamilies {

    /** The generic CSS families, pinned to the head of the panel's list. */
    val GENERIC_HEAD = listOf(
        "serif", "sans-serif", "monospace", "serif-monospace", "sans-serif-condensed", "cursive",
    )

    /** system + the vendor/product overlays modern devices merge in. */
    private val DEFAULT_FONT_XMLS = listOf(
        File("/system/etc/fonts.xml"),
        File("/vendor/etc/fonts.xml"),
        File("/product/etc/fonts.xml"),
    )

    /**
     * Family names from a fonts.xml stream: non-blank <family name="...">
     * entries, emoji families skipped (noise in a book-font dropdown — they
     * have no letters), deduped case-insensitively keeping first occurrence.
     * Nameless <family lang="..."> fallback entries are dropped by the
     * non-blank rule.
     */
    fun parse(xml: InputStream): List<String> {
        val factory = DocumentBuilderFactory.newInstance()
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (ignored: Exception) {
            // A parser without the flag still parses; the input is a
            // system-owned config file, this is defense in depth only.
        }
        val doc = factory.newDocumentBuilder().parse(xml)
        val nodes = doc.getElementsByTagName("family")
        val seen = HashSet<String>()
        val out = ArrayList<String>(nodes.length)
        for (i in 0 until nodes.length) {
            val name = nodes.item(i).attributes?.getNamedItem("name")?.nodeValue?.trim().orEmpty()
            if (name.isEmpty() || name.contains("emoji", ignoreCase = true)) continue
            if (seen.add(name.lowercase())) out.add(name)
        }
        return out
    }

    /** GENERIC_HEAD first, everything else deduped and sorted case-insensitively. */
    fun ordered(families: Collection<String>): List<String> {
        val seen = GENERIC_HEAD.map { it.lowercase() }.toHashSet()
        val rest = families.filter { seen.add(it.lowercase()) }
        return GENERIC_HEAD + rest.sortedBy { it.lowercase() }
    }

    /**
     * Merge every readable fonts.xml; the fallback list when none parse to
     * anything (the generic CSS families always resolve, so the panel keeps
     * working on exotic setups).
     */
    fun deviceFamilies(
        fallback: List<String>,
        fontXmls: List<File> = DEFAULT_FONT_XMLS,
    ): List<String> {
        val found = LinkedHashSet<String>()
        for (file in fontXmls) {
            if (!file.isFile) continue
            val parsed = runCatching { file.inputStream().use(::parse) }.getOrDefault(emptyList())
            found.addAll(parsed)
        }
        if (found.isEmpty()) return fallback
        return ordered(found)
    }
}
