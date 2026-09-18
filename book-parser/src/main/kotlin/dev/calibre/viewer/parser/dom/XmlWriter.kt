// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Namespace-aware XML serializer for DomElement trees. The pull parser does
// not report xmlns declarations (they are bindings, not attributes), so this
// writer re-synthesizes them: each element's namespace is bound when first
// used — the root's namespace as the default declaration, others via a
// conventional or synthesized prefix. The output is semantically equivalent
// to the input; byte-identity with lxml is not a goal (this is only used for
// files the pipeline itself re-parses, e.g. the rewritten OPF).

package dev.calibre.viewer.parser.dom

import java.io.ByteArrayOutputStream

private const val XML_NS = "http://www.w3.org/XML/1998/namespace"

private val PREFERRED_PREFIXES = mapOf(
    "http://purl.org/dc/elements/1.1/" to "dc",
    XLINK_NS to "xlink",
    "http://www.idpf.org/2007/ops" to "epub",
    "https://calibre-ebook.com" to "calibre",
    // the package namespace appears both as the tree default and on
    // attributes (opf:role); attributes need a real prefix
    "http://www.idpf.org/2007/opf" to "opf",
)

private class Bindings {
    private val nsToPrefix = HashMap<String, String>() // ns -> prefix ("" = default)

    fun copy(): Bindings = Bindings().also { it.nsToPrefix.putAll(nsToPrefix) }

    fun prefixFor(ns: String): String? = nsToPrefix[ns]

    fun defaultNs(): String? = nsToPrefix.entries.firstOrNull { it.value == "" }?.key

    fun bind(ns: String, prefix: String) {
        nsToPrefix[ns] = prefix
    }

    fun isTaken(prefix: String): Boolean = prefix in nsToPrefix.values
}

object XmlWriter {

    fun serializeToBytes(root: DomElement): ByteArray {
        val out = ByteArrayOutputStream()
        // lxml's toxml() declaration — single quotes, lowercase — so the
        // rewritten OPF bytes match upstream's output.
        out.write("<?xml version='1.0' encoding='utf-8'?>\n".toByteArray(Charsets.UTF_8))
        writeElement(out, root, Bindings())
        return out.toByteArray()
    }

    fun serializeToString(root: DomElement): String = String(serializeToBytes(root), Charsets.UTF_8)

    private fun writeElement(out: ByteArrayOutputStream, el: DomElement, parent: Bindings) {
        val ns = namespaceOf(el.tag)
        val local = el.localName
        val b = parent.copy()
        var prefix: String
        val declarations = ArrayList<Pair<String, String>>() // prefix ("" = default) -> ns

        if (ns.isEmpty()) {
            prefix = ""
            if (b.defaultNs() != null) declarations.add("" to "")
        } else {
            val bound = b.prefixFor(ns)
            if (bound != null) {
                prefix = bound
            } else {
                prefix = if (b.defaultNs() == null) "" else PREFERRED_PREFIXES[ns]?.takeIf { !b.isTaken(it) } ?: synth(b)
                b.bind(ns, prefix)
                declarations.add(prefix to ns)
            }
        }

        out.write('<'.code)
        if (prefix.isNotEmpty()) {
            out.write(prefix.toByteArray(Charsets.UTF_8))
            out.write(':'.code)
        }
        out.write(local.toByteArray(Charsets.UTF_8))
        for ((p, uri) in declarations) {
            when {
                p.isEmpty() && uri.isEmpty() -> out.write(" xmlns=\"\"".toByteArray(Charsets.UTF_8))
                p.isEmpty() -> {
                    out.write(" xmlns=\"".toByteArray(Charsets.UTF_8))
                    writeValue(out, uri)
                    out.write('"'.code)
                }
                else -> {
                    out.write(" xmlns:$p=\"".toByteArray(Charsets.UTF_8))
                    writeValue(out, uri)
                    out.write('"'.code)
                }
            }
        }

        for (attr in el.attrs.values) {
            out.write(' '.code)
            val ans = attr.namespace
            val aprefix: String
            if (ans.isEmpty()) {
                aprefix = ""
            } else if (ans == XML_NS) {
                // the xml prefix is implicitly bound in every XML document
                aprefix = "xml"
            } else {
                // attributes never use the default namespace binding
                val candidate = b.prefixFor(ans)?.takeIf { it.isNotEmpty() }
                if (candidate != null) {
                    aprefix = candidate
                } else {
                    aprefix = PREFERRED_PREFIXES[ans]?.takeIf { !b.isTaken(it) } ?: synth(b)
                    b.bind(ans, aprefix)
                    out.write("xmlns:$aprefix=\"".toByteArray(Charsets.UTF_8))
                    writeValue(out, ans)
                    out.write("\" ".toByteArray(Charsets.UTF_8))
                }
            }
            if (aprefix.isNotEmpty()) {
                out.write(aprefix.toByteArray(Charsets.UTF_8))
                out.write(':'.code)
            }
            out.write(attr.localName.toByteArray(Charsets.UTF_8))
            out.write("=\"".toByteArray(Charsets.UTF_8))
            writeValue(out, attr.value)
            out.write('"'.code)
        }

        if (el.children.isEmpty() && el.text.isNullOrEmpty()) {
            out.write("/>".toByteArray(Charsets.UTF_8))
            return
        }
        out.write('>'.code)
        el.text?.let { writeText(out, it) }
        for (child in el.children) {
            when (child) {
                is DomElement -> writeElement(out, child, b)
                is DomComment -> if (child.isProcessingInstruction) {
                    val t = child.text ?: ""
                    out.write("<?".toByteArray(Charsets.UTF_8))
                    out.write(t.toByteArray(Charsets.UTF_8))
                    if (!t.endsWith("?")) out.write('?'.code)
                    out.write('>'.code)
                } else {
                    out.write("<!--".toByteArray(Charsets.UTF_8))
                    out.write((child.text ?: "").toByteArray(Charsets.UTF_8))
                    out.write("-->".toByteArray(Charsets.UTF_8))
                }
            }
            child.tail?.let { writeText(out, it) }
        }
        out.write("</".toByteArray(Charsets.UTF_8))
        if (prefix.isNotEmpty()) {
            out.write(prefix.toByteArray(Charsets.UTF_8))
            out.write(':'.code)
        }
        out.write(local.toByteArray(Charsets.UTF_8))
        out.write('>'.code)
    }

    private fun synth(b: Bindings): String {
        var i = 1
        while (b.isTaken("ns$i")) i++
        return "ns$i"
    }

    /** Attribute values: escape ampersand, angle brackets and quotes. */
    private fun writeValue(out: ByteArrayOutputStream, s: String) {
        for (c in s) {
            when (c) {
                '&' -> out.write("&amp;".toByteArray(Charsets.UTF_8))
                '<' -> out.write("&lt;".toByteArray(Charsets.UTF_8))
                '>' -> out.write("&gt;".toByteArray(Charsets.UTF_8))
                '"' -> out.write("&quot;".toByteArray(Charsets.UTF_8))
                '\r' -> out.write("&#13;".toByteArray(Charsets.UTF_8))
                else -> out.write(c.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** Text nodes: quotes are legal, escape ampersand and angle brackets. */
    private fun writeText(out: ByteArrayOutputStream, s: String) {
        for (c in s) {
            when (c) {
                '&' -> out.write("&amp;".toByteArray(Charsets.UTF_8))
                '<' -> out.write("&lt;".toByteArray(Charsets.UTF_8))
                '>' -> out.write("&gt;".toByteArray(Charsets.UTF_8))
                '\r' -> out.write("&#13;".toByteArray(Charsets.UTF_8))
                else -> out.write(c.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }
}
