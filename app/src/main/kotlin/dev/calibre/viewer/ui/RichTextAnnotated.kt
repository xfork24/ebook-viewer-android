// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Compose mapping for [parseRichText] — the only file in this pair that
// touches androidx.compose, kept apart so the parser stays JVM-testable.

package dev.calibre.viewer.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** Host-message rich text as a Compose [AnnotatedString]. */
fun richTextOf(html: String): AnnotatedString = parseRichText(html).toAnnotatedString()

private fun RichText.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    append(text)
    for (span in spans) {
        addStyle(
            when (span.style) {
                RichStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                RichStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                RichStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
            },
            span.start,
            span.end,
        )
    }
}
