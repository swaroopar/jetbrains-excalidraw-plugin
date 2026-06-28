package com.swaroop.excalidraw.plugin.bridge

import com.google.gson.JsonParser
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawScene
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BridgeMessageTest {

    private fun fixtureScene(): ExcalidrawScene = ExcalidrawScene(
        type = "excalidraw",
        version = 2,
        source = "https://excalidraw.com",
        elements = listOf(mapOf("id" to "elem-1", "type" to "rectangle")),
        appState = mapOf("viewBackgroundColor" to "#ffffff"),
        files = null
    )

    @Test
    fun `LoadScene toJson contains type field with value loadScene`() {
        val msg = BridgeMessage.LoadScene(fixtureScene())
        val json = msg.toJson()
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("loadScene", obj.get("type").asString)
    }

    @Test
    fun `LoadScene toJson contains scene object with elements and appState`() {
        val msg = BridgeMessage.LoadScene(fixtureScene())
        val json = msg.toJson()
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("scene"), "JSON must contain 'scene' field")
        val scene = obj.getAsJsonObject("scene")
        assertTrue(scene.has("elements"), "scene must contain 'elements'")
        assertTrue(scene.has("appState"), "scene must contain 'appState'")
    }

    @Test
    fun `LoadScene toJson scene preserves element data`() {
        val msg = BridgeMessage.LoadScene(fixtureScene())
        val json = msg.toJson()
        val obj = JsonParser.parseString(json).asJsonObject
        val elements = obj.getAsJsonObject("scene").getAsJsonArray("elements")
        assertEquals(1, elements.size())
        assertEquals("elem-1", elements[0].asJsonObject.get("id").asString)
    }

    @Test
    fun `Ready is a valid BridgeMessage subtype`() {
        val msg: BridgeMessage = BridgeMessage.Ready
        assertTrue(msg is BridgeMessage.Ready)
    }

    // --- task-03-002: fromJson dispatcher tests ---

    @Test
    fun `fromJson with sceneChange type returns SceneChange instance`() {
        val json = """{"type":"sceneChange","elements":[],"appState":{}}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result is BridgeMessage.SceneChange, "Expected SceneChange but got $result")
    }

    @Test
    fun `fromJson with sceneChange type carries correct SceneChangeMessage payload`() {
        val json = """{"type":"sceneChange","elements":[{"type":"rectangle"}],"appState":{"viewBackgroundColor":"#ffffff"}}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result is BridgeMessage.SceneChange)
        val sceneChange = result as BridgeMessage.SceneChange
        assertEquals(1, sceneChange.payload.elements.size())
        assertTrue(sceneChange.payload.appState != null)
    }

    @Test
    fun `fromJson with unknown type returns null`() {
        val json = """{"type":"unknownType","data":"someValue"}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result == null, "Expected null for unknown type but got $result")
    }

    @Test
    fun `fromJson with empty elements array returns SceneChange with empty elements`() {
        val json = """{"type":"sceneChange","elements":[],"appState":{}}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result is BridgeMessage.SceneChange)
        val sceneChange = result as BridgeMessage.SceneChange
        assertEquals(0, sceneChange.payload.elements.size())
    }

    // --- task-06-003: ExportResult arm tests ---

    @Test
    fun `fromJson with exportResult type and svg format returns ExportResult instance`() {
        val json = """{"type":"exportResult","format":"svg","data":"<svg/>"}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result is BridgeMessage.ExportResult, "Expected ExportResult but got $result")
    }

    @Test
    fun `fromJson with exportResult type carries correct format and data in payload`() {
        val json = """{"type":"exportResult","format":"svg","data":"<svg/>"}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result is BridgeMessage.ExportResult)
        val exportResult = result as BridgeMessage.ExportResult
        assertEquals("svg", exportResult.payload.format)
        assertEquals("<svg/>", exportResult.payload.data)
    }

    @Test
    fun `fromJson with exportResult type and png format carries correct payload`() {
        val json = """{"type":"exportResult","format":"png","data":"iVBORw0KGgo="}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result is BridgeMessage.ExportResult)
        val exportResult = result as BridgeMessage.ExportResult
        assertEquals("png", exportResult.payload.format)
        assertEquals("iVBORw0KGgo=", exportResult.payload.data)
    }

    @Test
    fun `fromJson with exportResult type and malformed JSON returns null`() {
        val json = """{"type":"exportResult","format":}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result == null, "Expected null for malformed exportResult JSON but got $result")
    }

    @Test
    fun `fromJson with exportResult type but missing data field returns null`() {
        val json = """{"type":"exportResult","format":"svg"}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result == null, "Expected null when data field is missing but got $result")
    }

    @Test
    fun `fromJson with unknown type still returns null after ExportResult arm added`() {
        val json = """{"type":"somethingElse","foo":"bar"}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result == null, "Expected null for unknown type but got $result")
    }

    // --- task-07-001: PngExtracted arm tests (AC-TC-PngExt-01..03) ---

    @Test
    fun `fromJson pngExtracted with sceneJson returns PngExtracted with sceneJson not null and error null`() {
        val json = """{"type":"pngExtracted","sceneJson":"{\"type\":\"excalidraw\"}"}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result is BridgeMessage.PngExtracted, "Expected PngExtracted but got $result")
        val msg = result as BridgeMessage.PngExtracted
        assertTrue(msg.sceneJson != null, "sceneJson must not be null")
        assertTrue(msg.error == null, "error must be null when sceneJson is present")
    }

    @Test
    fun `fromJson pngExtracted with error returns PngExtracted with error not null and sceneJson null`() {
        val json = """{"type":"pngExtracted","error":"no scene"}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result is BridgeMessage.PngExtracted, "Expected PngExtracted but got $result")
        val msg = result as BridgeMessage.PngExtracted
        assertTrue(msg.error != null, "error must not be null")
        assertTrue(msg.sceneJson == null, "sceneJson must be null when error is present")
    }

    @Test
    fun `fromJson pngExtracted with neither field returns PngExtracted with both null`() {
        val json = """{"type":"pngExtracted"}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result is BridgeMessage.PngExtracted, "Expected PngExtracted but got $result")
        val msg = result as BridgeMessage.PngExtracted
        assertTrue(msg.sceneJson == null, "sceneJson must be null")
        assertTrue(msg.error == null, "error must be null")
    }

    // --- task-07-001: PngExported arm tests (AC-TC-PngExp-01..02) ---

    @Test
    fun `fromJson pngExported with base64Png returns PngExported with base64Png not null and error null`() {
        val json = """{"type":"pngExported","base64Png":"AAAA"}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result is BridgeMessage.PngExported, "Expected PngExported but got $result")
        val msg = result as BridgeMessage.PngExported
        assertTrue(msg.base64Png != null, "base64Png must not be null")
        assertTrue(msg.error == null, "error must be null when base64Png is present")
    }

    @Test
    fun `fromJson pngExported with error returns PngExported with error not null and base64Png null`() {
        val json = """{"type":"pngExported","error":"export failed"}"""
        val result = BridgeMessage.fromJson(json)
        assertTrue(result is BridgeMessage.PngExported, "Expected PngExported but got $result")
        val msg = result as BridgeMessage.PngExported
        assertTrue(msg.error != null, "error must not be null")
        assertTrue(msg.base64Png == null, "base64Png must be null when error is present")
    }
}
