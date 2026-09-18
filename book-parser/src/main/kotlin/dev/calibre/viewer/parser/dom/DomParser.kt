// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// HTML parsing mirroring calibre's parse_utils.parse_html() as invoked through
// polish Container.parse_xhtml(): strict XML first (the EPUB main path),
// entity-replaced XML second, HTML5 tree construction as the fallback, then
// the namespace coercion + normalization pipeline. Everything downstream
// (transform_html, html_as_json) consumes the tree this produces, so its
// shape — not just its content — must match lxml's.

package dev.calibre.viewer.parser.dom

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.StringReader

/** Thrown when the file turns out not to be HTML at all (e.g. NCX). */
class NotHTMLException(val tag: String) : Exception("Not HTML: $tag")

class HtmlParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * @param pullParserFactory supplies a fresh XmlPullParser per parse. On
 *   Android pass { android.util.Xml.newPullParser() }; JVM tests inject kxml2.
 */
class DomParser(private val pullParserFactory: () -> XmlPullParser) {

    /** parse_utils.parse_html() with non_html_file_tags={'ncx'}. */
    fun parseHtml(bytes: ByteArray, nonHtmlFileTags: Set<String> = setOf("ncx")): DomElement {
        var text = decodeBytes(bytes)
        text = stripEncodingDeclarations(text)

        // Split off the pre-<html> stanza: XML decl, DOCTYPE, comments, PIs.
        val idxHtml = text.indexOf("<html").let { if (it == -1) text.indexOf("<HTML") else it }
        var pre = ""
        if (idxHtml > -1) {
            pre = text.substring(0, idxHtml)
            text = text.substring(idxHtml)
        }
        val hasHtml4Doctype = Regex("""<!DOCTYPE\s+[^>]+HTML\s+4\.0[^.]+>""").containsMatchIn(pre)
        // User-defined entities in the internal DTD subset are expanded at the
        // text level before any parser sees them (mirrors parse_html).
        val userEntities = LinkedHashMap<String, String>()
        for (m in Regex("""<!ENTITY\s+(\S+)\s+([^>]+)""").findAll(pre)) {
            var v = m.groupValues[2]
            if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v = v.substring(1, v.length - 1)
            userEntities[m.groupValues[1]] = v
        }
        if (userEntities.isNotEmpty()) {
            val pat = Regex("&(${userEntities.keys.joinToString("|") { Regex.escape(it) }});")
            text = pat.replace(text) { m -> userEntities[m.groupValues[1]] ?: m.value }
        }

        // Null bytes from &#0; entities break parsers downstream.
        text = text.replace("\u0000", "")
        text = cleanWordDoc(text)
        // Pristine copy for the HTML5-doctype probe below — the cascade may
        // rewrite `text`, but check_for_html5 matches against the original.
        val whole = text

        // Cascade: strict XML -> entity-replaced strict XML -> HTML5. A
        // successfully parsed tree that is really an HTML5 document (DOCTYPE
        // html + an un-namespaced <svg>) is re-routed to the HTML5 parser
        // exactly like upstream's HTML5Doc exception (parse_utils.py:138-141).
        val root: DomElement = try {
            parseStrictUnlessHtml5(text, whole)
        } catch (e: Html5DocException) {
            parseHtml5(text)
        } catch (e: XmlPullParserException) {
            try {
                parseStrictUnlessHtml5(replaceEntitiesKeepXml(text), whole)
            } catch (e2: Html5DocException) {
                parseHtml5(text)
            } catch (e2: XmlPullParserException) {
                parseHtml5(text)
            }
        }

        // HTML 4 docs (kindlegen) carry uppercase tags/attrs.
        val lastChild = root.elementChildren.lastOrNull()
        if (hasHtml4Doctype || root.tag == "HTML" ||
            (lastChild != null && (lastChild.getAttr("LANG") != null || lastChild.getAttr("DIR") != null))
        ) {
            lowercaseNames(root)
        }

        // Non-<html> roots get wrapped under a synthetic html>body. Upstream
        // (parse_utils.py:242-247) detaches every node of data.iter() — the
        // root INCLUDED — in document order and appends each to the synthetic
        // body: the fragment is fully FLATTENED, the old root and any inner
        // wrappers surviving as empty shells (root==body included).
        if (root.localName != "html") {
            if (root.localName in nonHtmlFileTags) throw NotHTMLException(root.localName)
            val nroot = DomElement("html")
            val body = DomElement("body")
            nroot.append(body)
            val preorder = root.walk().toList()
            for (node in preorder) if (node is DomElement) node.children.clear()
            for (node in preorder) body.append(node)
            return coerceAndNormalize(nroot)
        }
        return coerceAndNormalize(root)
    }

    /** container.xml / OPF / NCX: strict namespaced XML, no HTML normalization. */
    fun parseXml(bytes: ByteArray): DomElement {
        val text = stripEncodingDeclarations(decodeBytes(bytes))
        return try {
            parseStrictXml(text)
        } catch (e: XmlPullParserException) {
            try {
                parseStrictXml(replaceEntitiesKeepXml(text))
            } catch (e2: XmlPullParserException) {
                throw HtmlParseException("XML parse failed: ${e2.message}", e2)
            }
        }
    }

    // ---- strict XML (safe_xml_fromstring, recover=False) ----

    /** Raised when a strictly-parsed tree is really HTML5 (see parse_utils.py:138-141). */
    private class Html5DocException : Exception()

    private val html5Doctype = Regex("""<!DOCTYPE\s+html\s*>""", RegexOption.IGNORE_CASE)

    /** parse + check_for_html5: DOCTYPE html in [whole] plus a bare <svg> in the tree forces the html5 path. */
    private fun parseStrictUnlessHtml5(text: String, whole: String): DomElement {
        val root = parseStrictXml(text)
        if (html5Doctype.containsMatchIn(whole) &&
            root.walk().filterIsInstance<DomElement>().any { it.namespace.isEmpty() && it.localName == "svg" }
        ) {
            throw Html5DocException()
        }
        return root
    }

    /**
     * fast_html_entities replace_all_entities(keep_xml_entities=True): expand
     * every &name;/&#nn;/&#xhh; reference (trailing `;` required) EXCEPT the
     * XML five, which stay escaped so the retry parse stays well-formed XML.
     * Numeric references to invalid codepoints are deleted (upstream
     * add_entity yields a zero-length replacement); unknown names are left
     * as-is so the retry fails and the cascade falls through to HTML5.
     */
    internal fun replaceEntitiesKeepXml(text: String): String {
        if ('&' !in text) return text
        return entityRef.replace(text) { m ->
            val ref = m.groupValues[1]
            when {
                ref.startsWith("#") -> {
                    val cp = if (ref.length > 1 && (ref[1] == 'x' || ref[1] == 'X')) {
                        ref.substring(2).toIntOrNull(16)
                    } else {
                        ref.substring(1).toIntOrNull()
                    }
                    when {
                        cp == null -> m.value
                        cp in KEEP_XML_CODEPOINTS -> m.value
                        else -> xmlCodepoint(cp)
                    }
                }
                ref in KEEP_XML_ENTITIES -> m.value
                else -> {
                    val expanded = Parser.unescapeEntities(m.value, false)
                    if (expanded == m.value) m.value else expanded
                }
            }
        }
    }

    private val entityRef = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")
    private val KEEP_XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")
    private val KEEP_XML_CODEPOINTS = setOf(38, 60, 62, 34, 39)

    /** Valid XML 1.0 codepoint → its char, anything else → deleted. */
    private fun xmlCodepoint(cp: Int): String = when (cp) {
        0x9, 0xA, 0xD -> String(Character.toChars(cp))
        in 0x20..0xD7FF, in 0xE000..0xFFFD, in 0x10000..0x10FFFF -> String(Character.toChars(cp))
        else -> ""
    }

    private fun parseStrictXml(text: String): DomElement {
        val parser = pullParserFactory()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(text))
        return buildFromPull(parser)
    }

    private fun buildFromPull(parser: XmlPullParser): DomElement {
        val stack = ArrayDeque<DomElement>()
        var root: DomElement? = null
        // Pending character data goes to the current element's .text or the
        // last child's .tail — exactly lxml's placement rules.
        val sb = StringBuilder()

        fun flushText() {
            if (sb.isEmpty()) return
            val chunk = sb.toString()
            sb.setLength(0)
            val top = stack.lastOrNull()
            if (top == null) {
                // Leading text before the root element: lxml rejects it
                // ("Start tag expected") so the cascade must fall through to
                // the HTML5 parser, which preserves it as body content.
                if (chunk.isNotBlank()) {
                    throw XmlPullParserException("leading content before root element")
                }
                return
            }
            val lastChild = top.children.lastOrNull()
            if (lastChild == null) {
                top.text = (top.text ?: "") + chunk
            } else {
                lastChild.tail = (lastChild.tail ?: "") + chunk
            }
        }

        while (true) {
            when (val t = parser.nextToken()) {
                XmlPullParser.START_TAG -> {
                    flushText()
                    val ns = parser.namespace
                    val tag = if (ns.isEmpty()) parser.name else "{$ns}${parser.name}"
                    val e = DomElement(tag)
                    for (i in 0 until parser.attributeCount) {
                        val ans = parser.getAttributeNamespace(i)
                        val aname = if (ans.isEmpty()) parser.getAttributeName(i)
                        else "{$ans}${parser.getAttributeName(i)}"
                        e.setAttr(aname, parser.getAttributeValue(i))
                    }
                    if (stack.isEmpty()) root = e else stack.last().append(e)
                    stack.addLast(e)
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT ->
                    parser.text?.let { if (it.isNotEmpty()) sb.append(it) }
                XmlPullParser.ENTITY_REF -> {
                    // kxml silently reports undefined entities as ENTITY_REF
                    // with null text; lxml (recover=False) rejects them, and so
                    // must we to keep the cascade honest.
                    val resolved = parser.text
                        ?: throw XmlPullParserException("undefined entity &${parser.name};", parser, null)
                    if (resolved.isNotEmpty()) sb.append(resolved)
                }
                XmlPullParser.END_TAG -> {
                    flushText()
                    stack.removeLastOrNull()
                        ?: throw XmlPullParserException("unbalanced end tag ${parser.name}", parser, null)
                    if (stack.isEmpty()) {
                        val rest = parser.remainingText()
                        if (rest != null && rest.isNotBlank()) {
                            throw XmlPullParserException("trailing content after root element", parser, null)
                        }
                        return requireNotNull(root) { "no root element parsed" }
                    }
                }
                XmlPullParser.COMMENT -> {
                    flushText()
                    val c = DomComment(isProcessingInstruction = false)
                    c.text = parser.text
                    stack.lastOrNull()?.append(c)
                }
                XmlPullParser.PROCESSING_INSTRUCTION -> {
                    flushText()
                    val pi = DomComment(isProcessingInstruction = true)
                    pi.text = parser.text
                    stack.lastOrNull()?.append(pi)
                }
                XmlPullParser.IGNORABLE_WHITESPACE -> sb.append(parser.text ?: "")
                XmlPullParser.END_DOCUMENT ->
                    throw XmlPullParserException("unexpected end of document before root closed", parser, null)
                XmlPullParser.DOCDECL, XmlPullParser.START_DOCUMENT -> { /* skipped */ }
                else -> { /* unknown token */ }
            }
        }
    }

    /** Everything after the root element must be whitespace-only, else fail. */
    private fun XmlPullParser.remainingText(): String? {
        val sb = StringBuilder()
        loop@ while (true) {
            when (nextToken()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.IGNORABLE_WHITESPACE ->
                    sb.append(text ?: "")
                XmlPullParser.ENTITY_REF -> return " non-ws"
                XmlPullParser.COMMENT, XmlPullParser.PROCESSING_INSTRUCTION, XmlPullParser.DOCDECL -> { /* ok */ }
                XmlPullParser.START_TAG -> return " non-ws"
                XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT -> break@loop
                else -> break@loop
            }
        }
        return sb.toString()
    }

    // ---- HTML5 fallback (html5_parse / _html4_parse) ----

    private fun parseHtml5(text: String): DomElement {
        val doc: Document = Jsoup.parse(text, "", Parser.htmlParser())
        val html = doc.children().firstOrNull()
            ?: return DomElement("html").also { it.append(DomElement("body")) }
        return convertJsoupElement(html, XHTML_NS)
    }

    // WHATWG foreign content: <svg>/<math> entered from HTML live in their
    // real namespaces, and HTML resumes inside the integration points
    // (svg:foreignObject/desc/title; math:mi/mo/mn/ms/mtext + html-encoded
    // annotation-xml). Everything else inherits the current namespace.
    private val svgHtmlIntegration = setOf("foreignObject", "desc", "title")
    private val mathmlTextIntegration = setOf("mi", "mo", "mn", "ms", "mtext")

    /** WHATWG "adjust foreign attributes": xlink:/xml: prefixes map to real namespaces; xmlns declarations are bindings, not attributes. */
    private val xlinkLocals = setOf("actuate", "arcrole", "href", "role", "show", "title", "type")

    private fun childNamespace(childTag: String, ctx: String): String = when {
        ctx == XHTML_NS && childTag == "svg" -> SVG_NS
        ctx == XHTML_NS && childTag == "math" -> MATHML_NS
        else -> ctx
    }

    private fun childrenContext(dom: DomElement, ctx: String): String {
        val local = dom.localName
        return when (dom.namespace) {
            SVG_NS -> if (local in svgHtmlIntegration) XHTML_NS else SVG_NS
            MATHML_NS -> when {
                local in mathmlTextIntegration -> XHTML_NS
                local == "annotation-xml" -> {
                    val enc = dom.getAttr("encoding")?.lowercase() ?: ""
                    if (enc.contains("text/html") || enc.contains("application/xhtml+xml")) XHTML_NS else MATHML_NS
                }
                else -> MATHML_NS
            }
            else -> ctx
        }
    }

    private fun adjustAttrKey(key: String): String? {
        if (key == "xmlns" || key.startsWith("xmlns:")) return null
        val i = key.indexOf(':')
        if (i <= 0) return key
        val prefix = key.substring(0, i)
        val local = key.substring(i + 1)
        return when (prefix) {
            "xlink" -> if (local in xlinkLocals) "{$XLINK_NS}$local" else key
            "xml" -> "{$XML_NAMESPACE}$local"
            else -> key
        }
    }

    private fun convertJsoupElement(el: Element, ns: String): DomElement {
        val dom = if (ns.isEmpty()) DomElement(el.tagName()) else DomElement("{$ns}${el.tagName()}")
        for (attr in el.attributes()) {
            adjustAttrKey(attr.key)?.let { dom.setAttr(it, attr.value) }
        }
        val childCtx = childrenContext(dom, ns)
        var last: DomNode? = null
        for (child in el.childNodes()) {
            when (child) {
                is TextNode -> {
                    val chunk = child.getWholeText()
                    if (chunk.isEmpty()) continue
                    if (last == null) dom.text = (dom.text ?: "") + chunk
                    else last!!.tail = (last!!.tail ?: "") + chunk
                }
                is Element -> {
                    val e = convertJsoupElement(child, childNamespace(child.tagName(), childCtx))
                    dom.append(e)
                    last = e
                }
                is Comment -> {
                    // jsoup funnels PIs into Comment nodes too; the html5 path
                    // never produces a real PI worth distinguishing.
                    val c = DomComment(isProcessingInstruction = false).apply { text = child.getData() }
                    dom.append(c)
                    last = c
                }
                is DataNode -> {
                    // Contents of <script>/<style> live in element text.
                    dom.text = (dom.text ?: "") + child.getWholeData()
                }
                else -> { /* unknown node kind: drop */ }
            }
        }
        return dom
    }

    // ---- shared text cleanup ----

    private fun lowercaseNames(root: DomElement) {
        for (node in root.walk().toList()) {
            if (node !is DomElement) continue
            node.tag = node.tag.lowercase()
            if (node.attrs.keys.any { it != it.lowercase() }) {
                val lowered = LinkedHashMap<String, DomAttr>(node.attrs.size)
                for ((k, v) in node.attrs) lowered[k.lowercase()] = DomAttr(k.lowercase(), v.value)
                node.attrs.clear()
                node.attrs.putAll(lowered)
            }
        }
    }

    /** clean_word_doc(): drop empty Word-namespaced tags that would materialize as HTML. */
    private fun cleanWordDoc(data: String): String {
        val prefixes = Regex("""xmlns:(\S+?)=".*?microsoft.*?"""").findAll(data).map { it.groupValues[1] }.toList()
        if (prefixes.isEmpty()) return data
        val alt = prefixes.joinToString("|") { Regex.escape(it) }
        val pat1 = Regex("""<($alt):([a-zA-Z0-9]+)[^>/]*?></\1:\2>""", RegexOption.DOT_MATCHES_ALL)
        val pat2 = Regex("""<($alt):([a-zA-Z0-9]+)[^>/]*?/>""")
        return pat2.replace(pat1.replace(data, ""), "")
    }
}
