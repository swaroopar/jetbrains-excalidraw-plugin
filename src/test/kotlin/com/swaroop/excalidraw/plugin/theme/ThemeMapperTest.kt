package com.swaroop.excalidraw.plugin.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ThemeMapper.lafToExcalidrawTheme].
 *
 * These tests run without any IDE runtime, without JCEF, and without
 * ApplicationManager — pure Kotlin logic only.
 *
 * AC-E4-01 (task-05-001): lafToExcalidrawTheme must map LookAndFeel names
 * to "dark" or "light" correctly.
 */
class ThemeMapperTest {

    @Test
    fun `lafToExcalidrawTheme with Darcula returns dark`() {
        assertEquals("dark", ThemeMapper.lafToExcalidrawTheme("Darcula"))
    }

    @Test
    fun `lafToExcalidrawTheme with IntelliJ Light returns light`() {
        assertEquals("light", ThemeMapper.lafToExcalidrawTheme("IntelliJ Light"))
    }

    @Test
    fun `lafToExcalidrawTheme with High Contrast returns dark`() {
        assertEquals("dark", ThemeMapper.lafToExcalidrawTheme("High Contrast"))
    }

    @Test
    fun `lafToExcalidrawTheme with null returns light`() {
        assertEquals("light", ThemeMapper.lafToExcalidrawTheme(null))
    }
}
