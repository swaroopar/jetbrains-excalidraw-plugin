package com.swaroop.excalidraw.plugin.persistence

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExcalidrawSerializer].
 *
 * All tests run as plain JUnit 5 without any IDE runtime.
 *
 * AC-E3-02 coverage:
 * - TC-01: serialize() output contains exactly the 6 canonical fields.
 * - TC-02: serialize(serialize(x)) == serialize(x) (idempotency).
 * - TC-03: empty elements array is serialized without exception.
 * - TC-04: missing optional fields (source=null, files=null) receive null defaults.
 * - TC-05: canonical field order is stable (type, version, source, elements, appState, files).
 * - TC-06: extra unknown fields are stripped from output.
 */
class ExcalidrawSerializerTest {

    private val serializer = ExcalidrawSerializer()
    private val gson = Gson()

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private val fullSceneJson = """
        {
          "type": "excalidraw",
          "version": 2,
          "source": "https://excalidraw.com",
          "elements": [
            {"id": "el1", "type": "rectangle", "x": 10, "y": 20}
          ],
          "appState": {
            "viewBackgroundColor": "#ffffff",
            "zoom": 1.0
          },
          "files": {}
        }
    """.trimIndent()

    private val minimalSceneJson = """
        {
          "type": "excalidraw",
          "version": 2,
          "source": null,
          "elements": [],
          "appState": {},
          "files": null
        }
    """.trimIndent()

    private val extraFieldsSceneJson = """
        {
          "type": "excalidraw",
          "version": 2,
          "source": "https://excalidraw.com",
          "elements": [],
          "appState": {},
          "files": null,
          "extraUnknownField": "should be stripped",
          "anotherField": 42
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // TC-01: Output contains exactly the 6 canonical fields, no extras
    // ------------------------------------------------------------------

    @Test
    fun `serialize full scene JSON returns exactly the 6 canonical fields`() {
        val result = serializer.serialize(fullSceneJson)

        val obj: JsonObject = gson.fromJson(result, JsonObject::class.java)
        val keys: Set<String> = obj.keySet()

        assertEquals(
            setOf("type", "version", "source", "elements", "appState", "files"),
            keys,
            "Output must contain exactly the 6 canonical fields"
        )
    }

    // ------------------------------------------------------------------
    // TC-02: Idempotency — serialize(serialize(x)) == serialize(x)
    // ------------------------------------------------------------------

    @Test
    fun `serialize is idempotent — double serialize equals single serialize`() {
        val once = serializer.serialize(fullSceneJson)
        val twice = serializer.serialize(once)

        assertEquals(once, twice, "serialize(serialize(x)) must equal serialize(x)")
    }

    // ------------------------------------------------------------------
    // TC-03: Empty elements array does not throw, serialized as empty array
    // ------------------------------------------------------------------

    @Test
    fun `serialize with empty elements array completes without exception`() {
        val result = serializer.serialize(minimalSceneJson)

        assertNotNull(result)
        val obj: JsonObject = gson.fromJson(result, JsonObject::class.java)
        assertTrue(obj.has("elements"), "Output must have 'elements' field")
        val elementsEl = obj.get("elements")
        assertTrue(elementsEl.isJsonArray, "'elements' must be a JSON array")
        assertEquals(0, elementsEl.asJsonArray.size(), "'elements' must be empty")
    }

    // ------------------------------------------------------------------
    // TC-04: Missing optional fields receive null values in output (no crash)
    // ------------------------------------------------------------------

    @Test
    fun `serialize with null source and files produces null values in output`() {
        val result = serializer.serialize(minimalSceneJson)

        val obj: JsonObject = gson.fromJson(result, JsonObject::class.java)
        assertTrue(obj.has("source"), "Output must have 'source' field")
        assertTrue(obj.get("source").isJsonNull, "'source' must be null when not provided")
        assertTrue(obj.has("files"), "Output must have 'files' field")
        assertTrue(obj.get("files").isJsonNull, "'files' must be null when not provided")
    }

    // ------------------------------------------------------------------
    // TC-05: Canonical field order is stable
    // ------------------------------------------------------------------

    @Test
    fun `serialize produces stable canonical field order`() {
        val result = serializer.serialize(fullSceneJson)

        // Verify field order by relative string positions.
        val typePos = result.indexOf("\"type\"")
        val versionPos = result.indexOf("\"version\"")
        val sourcePos = result.indexOf("\"source\"")
        val elementsPos = result.indexOf("\"elements\"")
        val appStatePos = result.indexOf("\"appState\"")
        val filesPos = result.indexOf("\"files\"")

        assertTrue(typePos in 0..Int.MAX_VALUE, "type field must be present")
        assertTrue(typePos < versionPos, "type must appear before version")
        assertTrue(versionPos < sourcePos, "version must appear before source")
        assertTrue(sourcePos < elementsPos, "source must appear before elements")
        assertTrue(elementsPos < appStatePos, "elements must appear before appState")
        assertTrue(appStatePos < filesPos, "appState must appear before files")
    }

    // ------------------------------------------------------------------
    // TC-06: Extra unknown fields are stripped from output
    // ------------------------------------------------------------------

    @Test
    fun `serialize strips extra unknown fields from output`() {
        val result = serializer.serialize(extraFieldsSceneJson)

        val obj: JsonObject = gson.fromJson(result, JsonObject::class.java)
        val keys: Set<String> = obj.keySet()

        assertEquals(
            setOf("type", "version", "source", "elements", "appState", "files"),
            keys,
            "Extra fields must be stripped from output"
        )
    }
}
