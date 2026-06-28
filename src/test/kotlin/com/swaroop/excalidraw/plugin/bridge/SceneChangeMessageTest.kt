package com.swaroop.excalidraw.plugin.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SceneChangeMessageTest {

    @Test
    fun `fromJson with valid payload returns non-null instance with one element`() {
        val json = """{"elements":[{"type":"rectangle"}],"appState":{"viewBackgroundColor":"#ffffff"}}"""
        val result = SceneChangeMessage.fromJson(json)
        assertNotNull(result, "Expected non-null SceneChangeMessage for valid JSON")
        assertEquals(1, result!!.elements.size(), "Expected exactly one element")
        assertNotNull(result.appState, "Expected non-null appState")
    }

    @Test
    fun `fromJson with empty elements array returns instance with size zero`() {
        val json = """{"elements":[],"appState":{}}"""
        val result = SceneChangeMessage.fromJson(json)
        assertNotNull(result, "Expected non-null SceneChangeMessage for empty elements")
        assertEquals(0, result!!.elements.size(), "Expected zero elements")
    }

    @Test
    fun `fromJson with invalid JSON where elements is not an array returns null`() {
        val json = """{"elements":"not-an-array"}"""
        val result = SceneChangeMessage.fromJson(json)
        assertNull(result, "Expected null for malformed elements field")
    }

    @Test
    fun `fromJson with null appState field yields non-null instance with null appState`() {
        val json = """{"elements":[],"appState":null}"""
        val result = SceneChangeMessage.fromJson(json)
        assertNotNull(result, "Expected non-null SceneChangeMessage even when appState is null in JSON")
    }

    @Test
    fun `fromJson with completely malformed JSON returns null`() {
        val json = """not-valid-json"""
        val result = SceneChangeMessage.fromJson(json)
        assertNull(result, "Expected null for completely invalid JSON")
    }
}
