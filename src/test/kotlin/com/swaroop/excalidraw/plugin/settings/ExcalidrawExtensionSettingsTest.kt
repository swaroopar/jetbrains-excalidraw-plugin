package com.swaroop.excalidraw.plugin.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExcalidrawExtensionSettings].
 *
 * All tests construct the class directly (no ApplicationManager) to verify
 * normalization and state-transfer logic independently of the IDE runtime.
 *
 * AC-E7-02: Default list contains exactly .excalidraw and .excalidraw.png.
 * AC-E7-04: getState/loadState round-trip preserves extensions.
 */
class ExcalidrawExtensionSettingsTest {

    @Test
    fun testDefaultExtensions() {
        val settings = ExcalidrawExtensionSettings()
        val extensions = settings.getExtensions()
        assertEquals(listOf(".excalidraw", ".excalidraw.png"), extensions)
    }

    @Test
    fun testNormalizeAddsLeadingDot() {
        val settings = ExcalidrawExtensionSettings()
        settings.addExtension("foo")
        assertTrue(settings.getExtensions().contains(".foo"),
            "Expected .foo in extensions after addExtension(\"foo\")")
        assertFalse(settings.getExtensions().contains("foo"),
            "Raw 'foo' without leading dot must not appear")
    }

    @Test
    fun testNormalizeLowercase() {
        val settings = ExcalidrawExtensionSettings()
        settings.addExtension("EXCD")
        assertTrue(settings.getExtensions().contains(".excd"),
            "Expected .excd (lowercase) after addExtension(\"EXCD\")")
        assertFalse(settings.getExtensions().contains(".EXCD"),
            "Uppercase form must not be stored")
    }

    @Test
    fun testDeduplication() {
        val settings = ExcalidrawExtensionSettings()
        val before = settings.getExtensions().size
        settings.addExtension(".excalidraw")
        val after = settings.getExtensions().size
        assertEquals(before, after, "Duplicate addExtension must not grow the list")
    }

    @Test
    fun testRemoveExtension() {
        val settings = ExcalidrawExtensionSettings()
        settings.addExtension(".excd")
        assertTrue(settings.getExtensions().contains(".excd"))
        settings.removeExtension(".excd")
        assertFalse(settings.getExtensions().contains(".excd"),
            ".excd must be absent after removeExtension")
    }

    @Test
    fun testGetStateLoadStateRoundTrip() {
        val original = ExcalidrawExtensionSettings()
        original.addExtension(".myext")

        val state = original.getState()

        val restored = ExcalidrawExtensionSettings()
        restored.loadState(state)

        assertEquals(original.getExtensions(), restored.getExtensions(),
            "loadState must reproduce the same extension list as the original")
    }
}
