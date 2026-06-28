package com.swaroop.excalidraw.plugin.export

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Gson round-trip tests for ExportRequest and ExportResult.
 *
 * No @Serializable annotation, no kotlinx import — pure Gson.
 */
class ExportMessageTest {

    private val gson = Gson()

    @Test
    fun `ExportRequest toJson contains format and scale fields`() {
        val request = ExportMessage.ExportRequest(format = "svg", scale = 1.0)
        val json = gson.toJson(request)
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("svg", obj.get("format").asString)
        assertEquals(1.0, obj.get("scale").asDouble, 1e-9)
    }

    @Test
    fun `ExportResult fromJson with valid JSON returns correct instance`() {
        val json = """{"format":"svg","data":"<svg/>"}"""
        val result = ExportMessage.ExportResult.fromJson(json)
        assertNotNull(result)
        assertEquals("svg", result!!.format)
        assertEquals("<svg/>", result.data)
    }

    @Test
    fun `ExportResult fromJson with broken JSON returns null`() {
        val result = ExportMessage.ExportResult.fromJson("{not valid json{{")
        assertNull(result)
    }

    @Test
    fun `ExportRequest toJson with png format and scale 2 dot 0`() {
        val request = ExportMessage.ExportRequest(format = "png", scale = 2.0)
        val json = gson.toJson(request)
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("png", obj.get("format").asString)
        assertEquals(2.0, obj.get("scale").asDouble, 1e-9)
    }

    @Test
    fun `ExportResult fromJson with png base64 payload returns correct instance`() {
        val base64Data = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val json = """{"format":"png","data":"$base64Data"}"""
        val result = ExportMessage.ExportResult.fromJson(json)
        assertNotNull(result)
        assertEquals("png", result!!.format)
        assertTrue(result.data.isNotEmpty())
    }

    @Test
    fun `ExportResult fromJson with null input returns null`() {
        val result = ExportMessage.ExportResult.fromJson("null")
        assertNull(result)
    }
}
