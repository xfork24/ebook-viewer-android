// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The standalone viewer's full-book search, ported from
// gui2/viewer/search.py. The Qt host searches the *rendered* book: the
// html_as_json files on disk are flattened into a plain text stream per
// spine file (searchable_text_for_name) and regex-matched there; results
// are streamed to the page via show_search_result, which selects and
// scrolls to the match inside the live iframe.
//
// Deviations from Qt, deliberately:
//  - the case-insensitive "Contains"/"Whole words" modes take the regex
//    path instead of ICU primary collation (accent/punctuation-insensitive
//    matching is dropped; plain Unicode case folding remains)
//  - no search-history persistence (vprefs) — the panel keeps the last
//    query for the session

package dev.calibre.viewer.host

import java.io.File
import java.util.regex.Pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

enum class SearchMode(val wireName: String) {
    NORMAL("normal"),
    WORD("word"),
    NEAR("near"),
    REGEX("regex");

    companion object {
        fun fromWire(name: String?): SearchMode =
            entries.firstOrNull { it.wireName == name } ?: NORMAL
    }
}

/** search.py:quote_map — ASCII quotes also fold their curly counterparts. */
private val QUOTE_MAP = mapOf('"' to "\"“”", '\'' to "'‘’")
private val QUOTE_SPLIT = Regex("(['\"])")

/** search.py:invisible_chars — optional soft hyphen / ZWNJ / ZWJ between characters. */
private const val INVISIBLE_CHARS = "(?:[\u00ad\u200c\u200d]{0,1})"

internal fun textToRegex(text: String): String {
    val hasLeading = text.trimStart() != text
    val hasTrailing = text.trimEnd() != text
    if (text.isNotEmpty() && text.isBlank()) return "\\s+"
    val parts = ArrayList<String>()
    for (wpart in splitKeepingWhitespace(text.trim())) {
        if (wpart.isBlank()) {
            parts.add("\\s+")
        } else {
            for (part in QUOTE_SPLIT.splitKeeping(wpart)) {
                val folded = QUOTE_MAP[part.singleOrNull()]
                if (folded != null) {
                    parts.add("[$folded]")
                } else {
                    parts.add(part.map { Regex.escape(it.toString()) }.joinToString(INVISIBLE_CHARS))
                }
            }
        }
    }
    if (hasLeading) parts.add(0, "\\s+")
    if (hasTrailing) parts.add("\\s+")
    return parts.joinToString("")
}

/** re.split(r'(\s+)', text) semantics: separators kept as their own parts. */
private fun splitKeepingWhitespace(text: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var inWs = false
    for (c in text) {
        val ws = c.isWhitespace()
        if (sb.isNotEmpty() && ws != inWs) {
            out.add(sb.toString())
            sb.setLength(0)
        }
        inWs = ws
        sb.append(c)
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out
}

/** re.split(pattern, text) with the capture group kept, for the quote splitter. */
private fun Regex.splitKeeping(text: String): List<String> {
    val out = ArrayList<String>()
    var start = 0
    for (m in findAll(text)) {
        if (m.range.first > start) out.add(text.substring(start, m.range.first))
        out.add(m.value)
        start = m.range.last + 1
    }
    if (start < text.length) out.add(text.substring(start))
    return out
}

/** REGEX_FLAGS = MULTILINE | UNICODE (+ case folding when insensitive). */
private fun patternFlags(caseSensitive: Boolean, dotAll: Boolean = false): Int {
    var flags = Pattern.MULTILINE or Pattern.UNICODE_CHARACTER_CLASS or Pattern.UNICODE_CASE
    if (!caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE
    if (dotAll) flags = flags or Pattern.DOTALL
    return flags
}

/** search.py:Search — one immutable query plus its compiled matchers. */
data class SearchQuery(
    val text: String,
    val mode: SearchMode,
    val caseSensitive: Boolean,
    val backwards: Boolean = false,
) {
    val isEmpty: Boolean
        get() = text.isEmpty() ||
            (mode != SearchMode.REGEX && mode != SearchMode.NEAR &&
                PunctuationOnly.replace(text, "").isEmpty())

    val regex: Pattern by lazy {
        val expr = when (mode) {
            SearchMode.REGEX -> text
            SearchMode.WORD -> text.trim().split(WHITESPACE)
                .filter { it.isNotEmpty() }
                .joinToString("\\s+") { "\\b${textToRegex(it)}\\b" }
            else -> textToRegex(text)
        }
        Pattern.compile(expr, patternFlags(caseSensitive))
    }

    /** search.py:near_search_data — (per-word patterns, joined full-text pattern). */
    val nearSearchData: Pair<List<Pattern>, Pattern> by lazy {
        val (words, interval) = wordsAndIntervalForNear(text)
        val flags = patternFlags(caseSensitive, dotAll = true)
        val wordPats = words.map { Pattern.compile("\\b$it\\b", flags) }
        val matchAnyWord = "(?:\\b(?:${words.joinToString("|")})\\b)"
        val joiner = ".{1,${maxOf(1, interval)}}"
        wordPats to Pattern.compile(words.joinToString(joiner) { matchAnyWord }, flags)
    }

    companion object {
        // search.py is_empty: regex.sub(r'[\s\p{P}]+', '', text) — whitespace/punctuation only
        private val PunctuationOnly = Regex("[\\s\\p{P}]+")
        private val WHITESPACE = Regex("\\s+")

        /** search.py:words_and_interval_for_near — trailing integer = max gap, default 60. */
        fun wordsAndIntervalForNear(expr: String): Pair<List<String>, Int> {
            val parts = expr.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            val words = ArrayList<String>()
            var interval = 60
            for ((i, q) in parts.withIndex()) {
                if (i == parts.size - 1 && i > 1 && q.all { it.isDigit() }) {
                    interval = q.toInt()
                } else {
                    words.add(textToRegex(q))
                }
            }
            return words to interval
        }
    }
}

/** The flattened text of one spine file plus id → text-offset anchors. */
class SearchableText internal constructor(
    val text: String,
    val anchorOffsetMap: Map<String, Int>,
)

/**
 * search.py:searchable_text_for_name — walk the html_as_json tree exactly
 * like the Python: body children only, script/style/title/head not visited,
 * img/math/rt/rp/rtc text ignored (tails still land), ruby annotations
 * stripped. Comment nodes carry their text in "x" and flow through the same
 * generic path, as upstream. The body element's own tail is not part of the
 * flat text (the JS side drops it too).
 */
object SerializedTextExtractor {

    private val NO_VISIT = setOf("script", "style", "title", "head")
    private val IGNORE_TEXT = setOf("img", "math", "rt", "rp", "rtc")

    fun extract(serialized: JsonObject): SearchableText {
        val out = StringBuilder()
        var textPos = 0
        val anchorOffsetMap = LinkedHashMap<String, Int>()
        // stack entries: the node/text plus the ruby and ignore flags that
        // applied when it was pushed (Python's 3-tuples)
        val stack = ArrayDeque<Frame>()
        val bodies = HashSet<JsonObject>() // identity not needed: body nodes are distinct

        val tree = (serialized["tree"] as? JsonObject) ?: return SearchableText("", emptyMap())
        for (child in childrenOf(tree)) {
            if (nameOf(child) == "body") {
                val body = child as JsonObject
                bodies.add(body)
                stack.addLast(Frame(body, inRuby = false, ignoresText = false))
            }
        }
        while (stack.isNotEmpty()) {
            val (frameNode, inRuby, parentIgnoresText) = stack.removeLast()
            // JSON array string children are JsonPrimitive instances, while
            // element text/tails pushed below are already Kotlin Strings.
            // Treat both as text nodes instead of casting primitives to an
            // object (which made every normal EPUB search fail).
            val rawText = when (frameNode) {
                is String -> frameNode
                is JsonPrimitive -> frameNode.takeIf { it.isString }?.content
                else -> null
            }
            if (rawText != null) {
                val s = if (inRuby) rawText.trim() else rawText
                out.append(s)
                textPos += s.length
                continue
            }
            val node = frameNode as? JsonObject ?: continue
            val name = nameOf(node) ?: "" // comments have no "n"
            val text = node.str("x")
            val tail = node.str("l")
            for (attr in attrListOf(node)) {
                if (attr.firstOrNull()?.strOrNull() == "id") {
                    val aid = attr.getOrNull(1)?.strOrNull()
                    if (aid != null && aid !in anchorOffsetMap) {
                        anchorOffsetMap[aid] = textPos
                    }
                }
            }
            if (name in NO_VISIT) continue
            val nodeInRuby = inRuby
            val rubyNow = inRuby || name == "ruby"
            val ignoresHere = parentIgnoresText || name in IGNORE_TEXT

            if (text != null && !ignoresHere) {
                val t = if (inRuby) text.trim() else text
                out.append(t)
                textPos += t.length
            }
            if (tail != null && !parentIgnoresText && node !in bodies) {
                stack.addLast(Frame(tail, nodeInRuby, ignoresHere))
            }
            val children = childrenOf(node)
            for (i in children.indices.reversed()) {
                stack.addLast(Frame(children[i], rubyNow, ignoresHere))
            }
        }
        return SearchableText(out.toString(), anchorOffsetMap)
    }

    private data class Frame(val node: Any, val inRuby: Boolean, val ignoresText: Boolean)

    private fun childrenOf(node: JsonObject): List<JsonElement> =
        (node["c"] as? JsonArray)?.toList() ?: emptyList()

    private fun attrListOf(node: JsonObject): List<JsonArray> =
        (node["a"] as? JsonArray)?.filterIsInstance<JsonArray>() ?: emptyList()

    private fun nameOf(node: Any?): String? = (node as? JsonObject)?.str("n")

    private fun JsonObject.str(key: String): String? = get(key).strOrNull()

    private fun JsonElement?.strOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
}

/** search.py:SearchResult — one match, as shown in the panel and sent to the page. */
data class SearchResult(
    val fileName: String,
    val spineIdx: Int,
    val index: Int,
    val offset: Int,
    val before: String,
    val text: String,
    val after: String,
    val q: String,
    val resultNum: Long,
    /** TOC chain leading to this result (outermost…leaf titles); empty when unknown. */
    val tocTitles: List<String> = emptyList(),
    val isHidden: Boolean = false,
) {
    /** The same result with [isHidden] set (search_result_not_found). */
    fun hidden(): SearchResult = copy(isHidden = true)

    /** search.py:for_js — the exact dict the page's show_search_result expects. */
    fun forJs(mode: SearchMode, onDiscovery: JsonElement? = null): JsonObject = buildJsonObject {
        put("file_name", fileName)
        put("spine_idx", spineIdx)
        put("index", index)
        put("text", text)
        put("before", before)
        put("after", after)
        put("mode", mode.wireName)
        put("q", q)
        put("result_num", resultNum)
        if (onDiscovery != null) put("on_discovery", onDiscovery)
    }

    /** search.py:is_result — how the page's not-found/discovered reports are matched back. */
    fun isResult(other: JsonObject): Boolean =
        other.intOrNull("spine_idx") == spineIdx &&
            other.intOrNull("index") == index &&
            other.strOrNull("q") == q
}

/**
 * gui2/viewer/search.py against a rendered book directory: spine-ordered
 * search starting at (and wrapping from) the file currently displayed.
 */
class BookSearcher(private val bookDir: File, manifest: JsonObject) {

    private val spine: List<String> =
        (manifest["spine"] as? JsonArray)?.filterIsInstance<JsonPrimitive>()?.map { it.content }
            ?: emptyList()

    // get_toc_data(): spine name → toc nodes targeting it, node id → parent id
    private val spineTocMap: Map<String, List<JsonObject>>
    private val parentMap: Map<Long, Long>
    private val spineIdxMap: Map<String, Int> = spine.withIndex().associate { it.value to it.index }
    private val nodesById: Map<Long, JsonObject>

    private val textCache = HashMap<String, SearchableText>()

    init {
        val toc = manifest["toc"] as? JsonObject
        val byDest = HashMap<String, MutableList<JsonObject>>()
        val parents = HashMap<Long, Long>()
        val byId = HashMap<Long, JsonObject>()
        if (toc != null) {
            val spineSet = spine.toHashSet()
            fun visit(node: JsonObject) {
                node.longOrNullOf("id")?.let { byId[it] = node }
                val dest = node.strOrNull("dest")
                if (dest != null && dest in spineSet) {
                    byDest.getOrPut(dest) { ArrayList() }.add(node)
                }
                val kids = (node["children"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
                val parentId = node.longOrNullOf("id")
                for (kid in kids) {
                    val cid = kid.longOrNullOf("id")
                    if (parentId != null && cid != null) parents[cid] = parentId
                    visit(kid)
                }
            }
            visit(toc)
        }
        spineTocMap = byDest
        parentMap = parents
        nodesById = byId
    }

    /** search.py:search_in_name — (before, text, after, offset) per match. */
    internal fun searchInName(raw: String, query: SearchQuery, ctxSize: Int = 75): List<Match4> {
        val spans: List<IntRange> = when {
            query.mode == SearchMode.NEAR -> {
                val (wordPats, fullPat) = query.nearSearchData
                fullPat.matcher(raw).results()
                    .filter { m -> wordPats.all { it.matcher(m.group()).find() } }
                    .map { m -> m.start() until m.end() }
                    .toList()
            }
            else -> query.regex.matcher(raw).results()
                .map { m -> m.start() until m.end() }
                .toList()
        }
        return spans.map { r ->
            val start = r.first
            val end = r.last + 1
            Match4(
                before = raw.substring(maxOf(0, start - ctxSize), start),
                text = raw.substring(start, end),
                after = raw.substring(end, minOf(raw.length, end + ctxSize)),
                offset = start,
            )
        }
    }

    /**
     * run_searches(): iterate the spine from [startName] (wrapping), emitting
     * results in book order. Returns false when the start name is unknown —
     * Qt emits only the finished marker in that case.
     */
    fun search(query: SearchQuery, startName: String?, onResult: (SearchResult) -> Unit): Boolean {
        val spineIdx = startName?.let { spineIdxMap[it] } ?: -1
        if (spineIdx < 0 || spine.isEmpty()) return false
        var resultNum = 0L
        for (n in spine.indices) {
            val idx = (spineIdx + n) % spine.size
            val name = spine[idx]
            val text = searchableTextFor(name) ?: continue
            val counter = HashMap<String, Int>()
            for (m in searchInName(text.text, query)) {
                val q = m.before.takeLast(15) + m.text + m.after.take(15)
                resultNum += 1
                onResult(
                    SearchResult(
                        fileName = name,
                        spineIdx = idx,
                        index = counter[q] ?: 0,
                        offset = m.offset,
                        before = m.before,
                        text = m.text,
                        after = m.after,
                        q = q,
                        resultNum = resultNum,
                        tocTitles = tocTitlesFor(name, m.offset),
                    ),
                )
                counter[q] = (counter[q] ?: 0) + 1
            }
        }
        return true
    }

    fun searchableTextFor(name: String): SearchableText? = textCache.getOrPut(name) {
        val f = File(bookDir, name)
        if (!f.isFile) return null
        try {
            SerializedTextExtractor.extract(Json.parseToJsonElement(f.readText(Charsets.UTF_8)).jsonObject)
        } catch (e: Exception) {
            return null
        }
    }

    // ---- ToC location (search.py:get_toc_data / ToCOffsetMap) ----------------

    private fun tocTitlesFor(name: String, offset: Int): List<String> {
        val idx = spineIdxMap[name] ?: return emptyList()
        val nodes = spineTocMap[name] ?: return emptyList()
        val anchorMap = searchableTextFor(name)?.anchorOffsetMap ?: emptyMap()

        val matches = ArrayList<JsonObject>()
        for (node in nodes) {
            val nodeId = node.longOrNullOf("id")
            if (nodeId == null) continue
            val anchor = anchorMap[node.strOrNull("frag")] ?: 0
            if (anchor > offset) break
            matches.add(node)
        }
        if (matches.isEmpty()) {
            // ToCOffsetMap.previous_toc_node: last node of the closest
            // earlier spine file that has any
            for (i in (idx - 1) downTo 0) {
                val ptn = spineTocMap[spine[i]]
                if (!ptn.isNullOrEmpty()) {
                    return listOfNotNull(ptn.last().strOrNull("title"))
                }
            }
            return emptyList()
        }
        // ancestors of the deepest match, root dropped, outermost first
        val chain = ArrayList<String>()
        var cursor: JsonObject? = matches.last()
        val lineage = ArrayList<JsonObject>()
        while (cursor != null) {
            lineage.add(cursor)
            val parentId = cursor.longOrNullOf("id")?.let { parentMap[it] }
            cursor = parentId?.let { nodesById[it] }
        }
        if (lineage.size > 1) lineage.removeAt(lineage.lastIndex) // the root
        for (node in lineage.asReversed()) {
            node.strOrNull("title")?.let { chain.add(it) }
        }
        return chain
    }

    internal data class Match4(val before: String, val text: String, val after: String, val offset: Int)
}

private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.longOrNullOf(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
