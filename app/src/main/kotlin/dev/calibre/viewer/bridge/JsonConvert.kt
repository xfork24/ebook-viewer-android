// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Any? → JsonElement conversion for host→JS calls (create_view ui_data,
// session data read from disk, etc.). Maps/Lists/strings/booleans/numbers
// convert recursively; JsonElement passes through untouched.

package dev.calibre.viewer.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value.toDouble())
    is Double -> JsonPrimitive(value)
    is Map<*, *> -> buildJsonObject {
        for ((k, v) in value) put(k.toString(), toJsonElement(v))
    }
    is Iterable<*> -> buildJsonArray { for (v in value) add(toJsonElement(v)) }
    is Array<*> -> buildJsonArray { for (v in value) add(toJsonElement(v)) }
    else -> JsonPrimitive(value.toString())
}
