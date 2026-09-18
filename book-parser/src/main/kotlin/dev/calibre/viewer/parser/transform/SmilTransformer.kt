// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Port of calibre/src/calibre/srv/render_book.py:275-386 transform_smil +
// parse_smil_time. Walks a SMIL document, attaches an audio-par tree to the
// targeted HTML spine file in [smilMap]. The viewer's standalone reader
// doesn't render audio, so the smil_map is informational only — but the
// manifest contract requires it for parity with the desktop build.
//
// Faithfulness notes (all bug-for-bug against upstream):
//   - parnum is ONE counter per transform_smil call, incremented for every
//     <par> seen including the ones later dropped (nonlocal parnum).
//   - target = href_to_name(tref) may be None; upstream happily keys
//     smil_map[None] for unresolvable textrefs, so map keys are nullable.
//   - Only DIRECT children of the root are scanned for <body> (iterchildren),
//     and every body is processed, not just the first.
//   - A par survives iff its resolved audio name is truthy (non-null).
//   - Empty 'par'/'seq' keys are DELETED from the dict upstream; the model
//     keeps empty lists and the manifest writer omits them at serialization.

package dev.calibre.viewer.parser.transform

import dev.calibre.viewer.parser.dom.DomElement
import dev.calibre.viewer.parser.epub.BookContainer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private typealias XmlElement = DomElement

/**
 * A par is serialized as the dict make_par builds — key ORDER is part of the
 * byte format: num, type?, then anchor/audio/start/end in CHILD document
 * order (a second \<audio\> overwrites the first in place, a null resolution
 * overwrites a valid one — exactly like Python dict assign).
 */
typealias ParDict = LinkedHashMap<String, Any?>

object SmilTransformer {

    /** SMIL namespace URI (calibre.ebooks.oeb.base.SMIL_NS). */
    const val SMIL_NS = "http://www.w3.org/ns/SMIL"

    /** EPUB type/textref attribute namespace (calibre.ebooks.oeb.base.EPUB_NS). */
    private const val EPUB_NS = "http://www.idpf.org/2007/ops"

    class SmilSeq(
        val textref: Pair<String?, String>,
        var type: String? = null,
        val par: MutableList<ParDict> = mutableListOf(),
        val seq: MutableList<SmilSeq> = mutableListOf(),
    )

    /**
     * Walk the SMIL document at [name] and attach an audio-par tree to the
     * HTML file each <seq>/<body> targets via epub:textref.
     */
    fun transformSmil(
        container: BookContainer,
        name: String,
        smilMap: MutableMap<String?, SmilSeq>,
    ) {
        val root = container.parsed(name)
        // Upstream declares `parnum = 0` once per transform_smil call; the
        // nonlocal increments span every top-level seq processed below.
        val counter = Counter()
        for (child in root.children.filterIsInstance<XmlElement>()) {
            if (child.namespace != SMIL_NS || child.localName != "body") continue
            val tref = child.getAttr("{$EPUB_NS}textref")
            if (tref != null) {
                processSeq(container, name, child, tref, smilMap, null, counter)
            } else {
                for (gc in gcSeqs(child)) {
                    val t = gc.getAttr("{$EPUB_NS}textref") ?: continue
                    processSeq(container, name, gc, t, smilMap, null, counter)
                }
            }
        }
    }

    private fun gcSeqs(body: XmlElement): List<XmlElement> =
        body.children.filterIsInstance<XmlElement>().filter {
            it.namespace == SMIL_NS && it.localName == "seq"
        }

    /** Mutable counter hoisted into processSeq/makePar to mirror upstream's `nonlocal parnum`. */
    private class Counter { var value = 0 }

    private fun processSeq(
        container: BookContainer,
        name: String,
        seqElem: XmlElement,
        tref: String,
        smilMap: MutableMap<String?, SmilSeq>,
        parent: SmilSeq?,
        counter: Counter,
    ) {
        // May be null for an unresolvable textref — upstream keys smil_map
        // by None in that case rather than skipping the seq.
        val target = container.hrefToName(tref, name)
        val frag = tref.substringAfter('#', "")
        val seq = SmilSeq(textref = target to frag)
        seq.type = seqElem.getAttr("{$EPUB_NS}type")

        val resolvedParent: SmilSeq
        if (parent == null) {
            resolvedParent = smilMap.getOrPut(target) {
                SmilSeq(textref = target to "", type = "root")
            }
        } else {
            if (parent.textref.first != target) return // same-file rule
            resolvedParent = parent
        }
        resolvedParent.seq.add(seq)

        for (child in seqElem.children) {
            if (child !is XmlElement) continue
            if (child.namespace != SMIL_NS) continue
            when (child.localName) {
                "par" -> {
                    val p = makePar(container, name, child, target, counter)
                    // `if p.get('audio')` — a par with no resolved audio is dropped.
                    if (p != null) seq.par.add(p)
                }

                "seq" -> {
                    val t = child.getAttr("{$EPUB_NS}textref") ?: continue
                    processSeq(container, name, child, t, smilMap, seq, counter)
                }
            }
        }
    }

    /**
     * Build a par dict from a <par> element. Returns null when the par's
     * <text src> targets a different HTML file than the parent seq's textref
     * (upstream returns {} which then fails the audio gate), or when no
     * <audio src> resolved (the audio value ends up null). parnum is bumped
     * either way.
     */
    private fun makePar(
        container: BookContainer,
        smilName: String,
        par: XmlElement,
        target: String?,
        counter: Counter,
    ): ParDict? {
        counter.value += 1
        val ans = ParDict()
        ans["num"] = counter.value
        par.getAttr("{$EPUB_NS}type")?.takeIf { it.isNotEmpty() }?.let { ans["type"] = it }

        for (child in par.children) {
            if (child !is XmlElement || child.namespace != SMIL_NS) continue
            when (child.localName) {
                "text" -> {
                    // `if src:` — an empty src is skipped, not fatal.
                    val src = child.getAttr("src")?.takeIf { it.isNotEmpty() } ?: continue
                    val resolved = container.hrefToName(src, smilName)
                    if (resolved != target) return null // must match the parent seq's textref
                    // Assigned even when '' — upstream writes the key unconditionally.
                    ans["anchor"] = src.substringAfter('#', "")
                }
                "audio" -> {
                    val src = child.getAttr("src")?.takeIf { it.isNotEmpty() } ?: continue
                    // May be null — stored as-is so a later invalid <audio>
                    // overwrites an earlier valid one, like Python dict assign.
                    ans["audio"] = container.hrefToName(src, smilName)
                    child.getAttr("clipBegin")?.takeIf { it.isNotEmpty() }?.let { ans["start"] = parseSmilTime(it) }
                    child.getAttr("clipEnd")?.takeIf { it.isNotEmpty() }?.let { ans["end"] = parseSmilTime(it) }
                }
            }
        }
        if (ans["audio"] == null) return null
        return ans
    }

    /**
     * SMIL clock-value parser (render_book.py:275-296), bug-for-bug:
     * `endswith('s')` is checked FIRST, so "123ms" strips to "123m" and
     * raises exactly like Python's float(); the ms branch is dead code kept
     * for parity. Python int()/float() tolerate surrounding whitespace,
     * hence the trims.
     */
    fun parseSmilTime(x: String): Double {
        val parts = x.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].trim().toInt()
                val m = parts[1].trim().toInt()
                val s = parts[2].trim().toDouble()
                abs(h) * 3600.0 +
                    max(0.0, min(abs(m).toDouble(), 59.0)) * 60.0 +
                    max(0.0, min(abs(s), 59.0))
            }
            2 -> {
                val m = parts[0].trim().toInt()
                val s = parts[1].trim().toDouble()
                max(0.0, min(abs(m).toDouble(), 59.0)) * 60.0 +
                    max(0.0, min(abs(s), 59.0))
            }
            1 -> when {
                x.endsWith("s") -> x.dropLast(1).trim().toDouble()
                x.endsWith("ms") -> x.dropLast(2).trim().toDouble() * 0.001
                x.endsWith("min") -> x.dropLast(3).trim().toDouble() * 60.0
                x.endsWith("h") -> x.dropLast(1).trim().toDouble() * 3600.0
                else -> throw IllegalArgumentException("Malformed SMIL time: $x")
            }
            else -> throw IllegalArgumentException("Malformed SMIL time: $x")
        }
    }
}
