package com.swaroop.excalidraw.plugin.bridge

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.swaroop.excalidraw.plugin.export.ExportMessage
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawScene

/**
 * Typed message hierarchy for the Kotlin-to-JS (and JS-to-Kotlin) bridge channel.
 *
 * All message types are declared here so the compiler enforces exhaustive handling.
 * No JCEF dependency — serialisation only.
 */
sealed class BridgeMessage {

    /**
     * Kotlin→JS: instructs the Excalidraw web app to load and render [scene].
     *
     * [toJson] produces a JSON object with two top-level fields:
     * - `type`: always `"loadScene"`
     * - `scene`: the serialised [ExcalidrawScene]
     */
    data class LoadScene(val scene: ExcalidrawScene) : BridgeMessage() {
        fun toJson(): String {
            val payload = mapOf(
                "type" to "loadScene",
                "scene" to scene
            )
            return gson.toJson(payload)
        }
    }

    /**
     * JS→Kotlin: signal from the Excalidraw web app indicating it is ready
     * to receive commands.
     */
    data object Ready : BridgeMessage()

    /**
     * JS→Kotlin: scene-change event posted by the Excalidraw web app whenever
     * the user edits the drawing (draw, move, delete, undo, redo).
     *
     * The [payload] holds the full scene state (elements + appState) as parsed
     * by [SceneChangeMessage.fromJson].
     */
    data class SceneChange(val payload: SceneChangeMessage) : BridgeMessage()

    /**
     * JS→Kotlin: export result posted by the Excalidraw web app after an export
     * operation is triggered via [requestExport].
     *
     * The [payload] holds the export format and data as parsed by
     * [ExportMessage.ExportResult.fromJson].
     */
    data class ExportResult(val payload: ExportMessage.ExportResult) : BridgeMessage()

    /**
     * JS→Kotlin: response to a PNG scene extraction request.
     *
     * On success, [sceneJson] contains the extracted Excalidraw scene JSON and [error] is null.
     * On failure, [error] contains a human-readable error message and [sceneJson] is null.
     * Both fields may be null if the JS side sends neither (treated as empty extraction).
     */
    data class PngExtracted(val sceneJson: String?, val error: String?) : BridgeMessage()

    /**
     * JS→Kotlin: response to a PNG re-embed export request.
     *
     * On success, [base64Png] contains the Base64-encoded PNG data and [error] is null.
     * On failure, [error] contains a human-readable error message and [base64Png] is null.
     */
    data class PngExported(val base64Png: String?, val error: String?) : BridgeMessage()

    /**
     * JS→Kotlin: the editor's library changed (item added, removed or reordered).
     * [libraryItemsJson] is the full current library items array serialised as JSON —
     * persisted by the Kotlin side so libraries survive IDE restarts (the opaque
     * excalidraw:// origin disables Excalidraw's own IndexedDB persistence).
     */
    data class LibraryChange(val libraryItemsJson: String) : BridgeMessage()

    companion object {
        private val gson: Gson = Gson()

        /**
         * Deserialises a raw JSON string received from the JS bridge into a
         * typed [BridgeMessage].
         *
         * Dispatches on the `type` field:
         * - `"sceneChange"` → [SceneChange] (JS→Kotlin direction)
         * - `"exportResult"` → [ExportResult] (JS→Kotlin direction)
         * - `"pngExtracted"` → [PngExtracted] (JS→Kotlin direction)
         * - `"pngExported"` → [PngExported] (JS→Kotlin direction)
         * - any other value → `null` (unknown message, no crash)
         *
         * Returns `null` on malformed JSON or missing/unknown `type`.
         */
        fun fromJson(json: String): BridgeMessage? {
            return try {
                val obj = gson.fromJson(json, JsonObject::class.java) ?: return null
                val typeElement = obj.get("type") ?: return null
                if (!typeElement.isJsonPrimitive) return null
                when (typeElement.asString) {
                    "sceneChange" -> {
                        val sceneChangeMessage = SceneChangeMessage.fromJson(json) ?: return null
                        SceneChange(sceneChangeMessage)
                    }
                    "exportResult" -> {
                        val exportResult = ExportMessage.ExportResult.fromJson(json) ?: return null
                        ExportResult(exportResult)
                    }
                    "pngExtracted" -> PngExtracted(
                        sceneJson = obj.get("sceneJson")?.takeIf { !it.isJsonNull }?.asString,
                        error     = obj.get("error")?.takeIf { !it.isJsonNull }?.asString
                    )
                    "pngExported" -> PngExported(
                        base64Png = obj.get("base64Png")?.takeIf { !it.isJsonNull }?.asString,
                        error     = obj.get("error")?.takeIf { !it.isJsonNull }?.asString
                    )
                    "libraryChange" -> {
                        val items = obj.get("libraryItems")
                        if (items == null || !items.isJsonArray) return null
                        LibraryChange(libraryItemsJson = items.asJsonArray.toString())
                    }
                    else -> null
                }
            } catch (_: JsonSyntaxException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        }
    }
}
