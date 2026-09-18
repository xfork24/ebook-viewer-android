// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Port of calibre/src/calibre/srv/tests/fast_css_transform.py — golden
// vectors for the CSS transform used by the viewer's book preprocessing.

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.transform.CssTransformer
import dev.calibre.viewer.parser.transform.formatGeneralCssDouble
import kotlin.test.Test
import kotlin.test.assertEquals

class CssTransformerTest {

    private fun d(src: String, expected: String, isDeclaration: Boolean = true, urlCallback: ((String) -> String?)? = null) =
        assertEquals(expected, CssTransformer.transformProperties(src, urlCallback, isDeclaration), "src=$src")

    // mirrors CPython str.upper identity behavior: '' returns the same object
    // (unchanged), any non-empty string a fresh one (rewritten)
    private val upperCase: (String) -> String? = { if (it.isEmpty()) null else it.uppercase() }

    private fun u(src: String, expected: String) = d(src, expected, urlCallback = upperCase)

    private fun s(src: String, expected: String) = d(src, expected, isDeclaration = false, urlCallback = upperCase)

    @Test
    fun `number parsing`() {
        // upstream compares against ast.literal_eval — int/float == across types
        for (x in ".314 -.314 0.314 0 2 +2 -1 1e2 -3.14E+2 2e-2".split(" ")) {
            val expected = when (x) {
                ".314" -> 0.314
                "-.314" -> -0.314
                "0.314" -> 0.314
                "0" -> 0L
                "2" -> 2L
                "+2" -> 2L
                "-1" -> -1L
                "1e2" -> 100L
                "-3.14E+2" -> -314.0
                "2e-2" -> 0.02
                else -> throw AssertionError("unexpected case $x")
            }
            val actual = CssTransformer.parseCssNumber(x)
            assertEquals(
                (expected as Number).toDouble(), actual.toDouble(), 0.0,
                "parse_css_number($x)",
            )
        }
        assertEquals(2L, CssTransformer.parseCssNumber("2em"))
        assertEquals(0.3, CssTransformer.parseCssNumber(".3em"))
        assertEquals(3L, CssTransformer.parseCssNumber("3x3"))
    }

    @Test
    fun `page break rewrites`() {
        s(".c { page-break-after: 1 always }", ".c { break-after: 1 always ; -webkit-column-break-after: 1 always }")
        s(
            ".c { page-break-after: always\ncolor:red }",
            ".c { break-after: always; -webkit-column-break-after: always\ncolor:red }",
        )
        s(".c { page-break-after: always\n}", ".c { break-after: always; -webkit-column-break-after: always\n}")
        s(
            ".c { page-break-after: always;color:red }",
            ".c { break-after: always; -webkit-column-break-after: always;color:red }",
        )
        s(".c { page-break-after: /**/always }", ".c { break-after: always ; -webkit-column-break-after: always }")
        s(
            ".c { page-break-after: always !important }",
            ".c { break-after: always !important ; -webkit-column-break-after: always !important }",
        )
        s(".c { page-break-after: always;}", ".c { break-after: always; -webkit-column-break-after: always;}")
        d("page-break-before: always", "break-before: always; -webkit-column-break-before: always")
    }

    @Test
    fun `url and import rewrites`() {
        s(".c{x:url(y)}", ".c{x:url(\"Y\")}")
        s("@im/* c */port \"x.y\";", "@import \"X.Y\";")
        s(
            "@import url(\"narrow.css\") supports(display: flex) handheld and (max-width: 400px);",
            "@import url(\"NARROW.CSS\") supports(display: flex) handheld and (max-width: 400px);",
        )
        s("@import url( x/*a*/.y);", "@import url(\"X.Y\");")

        u("background: url(  te  st.gif  ) 12; src: url(x)", "background: url(\"TE  ST.GIF\") 12; src: url(\"X\")")
        u("background: url(te/**/st.gif); xxx: url()", "background: url(\"TEST.GIF\"); xxx: url()")
        u("""background: uRl(t\)e/st.gif)""", "background: url(\"T)E/ST.GIF\")")
        u("a:url(  \"( )\" /**/ )", "a:url(\"( )\")")
        // null = leave unchanged (upstream: identity callback), output verbatim
        d("a:url(  \"(/*)\"  )", "a:url(  \"(/*)\"  )", urlCallback = { null })
    }

    @Test
    fun `to_chars general formatting`() {
        // Every vector verified against clang++/libc++ (the toolchain family
        // calibre's fast_css_transform is built with): shortest round-trip
        // digits, fixed notation iff -4 <= X < 6, exponent padded to 2 digits.
        val cases = listOf(
            5.0 to "5",
            1.5 to "1.5",
            0.0 to "0",
            -0.0 to "-0",
            0.0001 to "0.0001",
            0.00012345 to "0.00012345",
            0.1 to "0.1",
            0.75 to "0.75",
            2.8346456693 to "2.8346456693",
            100000.0 to "100000",
            123456.0 to "123456",
            123456.7 to "123456.7",
            999999.5 to "999999.5", // X=5 — last fixed value
            1234567.0 to "1.234567e+06", // X=6 — first scientific value
            1000000.5 to "1.0000005e+06",
            -1234567.0 to "-1.234567e+06",
            -0.00001 to "-1e-05",
            9.999999999999999e-05 to "9.999999999999999e-05",
            4.9999999999999996e-05 to "4.9999999999999996e-05",
            1.234899785814033e13 to "1.234899785814033e+13",
            1.23456789012345e14 to "1.23456789012345e+14",
            1.0e15 to "1e+15",
            1.5e15 to "1.5e+15",
            1.0e16 to "1e+16",
            1.0e300 to "1e+300",
            1.0e-300 to "1e-300",
        )
        for ((v, expected) in cases) {
            assertEquals(expected, formatGeneralCssDouble(v), "formatGeneralCssDouble($v)")
        }
    }

    @Test
    fun `font size rewrites`() {
        // X=13 → scientific per libc++ to_chars(general): fixed iff -4<=X<6
        d("font-size: 197583965730245.28px", "font-size: 1.234899785814033e+13rem")
        d("font-size: 19.28px", "font-size: 1.205rem")
        d("font-size:+19.28px", "font-size:1.205rem")
        d("font-size: .28in", "font-size: 1.6800000000000002rem")
        d("font-size: +.28in", "font-size: 1.6800000000000002rem")
        d("""f\ont-s\69z\65 : 16\px""", "font-size: 1rem")
        d("font -size: 16px", "font -size: 16px")
        d("font-/* */size: 1/*x*/6/**/p/**/x !important", "font-size: 1rem !important")
        d("fOnt-size :16px", "fOnt-size :1rem")
        d("fönt-size :16px", "fönt-size :16px")
        d("font-size:2%", "font-size:2%")
        d(
            "font-size: 72pt; margin: /*here*/ 20px; font-size: 2in",
            "font-size: 6rem; margin: /*here*/ 20px; font-size: 12rem",
        )
        d("""font: "some 'name" 32px""", """font: "some 'name" 2rem""")
        d("""font: 'some "name' 32px""", """font: 'some "name' 2rem""")
        d("""font: 'some \n ame' 32px""", """font: "some n ame" 2rem""")
        d("font: 'some \\\nname' 32px", """font: "some name" 2rem""")
        d("font: sans-serif 16px/3", "font: sans-serif 1rem/3")
        d("font: sans-serif small/17", "font: sans-serif 0.8rem/17")
    }

    @Test
    fun `writing mode and passthrough`() {
        d(
            "-epub-writing-mode: a; -web/* */kit-writing-mode: b; writing-mode: c",
            "writing-mode: a; writing-mode: b; writing-mode: c",
        )
        d("xxx:yyy", "xxx:yyy")
    }

    @Test
    fun `full stylesheet`() {
        val sheet = """
@import "b/loc.test";
@media screen {
    font: 16px calc(20vw - 30rem);

    .cls {
        color: red;
        font-size: 16px;
        background: url("b/loc.test")
    }

    #moo.cat {
        x: url("b/loc.test")
    }

    @zoo {
        not(.woo) and why {
            font: 16px "something something" 16;
            page-break-before: avoid
        }
    }
}
.why { font: 16px}
"""
        val expected = sheet
            .replace("16px", "1rem")
            .replace("b/loc.test", "B/LOC.TEST")
            .replace("page-", "break-before: avoid; -webkit-column-")
        s(sheet, expected)
    }
}
