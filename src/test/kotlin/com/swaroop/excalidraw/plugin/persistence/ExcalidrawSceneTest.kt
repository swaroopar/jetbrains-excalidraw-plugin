package com.swaroop.excalidraw.plugin.persistence

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExcalidrawSceneTest {

    @Test
    fun `fields are returned unchanged after construction`() {
        val elements: List<Map<String, Any>> = listOf(
            mapOf<String, Any>("id" to "el1", "type" to "rectangle", "x" to 10, "y" to 20)
        )
        val appState: Map<String, Any> = mapOf<String, Any>("viewBackgroundColor" to "#ffffff", "zoom" to 1.0)
        val files: Map<String, Any> = mapOf<String, Any>("file1" to mapOf<String, Any>("id" to "file1", "dataURL" to "data:image/png;base64,abc"))

        val scene = ExcalidrawScene(
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
    fun `source is nullable and defaults to null`() {
        val scene = ExcalidrawScene(
            type = "excalidraw",
            version = 2,
            source = null,
            elements = emptyList<Map<String, Any>>(),
            appState = emptyMap<String, Any>(),
            files = null
        )

        assertNull(scene.source)
        assertNull(scene.files)
        assertTrue(scene.elements.isEmpty())
        assertTrue(scene.appState.isEmpty())
    }

    @Test
    fun `data class equality holds for identical field values`() {
        val scene1 = ExcalidrawScene(
            type = "excalidraw",
            version = 1,
            source = null,
            elements = listOf<Map<String, Any>>(mapOf<String, Any>("id" to "a")),
            appState = mapOf<String, Any>("zoom" to 1),
            files = null
        )
        val scene2 = ExcalidrawScene(
            type = "excalidraw",
            version = 1,
            source = null,
            elements = listOf<Map<String, Any>>(mapOf<String, Any>("id" to "a")),
            appState = mapOf<String, Any>("zoom" to 1),
            files = null
        )

        assertEquals(scene1, scene2)
        assertEquals(scene1.hashCode(), scene2.hashCode())
    }

    @Test
    fun `copy produces independent instance with overridden fields`() {
        val original = ExcalidrawScene(
            type = "excalidraw",
            version = 1,
            source = null,
            elements = emptyList<Map<String, Any>>(),
            appState = emptyMap<String, Any>(),
            files = null
        )
        val copy = original.copy(version = 2, source = "https://example.com")

        assertEquals(1, original.version)
        assertNull(original.source)
        assertEquals(2, copy.version)
        assertEquals("https://example.com", copy.source)
    }
}
