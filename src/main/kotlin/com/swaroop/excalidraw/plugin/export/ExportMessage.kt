package com.swaroop.excalidraw.plugin.export

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException

/**
 * Typed message data classes for the Kotlin↔JS export channel.
 *
 * No JCEF dependency — pure data holders with Gson serialisation.
 * No @Serializable annotation — Gson needs none.
 */
object ExportMessage {

    private val GSON: Gson = Gson()

    /**
     * Kotlin→JS: requests an export with the given [format] ("svg" or "png")
     * and [scale] factor. Serialised to JSON via Gson before being passed to
     * [evaluateJavaScript].
     */
    data class ExportRequest(val format: String, val scale: Double)

    /**
     * JS→Kotlin: carries the export result produced by the web app.
     *
     * [format] matches the request format ("svg" or "png").
     * [data] is the raw SVG string (for SVG) or a Base64-encoded PNG (for PNG).
     */
    data class ExportResult(val format: String, val data: String) {

        companion object {

            /**
             * Deserialises a raw JSON string from the JS bridge into an
             * [ExportResult].
             *
             * Returns `null` on malformed JSON or missing required fields.
             */
            fun fromJson(json: String): ExportResult? {
                return try {
                    val obj = GSON.fromJson(json, JsonObject::class.java) ?: return null
                    val formatEl = obj.get("format") ?: return null
                    val dataEl = obj.get("data") ?: return null
                    if (!formatEl.isJsonPrimitive || !dataEl.isJsonPrimitive) return null
                    ExportResult(
                        format = formatEl.asString,
                        data = dataEl.asString
                    )
                } catch (_: JsonSyntaxException) {
                    null
                } catch (_: IllegalStateException) {
                    null
                }
            }
        }
    }
}
