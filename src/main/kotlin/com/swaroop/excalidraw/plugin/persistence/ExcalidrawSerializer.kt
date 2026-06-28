package com.swaroop.excalidraw.plugin.persistence

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Canonical JSON serializer for `.excalidraw` scene files.
 *
 * Normalizes a raw scene JSON string into the canonical `.excalidraw` schema,
 * producing output with exactly the six top-level fields in a defined order:
 * `type`, `version`, `source`, `elements`, `appState`, `files`.
 *
 * **Design constraints:**
 * - No IDE API or JCEF dependency — can be used in pure JUnit tests.
 * - Uses Gson from the IntelliJ Platform classpath; no new Gradle dependency.
 * - Idempotent: `serialize(serialize(x)) == serialize(x)` for any valid input.
 * - Extra top-level fields are stripped from the output (schema enforcement, A03).
 * - Missing optional fields (`source`, `files`) appear as JSON null in output.
 * - Missing required fields (`elements`, `appState`) default to empty array / object.
 *
 * Secure-coding notes (OWASP A03 / A08):
 * - Gson is the established, vetted JSON parser — no eval() or dynamic code execution.
 * - All JSON parsing uses Gson's typed API; no string concatenation to build JSON.
 * - Input is parsed into a typed object model before output is produced.
 */
class ExcalidrawSerializer {

    /**
     * Gson instance configured with:
     * - `serializeNulls()` so that null `source` and `files` are emitted explicitly.
     * - No special type adapters needed; field order is controlled by the canonical
     *   [JsonObject] construction order (LinkedHashMap-backed in Gson).
     */
    private val gson: Gson = GsonBuilder()
        .serializeNulls()
        .create()

    /**
     * Normalizes [sceneJson] into the canonical `.excalidraw` JSON string.
     *
     * The output contains exactly the six top-level fields in defined order:
     * `type`, `version`, `source`, `elements`, `appState`, `files`.
     *
     * @param sceneJson raw JSON string representing an Excalidraw scene (e.g. from
     *   the JS bridge's `onSceneChanged` callback or from a `.excalidraw` file).
     * @return canonical `.excalidraw` JSON string, always valid JSON.
     * @throws com.google.gson.JsonParseException if [sceneJson] is not valid JSON.
     */
    fun serialize(sceneJson: String): String {
        val root: JsonObject = JsonParser.parseString(sceneJson).asJsonObject

        // Extract each canonical field, using safe defaults for missing values.
        val type: JsonElement = root.get("type") ?: JsonNull.INSTANCE
        val version: JsonElement = root.get("version") ?: JsonNull.INSTANCE
        val source: JsonElement = root.get("source") ?: JsonNull.INSTANCE
        val elements: JsonElement = root.get("elements") ?: JsonArray()
        val appState: JsonElement = root.get("appState") ?: JsonObject()
        val files: JsonElement = root.get("files") ?: JsonNull.INSTANCE

        // Build the canonical output object with fields in the defined order.
        // JsonObject in Gson is backed by a LinkedHashMap, so insertion order is preserved.
        val canonical = JsonObject()
        canonical.add("type", type)
        canonical.add("version", version)
        canonical.add("source", source)
        canonical.add("elements", elements)
        canonical.add("appState", appState)
        canonical.add("files", files)

        return gson.toJson(canonical)
    }

    /**
     * Convenience overload: normalizes an [ExcalidrawScene] object by first
     * serializing it with Gson and then applying canonical normalization.
     *
     * This ensures the same round-trip guarantee as [serialize(String)].
     *
     * @param scene the [ExcalidrawScene] to serialize.
     * @return canonical `.excalidraw` JSON string.
     */
    fun serialize(scene: ExcalidrawScene): String {
        val rawJson = gson.toJson(scene)
        return serialize(rawJson)
    }
}
