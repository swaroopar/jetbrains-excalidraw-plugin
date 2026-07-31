package com.swaroop.excalidraw.plugin.persistence

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [Scene] — the single type that owns parsing (both the strict
 * on-disk form and the lenient bridge/PNG forms), canonical serialization, and
 * content-equality for dirty-tracking. Consolidates what used to be
 * `ExcalidrawSceneTest`, `SceneChangeMessageTest`, `ExcalidrawSerializerTest`, and
 * `ExcalidrawSerializerRoundTripTest`.
 */
class SceneTest {

    private val gson = Gson()

    private fun elementsOf(vararg ids: String): JsonArray = JsonArray().apply {
        ids.forEach { add(JsonObject().apply { addProperty("id", it) }) }
    }

    // -------------------------------------------------------------------------
    // Fields / equality (was ExcalidrawSceneTest)
    // -------------------------------------------------------------------------

    @Test
    fun `fields are returned unchanged after construction`() {
        val elements = elementsOf("el1")
        val appState = JsonObject().apply { addProperty("viewBackgroundColor", "#ffffff") }
        val files = JsonObject()

        val scene = Scene(
            type = "excalidraw",
            version = 2,
            source = "https://excalidraw.com",
            elements = elements,
            appState = appState,
            files = files
        )

        assertEquals("excalidraw", scene.type)
        assertEquals(2, scene.version)
        assertEquals("https://excalidraw.com", scene.source)
        assertEquals(elements, scene.elements)
        assertEquals(appState, scene.appState)
        assertEquals(files, scene.files)
    }

    @Test
    fun `source and files are nullable`() {
        val scene = Scene(
            type = "excalidraw",
            version = 2,
            source = null,
            elements = JsonArray(),
            appState = JsonObject(),
            files = null
        )

        assertNull(scene.source)
        assertNull(scene.files)
        assertEquals(0, scene.elements.size())
    }

    @Test
    fun `data class equality holds for identical field values`() {
        val scene1 = Scene("excalidraw", 1, null, elementsOf("a"), JsonObject(), null)
        val scene2 = Scene("excalidraw", 1, null, elementsOf("a"), JsonObject(), null)

        assertEquals(scene1, scene2)
        assertEquals(scene1.hashCode(), scene2.hashCode())
    }

    @Test
    fun `copy produces independent instance with overridden fields`() {
        val original = Scene("excalidraw", 1, null, JsonArray(), JsonObject(), null)
        val copy = original.copy(version = 2, source = "https://example.com")

        assertEquals(1, original.version)
        assertNull(original.source)
        assertEquals(2, copy.version)
        assertEquals("https://example.com", copy.source)
    }

    @Test
    fun `empty returns a fresh blank scene`() {
        val scene = Scene.empty()

        assertEquals("excalidraw", scene.type)
        assertEquals(0, scene.elements.size())
        assertNotNull(scene.appState)
    }

    // -------------------------------------------------------------------------
    // parseFile — strict, throwing (was ExcalidrawPersistenceServiceTest's parse cases)
    // -------------------------------------------------------------------------

    private val validSceneJson = """
        {
          "type": "excalidraw",
          "version": 2,
          "source": "https://excalidraw.com",
          "elements": [{"id": "el1", "type": "rectangle"}],
          "appState": {"viewBackgroundColor": "#ffffff"},
          "files": {}
        }
    """.trimIndent()

    @Test
    fun `parseFile with valid JSON returns Scene with correct fields`() {
        val scene = Scene.parseFile(validSceneJson, "test.excalidraw")

        assertEquals("excalidraw", scene.type)
        assertEquals(2, scene.version)
        assertEquals("https://excalidraw.com", scene.source)
        assertEquals(1, scene.elements.size())
        assertEquals("el1", scene.elements[0].asJsonObject.get("id").asString)
        assertNotNull(scene.appState)
    }

    @Test
    fun `parseFile without elements field throws ExcalidrawParseException`() {
        val content = """{"type":"excalidraw","version":2,"appState":{}}"""
        assertThrows<ExcalidrawParseException> { Scene.parseFile(content, "no-elements.excalidraw") }
    }

    @Test
    fun `parseFile without appState field throws ExcalidrawParseException`() {
        val content = """{"type":"excalidraw","version":2,"elements":[]}"""
        assertThrows<ExcalidrawParseException> { Scene.parseFile(content, "no-appstate.excalidraw") }
    }

    @Test
    fun `parseFile with corrupt JSON throws ExcalidrawParseException`() {
        assertThrows<ExcalidrawParseException> { Scene.parseFile("{ not valid json", "corrupt.excalidraw") }
    }

    @Test
    fun `parseFile with empty content throws ExcalidrawParseException`() {
        assertThrows<ExcalidrawParseException> { Scene.parseFile("", "empty.excalidraw") }
    }

    @Test
    fun `parseFile with type other than excalidraw throws ExcalidrawParseException`() {
        val content = """{"type":"other","version":2,"elements":[],"appState":{}}"""
        assertThrows<ExcalidrawParseException> { Scene.parseFile(content, "wrong-type.excalidraw") }
    }

    // -------------------------------------------------------------------------
    // fromBridgeJson — lenient, nullable (was SceneChangeMessageTest)
    // -------------------------------------------------------------------------

    @Test
    fun `fromBridgeJson with valid payload returns a Scene with one element`() {
        val json = """{"elements":[{"type":"rectangle"}],"appState":{"viewBackgroundColor":"#ffffff"}}"""
        val result = Scene.fromBridgeJson(json)

        assertNotNull(result)
        assertEquals(1, result!!.elements.size())
        assertNotNull(result.appState)
    }

    @Test
    fun `fromBridgeJson with empty elements array returns a Scene with zero elements`() {
        val result = Scene.fromBridgeJson("""{"elements":[],"appState":{}}""")

        assertNotNull(result)
        assertEquals(0, result!!.elements.size())
    }

    @Test
    fun `fromBridgeJson with elements not an array returns null`() {
        assertNull(Scene.fromBridgeJson("""{"elements":"not-an-array"}"""))
    }

    @Test
    fun `fromBridgeJson with null appState defaults to an empty object`() {
        val result = Scene.fromBridgeJson("""{"elements":[],"appState":null}""")

        assertNotNull(result)
        assertEquals(0, result!!.appState.size())
    }

    @Test
    fun `fromBridgeJson with completely malformed JSON returns null`() {
        assertNull(Scene.fromBridgeJson("not-valid-json"))
    }

    // -------------------------------------------------------------------------
    // fromLenientJson — never-throwing, defaults everything (PNG extraction)
    // -------------------------------------------------------------------------

    @Test
    fun `fromLenientJson parses a full scene blob`() {
        val scene = Scene.fromLenientJson(validSceneJson)

        assertEquals("excalidraw", scene.type)
        assertEquals(1, scene.elements.size())
        assertEquals("https://excalidraw.com", scene.source)
    }

    @Test
    fun `fromLenientJson never throws and defaults on malformed input`() {
        val scene = Scene.fromLenientJson("not valid json at all")

        assertEquals("excalidraw", scene.type)
        assertEquals(0, scene.elements.size())
        assertNotNull(scene.appState)
    }

    // -------------------------------------------------------------------------
    // toCanonicalJson (was ExcalidrawSerializerTest / ExcalidrawSerializerRoundTripTest)
    // -------------------------------------------------------------------------

    @Test
    fun `toCanonicalJson produces exactly the 6 canonical fields`() {
        val scene = Scene.parseFile(validSceneJson, "x.excalidraw")
        val obj = gson.fromJson(scene.toCanonicalJson(), JsonObject::class.java)

        assertEquals(setOf("type", "version", "source", "elements", "appState", "files"), obj.keySet())
    }

    @Test
    fun `toCanonicalJson is idempotent`() {
        val scene = Scene.parseFile(validSceneJson, "x.excalidraw")
        val once = scene.toCanonicalJson()
        val twice = Scene.parseFile(once, "x.excalidraw").toCanonicalJson()

        assertEquals(once, twice)
    }

    @Test
    fun `toCanonicalJson emits explicit null for a null source and files`() {
        val scene = Scene("excalidraw", 2, null, JsonArray(), JsonObject(), null)
        val obj = gson.fromJson(scene.toCanonicalJson(), JsonObject::class.java)

        assertTrue(obj.get("source").isJsonNull)
        assertTrue(obj.get("files").isJsonNull)
    }

    @Test
    fun `toCanonicalJson produces stable canonical field order`() {
        val scene = Scene.parseFile(validSceneJson, "x.excalidraw")
        val result = scene.toCanonicalJson()

        val typePos = result.indexOf("\"type\"")
        val versionPos = result.indexOf("\"version\"")
        val sourcePos = result.indexOf("\"source\"")
        val elementsPos = result.indexOf("\"elements\"")
        val appStatePos = result.indexOf("\"appState\"")
        val filesPos = result.indexOf("\"files\"")

        assertTrue(typePos < versionPos)
        assertTrue(versionPos < sourcePos)
        assertTrue(sourcePos < elementsPos)
        assertTrue(elementsPos < appStatePos)
        assertTrue(appStatePos < filesPos)
    }

    @Test
    fun `toCanonicalJson round-trip preserves element and appState content`() {
        val scene = Scene.parseFile(validSceneJson, "x.excalidraw")
        val resultObj = gson.fromJson(scene.toCanonicalJson(), JsonObject::class.java)

        val resultEl = resultObj.getAsJsonArray("elements")[0].asJsonObject
        assertEquals("el1", resultEl.get("id").asString)
        assertEquals("rectangle", resultEl.get("type").asString)
        assertEquals(
            "#ffffff",
            resultObj.getAsJsonObject("appState").get("viewBackgroundColor").asString
        )
    }

    // -------------------------------------------------------------------------
    // contentKey — dirty-tracking equality (churn-field stripping)
    // -------------------------------------------------------------------------

    @Test
    fun `contentKey ignores version, versionNonce and updated churn`() {
        val base = Scene("excalidraw", 2, null, elementsOf("a"), JsonObject(), null)
        val churned = Scene(
            "excalidraw", 2, null,
            JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("id", "a")
                    addProperty("version", 7)
                    addProperty("versionNonce", 12345)
                    addProperty("updated", 999999L)
                })
            },
            JsonObject(),
            null
        )

        assertEquals(base.contentKey(), churned.contentKey())
    }

    @Test
    fun `contentKey differs when element ids differ`() {
        val a = Scene("excalidraw", 2, null, elementsOf("a"), JsonObject(), null)
        val b = Scene("excalidraw", 2, null, elementsOf("a", "b"), JsonObject(), null)

        assertFalse(a.contentKey() == b.contentKey())
    }

    @Test
    fun `contentKey does not mutate the original elements`() {
        val elements = JsonArray().apply {
            add(JsonObject().apply { addProperty("id", "a"); addProperty("version", 3) })
        }
        val scene = Scene("excalidraw", 2, null, elements, JsonObject(), null)

        scene.contentKey()

        assertTrue(elements[0].asJsonObject.has("version"), "contentKey must not mutate the source elements")
    }
}
