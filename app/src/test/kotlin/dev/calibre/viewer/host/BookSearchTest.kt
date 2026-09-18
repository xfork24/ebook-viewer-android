// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// gui2/viewer/search.py parity: the text_to_regex normalization (quote
// folding, whitespace classes, invisible joiners), the SearchQuery modes,
// the html_as_json flattening rules, and the spine-wrapping result stream
// with its for_js wire shape.

package dev.calibre.viewer.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BookSearchTest {

    // ---- text_to_regex -------------------------------------------------------

    @Test
    fun `query text matches across whitespace and invisible characters`() {
        val pat = SearchQuery("a b", SearchMode.NORMAL, caseSensitive = false).regex
        assertTrue(pat.matcher("a b").find())
        assertTrue(pat.matcher("a  b").find())
        assertTrue(pat.matcher("say a\nb now").find())

        val invisibleJoiner = SearchQuery("ab", SearchMode.NORMAL, caseSensitive = false).regex
        assertTrue(invisibleJoiner.matcher("a‌b").find()) // zero-width joiner in the book

        val softHyphen = SearchQuery("abc", SearchMode.NORMAL, caseSensitive = false).regex
        assertTrue(softHyphen.matcher("ab­c").find())
        assertFalse(softHyphen.matcher("abdc").find())
    }

    @Test
    fun `quotes fold their curly counterparts and case folds by default`() {
        val pat = SearchQuery("don't \"x\"", SearchMode.NORMAL, caseSensitive = false).regex
        assertTrue(pat.matcher("don't “x”").find())
        assertTrue(pat.matcher("DON'T \"X\"").find())

        val cs = SearchQuery("Hello", SearchMode.NORMAL, caseSensitive = true).regex
        assertFalse(cs.matcher("hello").find())
        assertTrue(cs.matcher("Hello").find())
    }

    @Test
    fun `leading and trailing whitespace become separator classes`() {
        val pat = SearchQuery(" x ", SearchMode.NORMAL, caseSensitive = false).regex
        assertTrue(pat.matcher("word x tail").find())
        assertFalse(pat.matcher("word xtail").find())
        assertFalse(pat.matcher("a x").find())
    }

    // ---- modes ---------------------------------------------------------------

    @Test
    fun `word mode only matches whole words`() {
        val q = SearchQuery("cat", SearchMode.WORD, caseSensitive = false)
        val hits = searchAll(q, "The cat sat in the category of cats")
        assertEquals(listOf("cat"), hits.map { it.first })
        // "category" and "cats" must not match
        assertFalse(hits.any { it.second == 15 })
    }

    @Test
    fun `regex mode passes the expression through`() {
        val q = SearchQuery("colou?r", SearchMode.REGEX, caseSensitive = false)
        assertEquals(2, searchAll(q, "color colour").size)
    }

    @Test
    fun `near mode needs all words within the interval`() {
        val searcher = BookSearcher(
            createTempDir(prefix = "near-search"),
            Json.parseToJsonElement("""{"spine":[]}""").jsonObject,
        )
        val near = SearchQuery("alpha beta", SearchMode.NEAR, caseSensitive = false)
        assertTrue(searcher.searchInName("alpha xxxxxxxxxxxxxxxxxxxx beta", near).isNotEmpty())
        val tight = SearchQuery("alpha beta 5", SearchMode.NEAR, caseSensitive = false)
        assertTrue(searcher.searchInName("alpha xxxxxxxxxxxxxxxxxxxx beta", tight).isEmpty())
        assertTrue(searcher.searchInName("alpha xx beta", tight).isNotEmpty())
    }

    @Test
    fun `is_empty follows the punctuation rule`() {
        assertTrue(SearchQuery("", SearchMode.NORMAL, false).isEmpty)
        assertTrue(SearchQuery("  ", SearchMode.NORMAL, false).isEmpty)
        assertTrue(SearchQuery("!!!", SearchMode.NORMAL, false).isEmpty)
        assertFalse(SearchQuery("!!!", SearchMode.REGEX, false).isEmpty)
        assertFalse(SearchQuery("书", SearchMode.NORMAL, false).isEmpty)
    }

    // ---- flattening ----------------------------------------------------------

    @Test
    fun `flattening skips script head and ruby annotations but keeps tails`() {
        val serialized = Json.parseToJsonElement(
            """
            {"version":1,"tree":{"n":"html","c":[
              {"n":"head","c":[{"n":"title","x":"Page title"}]},
              {"n":"body","x":"B","l":"dropped-body-tail","c":[
                "Hello ",
                {"n":"p","a":[["id","p1"]],"x":"big ","c":[
                  {"n":"em","x":"world","l":"!"}
                ],"l":" tail"},
                {"n":"script","x":"var i=0;","l":" st"},
                {"n":"img","a":[["id","im"]],"x":"alt-text","l":" it"},
                {"n":"ruby","c":["漢",{"n":"rt","x":"かん"}],"l":" ji"}
              ]}
            ]}}
            """.trimIndent(),
        ).jsonObject

        val flat = SerializedTextExtractor.extract(serialized)
        // body text + "Hello " + "big world!" + " tail" (script skipped entirely,
        // img text ignored but its tail kept, ruby annotation dropped)
        assertEquals("BHello big world! tail it漢 ji", flat.text)
        // anchors are recorded at the position where the element starts
        assertEquals(mapOf("p1" to "BHello ".length, "im" to "BHello big world! tail".length), flat.anchorOffsetMap)
    }

    // ---- searcher ------------------------------------------------------------

    @Test
    fun `search wraps the spine from the current file and numbers results`() {
        val dir = createTempDir(prefix = "search")
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
        writeFile(dir, "ch1.xhtml", tree("第一章", "胡适的口述自传从这里开始。"))
        // two hits with identical context windows → the same q, told apart by index
        writeFile(dir, "ch2.xhtml", tree("第二章", "A胡适B胡适A"))
        val manifest = Json.parseToJsonElement(
            """
            {"spine":["ch1.xhtml","ch2.xhtml"],
             "toc":{"title":"t","dest":null,"frag":null,"id":3,"children":[
               {"title":"第一章","dest":"ch1.xhtml","frag":null,"id":0,"children":[]},
               {"title":"第二章","dest":"ch2.xhtml","frag":null,"id":2,"children":[
                 {"title":"第二节","dest":"ch2.xhtml","frag":"s1","id":1,"children":[]}
               ]}
             ]}}
            """.trimIndent(),
        ).jsonObject

        val searcher = BookSearcher(dir, manifest)
        val results = ArrayList<SearchResult>()
        val ran = searcher.search(SearchQuery("胡适", SearchMode.NORMAL, false), "ch1.xhtml") { results.add(it) }
        assertTrue(ran)
        assertEquals(3, results.size)
        // starts at ch1 (the current file), then wraps into ch2
        assertEquals(listOf("ch1.xhtml", "ch2.xhtml", "ch2.xhtml"), results.map { it.fileName })
        assertEquals(listOf(0, 1, 1), results.map { it.spineIdx })
        assertEquals(listOf(1L, 2L, 3L), results.map { it.resultNum })
        // the two ch2 hits share their context key q → index distinguishes them
        assertEquals(results[1].q, results[2].q)
        assertEquals(listOf(0, 0, 1), results.map { it.index })
        // ToC chains resolve per file (第二节's frag has no anchor → offset 0)
        assertEquals(listOf("第一章"), results[0].tocTitles)
        assertEquals(listOf("第二章", "第二节"), results[1].tocTitles)

        // starting from an unknown file: no search at all (Qt emits only the
        // finished marker)
        assertFalse(searcher.search(SearchQuery("胡适", SearchMode.NORMAL, false), "nope.xhtml") {})
    }

    @Test
    fun `for_js carries the exact wire keys`() {
        val sr = SearchResult("a.xhtml", 1, 2, 30, "before", "match", "after", "q", 7)
        val js = sr.forJs(SearchMode.WORD, onDiscovery = JsonPrimitive(4))
        assertEquals(
            listOf(
                "file_name", "spine_idx", "index", "text", "before", "after",
                "mode", "q", "result_num", "on_discovery",
            ),
            js.keys.toList(),
        )
        assertEquals("word", js["mode"]!!.jsonPrimitive.content)
        assertEquals(7L, js["result_num"]!!.jsonPrimitive.content.toLong())
        val plain = sr.forJs(SearchMode.NORMAL)
        assertFalse("on_discovery" in plain)
    }

    // ---- helpers -------------------------------------------------------------

    private fun searchAll(q: SearchQuery, raw: String): List<Pair<String, Int>> =
        q.regex.matcher(raw).results().map { it.group() to it.start() }.toList()

    private fun tree(title: String, body: String): String =
        """{"version":1,"tree":{"n":"html","c":[{"n":"head","c":[{"n":"title","x":"$title"}]},""".trimIndent() +
            """{"n":"body","c":["$body"]}]}}"""

    private fun writeFile(dir: File, name: String, content: String) {
        File(dir, name).writeText(content, Charsets.UTF_8)
    }
}
