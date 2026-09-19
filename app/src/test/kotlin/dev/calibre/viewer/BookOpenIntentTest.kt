// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors

package dev.calibre.viewer

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookOpenIntentTest {

    @Test
    fun `data URI has precedence over vendor fallbacks`() {
        val choice = chooseBookUri(
            data = "data",
            clipItems = listOf("clip"),
            extraStream = "stream",
        )
        assertEquals("data", choice?.value)
        assertEquals(BookUriCarrier.DATA, choice?.carrier)
    }

    @Test
    fun `clip data supports OEM single selection results`() {
        val choice = chooseBookUri<String>(
            data = null,
            clipItems = listOf("clip-1", "clip-2"),
            extraStream = "stream",
        )
        assertEquals("clip-1", choice?.value)
        assertEquals(BookUriCarrier.CLIP_DATA, choice?.carrier)
    }

    @Test
    fun `extra stream is the final URI fallback`() {
        val choice = chooseBookUri<String>(null, emptyList(), "stream")
        assertEquals("stream", choice?.value)
        assertEquals(BookUriCarrier.EXTRA_STREAM, choice?.carrier)
        assertNull(chooseBookUri<String>(null, emptyList(), null))
    }

    @Test
    fun `persistence requires both read and persistable grants`() {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val persist = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        assertFalse(hasPersistableReadGrant(0))
        assertFalse(hasPersistableReadGrant(read))
        assertFalse(hasPersistableReadGrant(persist))
        assertTrue(hasPersistableReadGrant(read or persist))
    }
}
