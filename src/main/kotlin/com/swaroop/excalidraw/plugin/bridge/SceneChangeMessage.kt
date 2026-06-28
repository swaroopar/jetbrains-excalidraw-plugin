package com.swaroop.excalidraw.plugin.bridge

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException

/**
 * Typed representation of a scene-change event posted from the Excalidraw web app to Kotlin.
 *
 * The JavaScript side serialises the scene change as:
 * ```json
 * { "type": "sceneChange", "elements": [...], "appState": {...} }
 * ```
 * This class captures only [elements] and [appState]; the `type` discriminator
 * is consumed at the [BridgeMessage] layer.
 *
 * No IDE or JCEF dependency — pure Gson data holder, safe for unit testing.
 */
data class SceneChangeMessage(
    val elements: JsonArray,
    val appState: JsonObject?
) {
    companion object {
        private val gson = Gson()

        /**
         * Deserialises a JSON string into a [SceneChangeMessage].
         *
         * Returns null when:
         * - the JSON is syntactically invalid,
         * - `elements` is missing or is not a JSON array.
         *
         * A null `appState` field in the JSON is allowed and yields a
         * [SceneChangeMessage] with [appState] == null.
         */
        fun fromJson(json: String): SceneChangeMessage? {
            return try {
                val obj = gson.fromJson(json, JsonObject::class.java)
                    ?: return null
                val elementsElement = obj.get("elements")
                    ?: return null
                if (!elementsElement.isJsonArray) return null
                val elements = elementsElement.asJsonArray
                val appStateElement = obj.get("appState")
                val appState: JsonObject? = when {
                    appStateElement == null || appStateElement.isJsonNull -> null
                    appStateElement.isJsonObject -> appStateElement.asJsonObject
                    else -> return null
                }
                SceneChangeMessage(elements = elements, appState = appState)
            } catch (_: JsonSyntaxException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        }
    }
}
