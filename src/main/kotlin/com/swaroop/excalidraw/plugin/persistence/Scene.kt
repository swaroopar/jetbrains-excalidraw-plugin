package com.swaroop.excalidraw.plugin.persistence

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * A single Excalidraw scene: everything a `.excalidraw` file or a scene-embedded
 * `.excalidraw.png` carries, and everything the bridge exchanges with the JS side.
 *
 * The one type every boundary passes — file persistence, the JCEF bridge, and
 * autosave dirty-tracking all read and write [Scene] objects, never a raw JSON
 * `String` or a bespoke per-boundary shape. `elements`/`appState`/`files` are native
 * Gson types (not a reflected `Map<String, Any>`) since that is what both the file
 * JSON and the bridge's JS payloads already are — no extra conversion layer needed.
 */
data class Scene(
    val type: String,
    val version: Int,
    val source: String?,
    val elements: JsonArray,
    val appState: JsonObject,
    val files: JsonObject?
) {
    /**
     * Canonical on-disk JSON: exactly six top-level fields in a fixed order
     * (`type`, `version`, `source`, `elements`, `appState`, `files`), with `null`
     * emitted explicitly for a null [source]/[files]. Idempotent — parsing this
     * output back via [parseFile] and calling [toCanonicalJson] again yields the
     * same string, since a [Scene] is always fully populated at construction time.
     */
    fun toCanonicalJson(): String {
        val canonical = JsonObject()
        canonical.addProperty("type", type)
        canonical.addProperty("version", version)
        canonical.add("source", source?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        canonical.add("elements", elements)
        canonical.add("appState", appState)
        canonical.add("files", files ?: JsonNull.INSTANCE)
        return CANONICAL_GSON.toJson(canonical)
    }

    /**
     * Stable content-equality key for dirty-tracking: [elements] with the per-element
     * fields that churn without a meaningful content change (`version`, `versionNonce`,
     * `updated`) removed. Operates on a deep copy — [elements] itself is never mutated.
     */
    fun contentKey(): String {
        val copy = elements.deepCopy()
        for (element in copy) {
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                obj.remove("version")
                obj.remove("versionNonce")
                obj.remove("updated")
            }
        }
        return copy.toString()
    }

    companion object {
        private val gson = Gson()
        private val CANONICAL_GSON: Gson = GsonBuilder().serializeNulls().create()

        private fun fail(filePath: String, message: String): Nothing =
            throw ExcalidrawParseException(filePath, IllegalArgumentException(message))

        /** A fresh, empty scene — used for a new/blank `.excalidraw` file. */
        fun empty(): Scene = Scene(
            type = "excalidraw",
            version = 2,
            source = "https://excalidraw.com",
            elements = JsonArray(),
            appState = JsonObject(),
            files = JsonObject()
        )

        /**
         * Strict parse of on-disk file [content]. Validates mandatory fields
         * (`elements`, `appState`) and rejects empty/malformed/wrong-`type` input.
         *
         * @throws ExcalidrawParseException on any parse or validation failure.
         */
        fun parseFile(content: String, filePath: String): Scene {
            if (content.isBlank()) {
                fail(filePath, "File content is empty")
            }

            val root: JsonObject = try {
                JsonParser.parseString(content)
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?: throw JsonParseException("Top-level JSON element is not an object")
            } catch (ex: JsonSyntaxException) {
                throw ExcalidrawParseException(filePath, ex)
            } catch (ex: JsonParseException) {
                throw ExcalidrawParseException(filePath, ex)
            }

            val elementsEl = root.get("elements") ?: fail(filePath, "Missing mandatory field: elements")
            if (!elementsEl.isJsonArray) {
                fail(filePath, "Field 'elements' must be a JSON array")
            }

            val appStateEl = root.get("appState") ?: fail(filePath, "Missing mandatory field: appState")
            if (!appStateEl.isJsonObject) {
                fail(filePath, "Field 'appState' must be a JSON object")
            }

            val type = root.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            if (type != "excalidraw") {
                fail(filePath, "Field 'type' must be \"excalidraw\", got: \"$type\"")
            }

            val version = root.get("version")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt ?: 0
            val source = root.get("source")?.takeIf { it.isJsonPrimitive }?.asString
            val files = root.get("files")?.takeIf { it.isJsonObject }?.asJsonObject

            return Scene(
                type = type,
                version = version,
                source = source,
                elements = elementsEl.asJsonArray,
                appState = appStateEl.asJsonObject,
                files = files
            )
        }

        /**
         * Lenient parse of a JS "sceneChange" bridge payload
         * (`{"elements":[...],"appState":{...},"files":{...}}`), possibly with other fields the
         * caller doesn't care about — e.g. a `type` discriminator consumed elsewhere. Returns
         * `null` when the JSON is malformed, `elements` is missing or not an array, or `appState`
         * is present but neither `null` nor an object. A `null` or missing `appState` defaults to
         * an empty object (unlike the strict [parseFile], nothing else about a scene-change event
         * implies a real, non-empty app state). `files` is parsed the same way as
         * [fromLenientJson] — present and a JSON object, or `null` when absent/invalid, so old
         * bridge messages without a `files` field (e.g. cached from before this field existed)
         * still parse without error.
         */
        fun fromBridgeJson(json: String): Scene? {
            return try {
                val obj = gson.fromJson(json, JsonObject::class.java) ?: return null
                val elementsEl = obj.get("elements") ?: return null
                if (!elementsEl.isJsonArray) return null
                val appStateEl = obj.get("appState")
                val appState: JsonObject = when {
                    appStateEl == null || appStateEl.isJsonNull -> JsonObject()
                    appStateEl.isJsonObject -> appStateEl.asJsonObject
                    else -> return null
                }
                val files = obj.get("files")?.takeIf { it.isJsonObject }?.asJsonObject
                Scene(
                    type = "excalidraw",
                    version = 2,
                    source = null,
                    elements = elementsEl.asJsonArray,
                    appState = appState,
                    files = files
                )
            } catch (_: JsonSyntaxException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        }

        /**
         * Lenient, never-throwing parse of a full scene JSON blob (e.g. extracted from a
         * `.excalidraw.png`) — defaults any missing/invalid field rather than failing. Used
         * where the source is trusted to already be a real scene (the JS side's own export),
         * so the goal is best-effort reconstruction, not validation.
         */
        fun fromLenientJson(json: String): Scene {
            val root = try {
                JsonParser.parseString(json)?.takeIf { it.isJsonObject }?.asJsonObject
            } catch (_: Exception) {
                null
            } ?: JsonObject()

            val elements = root.get("elements")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
            val appState = root.get("appState")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            val type = root.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: "excalidraw"
            val version = root.get("version")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt ?: 2
            val source = root.get("source")?.takeIf { it.isJsonPrimitive }?.asString
            val files = root.get("files")?.takeIf { it.isJsonObject }?.asJsonObject

            return Scene(type, version, source, elements, appState, files)
        }
    }
}
