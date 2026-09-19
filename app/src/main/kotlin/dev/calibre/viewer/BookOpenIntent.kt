// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Normalises the several Intent shapes used by Android and vendor file
// pickers. Some OEM pickers put a single result in ClipData (or EXTRA_STREAM)
// instead of Intent.data, so the activity must retain the whole result Intent
// rather than reducing it to a nullable Uri.

package dev.calibre.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract

internal enum class BookUriCarrier {
    DATA,
    CLIP_DATA,
    EXTRA_STREAM,
    RECENT,
}

internal data class BookOpenRequest(
    val uri: Uri,
    val grantFlags: Int,
    val sourceAction: String?,
    val carrier: BookUriCarrier,
)

internal sealed interface BookPickerResult {
    data class Selected(val request: BookOpenRequest) : BookPickerResult
    data object Cancelled : BookPickerResult
    data object MissingUri : BookPickerResult
}

/** Generic seam so URI precedence and OEM fallbacks stay plain-JVM testable. */
internal data class BookUriChoice<T>(val value: T, val carrier: BookUriCarrier)

internal fun <T> chooseBookUri(
    data: T?,
    clipItems: List<T>,
    extraStream: T?,
): BookUriChoice<T>? = when {
    data != null -> BookUriChoice(data, BookUriCarrier.DATA)
    clipItems.isNotEmpty() -> BookUriChoice(clipItems.first(), BookUriCarrier.CLIP_DATA)
    extraStream != null -> BookUriChoice(extraStream, BookUriCarrier.EXTRA_STREAM)
    else -> null
}

internal fun hasPersistableReadGrant(flags: Int): Boolean =
    flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
        flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0

internal fun Intent.toBookOpenRequest(defaultAction: String? = action): BookOpenRequest? {
    val clipUris = buildList {
        val clips = clipData
        if (clips != null) {
            for (i in 0 until clips.itemCount) {
                clips.getItemAt(i).uri?.let(::add)
            }
        }
    }
    val choice = chooseBookUri(data, clipUris, extraStreamUri()) ?: return null
    return BookOpenRequest(
        uri = choice.value,
        grantFlags = flags and URI_GRANT_FLAGS,
        sourceAction = action ?: defaultAction,
        carrier = choice.carrier,
    )
}

private fun Intent.extraStreamUri(): Uri? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            ?: getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.firstOrNull()
    } else {
        @Suppress("DEPRECATION")
        (getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull())
    }
} catch (_: RuntimeException) {
    // A malformed vendor Intent must not crash the reader. The caller will
    // either use data/ClipData or surface a visible "missing URI" error.
    null
}

/**
 * Standard SAF request with the broad MIME filter needed for providers that
 * report EPUB files as application/octet-stream. Persistable access is a
 * property of the RESULT grant; requesting it on the outbound Intent is both
 * redundant and problematic on some vendor DocumentsUI implementations.
 */
internal class OpenBookDocument : ActivityResultContract<Unit, BookPickerResult>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")

    override fun parseResult(resultCode: Int, intent: Intent?): BookPickerResult {
        if (resultCode != Activity.RESULT_OK) return BookPickerResult.Cancelled
        val request = intent?.toBookOpenRequest(Intent.ACTION_OPEN_DOCUMENT)
        return if (request == null) BookPickerResult.MissingUri else BookPickerResult.Selected(request)
    }
}

private const val URI_GRANT_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
