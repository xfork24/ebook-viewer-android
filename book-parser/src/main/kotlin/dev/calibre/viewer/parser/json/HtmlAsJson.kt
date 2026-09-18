// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Byte-exact Kotlin port of calibre's html_as_json serializer
// (src/calibre/srv/html_as_json.cpp + the Python wrapper at
// src/calibre/srv/render_book.py:740-768).
//
// Contract highlights, all mirrored from the C code:
//  - element: {"n":local[,"s":nsIdx if >0][,"x":text][,"l":tail][,"a":[[name,val[,nsIdx]]...]]}
//    text/tail: null is omitted, "" is emitted as "x":""
//  - comment: {"s":"c"[,"x"][,"l"]}   processing instruction: {"s":"o"...}
//  - ns_map is filled lazily, pre-order: tag namespace first, then each
//    namespaced attribute of that tag, then descendants
//  - namespace lookup compares only the first len(new_ns) bytes of the stored
//    URI (an upstream quirk: a stored "http://ab" also matches "http://a").
//    Reproduced deliberately for byte compatibility.
//  - string escaping: \" \\ \b \f \n \r \t; other code points < 0x1F become
//    \uXXXX with UPPERCASE hex (surrogate pairs above 0xFFFF); 0x1F, 0x7F and
//    all non-ASCII are written as raw UTF-8 bytes
//  - final top-level shape: {"version":1,"tree":<root>,"ns_map":[...]}

package dev.calibre.viewer.parser.json

import dev.calibre.viewer.parser.dom.DomComment
import dev.calibre.viewer.parser.dom.DomElement
import dev.calibre.viewer.parser.dom.DomNode
import dev.calibre.viewer.parser.dom.XHTML_NS
import java.io.ByteArrayOutputStream

private const val HEX = "0123456789ABCDEF"

class HtmlAsJsonException(message: String) : IllegalArgumentException(message)

/** Mirrors render_book.py html_as_json(): validate root, ensure single body, prune, serialize. */
fun serializeHtmlAsJson(root: DomElement): ByteArray {
    if (root.namespace != "" && root.namespace != XHTML_NS) {
        throw HtmlAsJsonException("HTML tag must be in empty or XHTML namespace, got: ${root.namespace}")
    }
    ensureBody(root)
    root.children.removeAll { it is DomElement && it.localName != "head" && it.localName != "body" }
    root.text = null
    root.tail = null
    return serializeTree(root)
}

/** Raw C-level serialize() — no root validation, no body coercion. */
fun serializeTree(root: DomNode): ByteArray {
    val out = ByteArrayOutputStream(128 * 1024)
    Serializer(out).writeDocument(root)
    return out.toByteArray()
}

fun serializeHtmlAsJsonString(root: DomElement): String = serializeHtmlAsJson(root).toString(Charsets.UTF_8)

fun serializeTreeString(root: DomNode): String = serializeTree(root).toString(Charsets.UTF_8)

/**
 * render_book.py:740-754 ensure_body(): make sure the root has exactly one
 * XHTML <body>. Only XHTML-namespace bodies are considered — a foreign-ns
 * `body` is just an ordinary element. Surplus bodies are NOT removed: their
 * children move into an XHTML <div> (which also gets a COPY of their attrs
 * and their text) appended to the first body, while the emptied body itself
 * stays under <html> with attrs/text/tail intact. The synthesized body/div
 * are XHTML-namespaced, matching root.makeelement(XHTML(...)).
 */
internal fun ensureBody(root: DomElement) {
    val bodies = root.children.filterIsInstance<DomElement>()
        .filter { it.localName == "body" && it.namespace == XHTML_NS }
    if (bodies.size == 1) return
    if (bodies.isEmpty()) {
        root.append(DomElement("{$XHTML_NS}body"))
        return
    }
    val first = bodies[0]
    for (b in bodies.drop(1)) {
        val div = DomElement("{$XHTML_NS}div")
        for ((k, v) in b.attrs) div.setAttr(k, v.value)
        div.text = b.text
        for (child in b.children.toList()) {
            b.remove(child)
            div.append(child)
        }
        first.append(div)
    }
}

/** Stack frames for the iterative pre-order walk, mirroring the C++ serializer. */
private sealed interface Frame {
    data class Node(val node: DomNode) : Frame
    data object EndChildren : Frame   // writes "]}" after the last child
    data object Separator : Frame     // writes "," between children
}

private class Serializer(private val out: ByteArrayOutputStream) {
    private val nsMap = ArrayList<String>(8)

    fun writeDocument(root: DomNode) {
        out.writeAscii("{\"version\":1,\"tree\":")
        writeSubtree(root)
        out.writeAscii(",\"ns_map\":[")
        for (i in nsMap.indices) {
            if (i > 0) out.writeAscii(",")
            writeString(nsMap[i])
        }
        out.writeAscii("]}")
    }

    /** Iterative pre-order serialization of [node]'s subtree (guard against deep nesting). */
    private fun writeSubtree(node: DomNode) {
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame.Node(node))
        while (stack.isNotEmpty()) {
            when (val frame = stack.removeLast()) {
                is Frame.EndChildren -> out.writeAscii("]}")
                is Frame.Separator -> out.writeAscii(",")
                is Frame.Node -> when (val n = frame.node) {
                    is DomComment -> writeComment(n)
                    is DomElement -> {
                        writeStartTag(n)
                        if (n.children.isEmpty()) {
                            out.writeAscii("}")
                        } else {
                            out.writeAscii(",\"c\":[")
                            stack.addLast(Frame.EndChildren)
                            for (i in n.children.indices.reversed()) {
                                if (i != n.children.size - 1) stack.addLast(Frame.Separator)
                                stack.addLast(Frame.Node(n.children[i]))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun writeStartTag(e: DomElement) {
        val tagNs = e.namespace
        val nsIndex = if (tagNs.isEmpty()) -1 else namespaceIndex(tagNs)
        out.writeAscii("{\"n\":")
        writeString(e.localName)
        if (nsIndex > 0) {
            out.writeAscii(",\"s\":")
            out.write(nsIndex.toString().toByteArray())
        }
        e.text?.let { out.writeAscii(",\"x\":"); writeString(it) }
        e.tail?.let { out.writeAscii(",\"l\":"); writeString(it) }
        if (e.attrs.isNotEmpty()) {
            out.writeAscii(",\"a\":[")
            var first = true
            for (attr in e.attrs.values) {
                if (!first) out.writeAscii(",")
                first = false
                out.writeAscii("[")
                writeString(attr.localName)
                out.writeAscii(",")
                writeString(attr.value)
                if (attr.namespace.isNotEmpty()) {
                    out.writeAscii(",")
                    out.write(namespaceIndex(attr.namespace).toString().toByteArray())
                }
                out.writeAscii("]")
            }
            out.writeAscii("]")
        }
    }

    private fun writeComment(c: DomComment) {
        out.writeAscii("{\"s\":")
        writeString(if (c.isProcessingInstruction) "o" else "c")
        c.text?.let { out.writeAscii(",\"x\":"); writeString(it) }
        c.tail?.let { out.writeAscii(",\"l\":"); writeString(it) }
        out.writeAscii("}")
    }

    /**
     * C++ namespace_index(): returns the index of a stored URI whose first
     * `ns.length` bytes equal `ns`, registering `ns` when absent. The prefix
     * quirk (stored "http://ab" matches query "http://a") is upstream
     * behaviour and is kept for byte compatibility.
     */
    private fun namespaceIndex(ns: String): Int {
        for (i in nsMap.indices) {
            if (nsMap[i].startsWith(ns)) return i
        }
        nsMap.add(ns)
        return nsMap.size - 1
    }

    fun writeString(s: String) {
        out.writeAscii("\"")
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '"' -> out.writeAscii("\\\"")
                c == '\\' -> out.writeAscii("\\\\")
                c == '\b' -> out.writeAscii("\\b")
                c == '\u000C' -> out.writeAscii("\\f")
                c == '\n' -> out.writeAscii("\\n")
                c == '\r' -> out.writeAscii("\\r")
                c == '\t' -> out.writeAscii("\\t")
                else -> {
                    // Decode the full code point (surrogate pairs recombine).
                    val cp: Int
                    if (c.isHighSurrogate() && i + 1 < n && s[i + 1].isLowSurrogate()) {
                        cp = Character.toCodePoint(c, s[i + 1])
                        i++
                    } else {
                        cp = c.code
                    }
                    if (cp < 0x1F) {
                        out.writeAscii("\\u")
                        out.write(hex4(cp).toByteArray())
                    } else {
                        writeUtf8(cp)
                    }
                }
            }
            i++
        }
        out.writeAscii("\"")
    }

    private fun writeUtf8(cp: Int) {
        when {
            cp < 0x80 -> out.write(cp)
            cp < 0x800 -> {
                out.write(0xC0 or (cp shr 6))
                out.write(0x80 or (cp and 0x3F))
            }
            cp < 0x10000 -> {
                out.write(0xE0 or (cp shr 12))
                out.write(0x80 or ((cp shr 6) and 0x3F))
                out.write(0x80 or (cp and 0x3F))
            }
            else -> {
                out.write(0xF0 or (cp shr 18))
                out.write(0x80 or ((cp shr 12) and 0x3F))
                out.write(0x80 or ((cp shr 6) and 0x3F))
                out.write(0x80 or (cp and 0x3F))
            }
        }
    }

    private fun hex4(v: Int): String {
        val b = CharArray(4)
        b[0] = HEX[(v shr 12) and 0xF]
        b[1] = HEX[(v shr 8) and 0xF]
        b[2] = HEX[(v shr 4) and 0xF]
        b[3] = HEX[v and 0xF]
        return String(b)
    }
}

/** ASCII-only fast write (all serializer literals are ASCII). */
private fun ByteArrayOutputStream.writeAscii(s: String) {
    val bytes = s.toByteArray(Charsets.ISO_8859_1)
    write(bytes, 0, bytes.size)
}
