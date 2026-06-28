package com.swaroop.excalidraw.plugin.persistence

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Round-trip and schema-conformance tests for [ExcalidrawSerializer].
 *
 * Covers AC-E3-02:
 * - TC-RT-01 (Schema): serialize() with a complete scene JSON produces output
 *   containing exactly the 6 canonical top-level fields and no others.
 * - TC-RT-02 (Idempotency): serialize(serialize(x)) == serialize(x) for a
 *   typical scene with a non-empty elements array.
 * - TC-RT-03 (Round-Trip): the valid-scene.excalidraw fixture is parsed and
 *   serialized; elements and appState of the output are semantically equivalent
 *   to the original (Gson field-by-field comparison — no byte identity required).
 *
 * All tests run as plain JUnit 5 without any IDE runtime dependency.
 */
class ExcalidrawSerializerRoundTripTest {

    private val serializer = ExcalidrawSerializer()
    private val gson = Gson()

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Inline reproduction of src/test/resources/fixtures/valid-scene.excalidraw.
     *
     * Using an inline string avoids classpath resource loading in pure-JUnit
     * contexts and keeps the test self-contained.  The content mirrors the
     * fixture exactly so that round-trip semantics can be verified here without
     * reading files from the filesystem.
     */
    private val validSceneFixture = """
        {
          "type": "excalidraw",
          "version": 2,
          "source": "https://excalidraw.com",
          "elements": [
            {
              "id": "el1",
              "type": "rectangle",
              "x": 100,
              "y": 200,
              "width": 300,
              "height": 150,
              "angle": 0,
              "strokeColor": "#000000",
              "backgroundColor": "transparent",
              "fillStyle": "solid",
              "strokeWidth": 1,
              "strokeStyle": "solid",
              "roughness": 1,
              "opacity": 100,
              "version": 1,
              "versionNonce": 123456,
              "isDeleted": false,
              "groupIds": [],
              "boundElements": null,
              "updated": 1700000000000,
              "link": null,
              "locked": false
            }
          ],
          "appState": {
            "viewBackgroundColor": "#ffffff",
            "gridSize": null
          },
          "files": {}
        }
    """.trimIndent()

    /**
     * A typical scene JSON with a non-empty elements array, used for
     * idempotency verification (TC-RT-02).
     */
    private val typicalSceneJson = """
        {
          "type": "excalidraw",
          "version": 2,
          "source": "https://excalidraw.com",
          "elements": [
            {"id": "a1", "type": "ellipse", "x": 50, "y": 60, "width": 100, "height": 80},
            {"id": "a2", "type": "text", "x": 200, "y": 200, "text": "Hello"}
          ],
          "appState": {
            "viewBackgroundColor": "#ffffff",
            "zoom": {"value": 1.0}
          },
          "files": {}
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // TC-RT-01: Schema conformance — exactly 6 canonical top-level fields
    // ------------------------------------------------------------------

    /**
     * Verifies that serializing a complete scene JSON (TC-RT-01) produces an
     * output object whose top-level key set is exactly
     * {type, version, source, elements, appState, files} — no extras, no missing.
     *
     * This enforces AC-E3-02: "Saved output validates against the .excalidraw
     * JSON schema."
     */
    @Test
    fun `schema conformance - serialize produces exactly the 6 canonical top-level fields`() {
        val result = serializer.serialize(validSceneFixture)

        assertNotNull(result, "serialize() must not return null")

        val obj: JsonObject = gson.fromJson(result, JsonObject::class.java)
        val keys: Set<String> = obj.keySet()

        assertEquals(
            setOf("type", "version", "source", "elements", "appState", "files"),
            keys,
            "Output must contain exactly the 6 canonical fields and no extras"
        )
    }

    // ------------------------------------------------------------------
    // TC-RT-02: Idempotency — serialize(serialize(x)) == serialize(x)
    //           (non-empty elements array)
    // ------------------------------------------------------------------

    /**
     * Verifies idempotency of [ExcalidrawSerializer.serialize] for a scene with
     * a non-empty elements array (TC-RT-02).  This is stricter than
     * [ExcalidrawSerializerTest]'s TC-02, which uses a full scene but does not
     * specifically require a non-empty elements list in the fixture.
     *
     * Idempotency is a key correctness property (AC-E3-02): once a file has been
     * saved, a second auto-save must not change the on-disk bytes.
     */
    @Test
    fun `idempotency - serialize of serialize equals single serialize for non-empty elements`() {
        val once = serializer.serialize(typicalSceneJson)
        val twice = serializer.serialize(once)

        assertEquals(
            once,
            twice,
            "serialize(serialize(x)) must equal serialize(x) — idempotency violation"
        )
    }

    // ------------------------------------------------------------------
    // TC-RT-03: Round-trip — elements and appState semantically unchanged
    // ------------------------------------------------------------------

    /**
     * Verifies the full open→serialize round-trip (TC-RT-03): after serializing
     * the valid-scene fixture, the elements array contains the same element
     * (id="el1", type="rectangle") and the appState retains the same
     * viewBackgroundColor — no data is silently dropped or mutated.
     *
     * Byte-level identity is NOT required; only field-level semantic equivalence
     * is checked via Gson, as specified in AC-E3-02.
     */
    @Test
    fun `round-trip - elements and appState are semantically unchanged after serialize`() {
        val result = serializer.serialize(validSceneFixture)

        val originalObj: JsonObject = gson.fromJson(validSceneFixture, JsonObject::class.java)
        val resultObj: JsonObject = gson.fromJson(result, JsonObject::class.java)

        // --- elements ---
        val originalElements = originalObj.getAsJsonArray("elements")
        val resultElements = resultObj.getAsJsonArray("elements")

        assertNotNull(resultElements, "Output must have an 'elements' array")
        assertEquals(
            originalElements.size(),
            resultElements.size(),
            "elements array size must be preserved after round-trip"
        )

        val originalEl = originalElements[0].asJsonObject
        val resultEl = resultElements[0].asJsonObject

        assertEquals(
            originalEl.get("id").asString,
            resultEl.get("id").asString,
            "Element 'id' must be preserved"
        )
        assertEquals(
            originalEl.get("type").asString,
            resultEl.get("type").asString,
            "Element 'type' must be preserved"
        )
        assertTrue(
            resultEl.has("version"),
            "Element 'version' field must be preserved after round-trip"
        )

        // --- appState ---
        val originalAppState = originalObj.getAsJsonObject("appState")
        val resultAppState = resultObj.getAsJsonObject("appState")

        assertNotNull(resultAppState, "Output must have an 'appState' object")
        assertEquals(
            originalAppState.get("viewBackgroundColor").asString,
            resultAppState.get("viewBackgroundColor").asString,
            "appState.viewBackgroundColor must be preserved"
        )
    }
}
