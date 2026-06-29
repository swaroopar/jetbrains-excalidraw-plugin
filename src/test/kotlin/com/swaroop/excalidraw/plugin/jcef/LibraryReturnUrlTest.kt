package com.swaroop.excalidraw.plugin.jcef

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LibraryBrowserDialog.extractAddLibraryUrl] — pulling the .excalidrawlib
 * URL out of the library site's return navigation (query or hash form).
 */
class LibraryReturnUrlTest {

    @Test
    fun `extracts library url from the real return form`() {
        // The exact shape observed from libraries.excalidraw.com (opaque-origin referrer).
        val url = "https://libraries.excalidraw.com/null/index.html" +
            "#addLibrary=https%3A%2F%2Flibraries.excalidraw.com%2Flibraries%2Fyouritjang%2Fsoftware-architecture.excalidrawlib" +
            "&token=I6XXf0ZGpfR7XVzOpuUbi"
        assertEquals(
            "https://libraries.excalidraw.com/libraries/youritjang/software-architecture.excalidrawlib",
            LibraryBrowserDialog.extractAddLibraryUrl(url),
        )
    }

    @Test
    fun `extracts library url from query form`() {
        val url = "https://x.invalid/return?addLibrary=https%3A%2F%2Fexample.com%2Fa.excalidrawlib&token=t"
        assertEquals("https://example.com/a.excalidrawlib", LibraryBrowserDialog.extractAddLibraryUrl(url))
    }

    @Test
    fun `returns null when no addLibrary param`() {
        assertNull(LibraryBrowserDialog.extractAddLibraryUrl("https://libraries.excalidraw.com/"))
    }

    @Test
    fun `rejects non-http(s) schemes`() {
        assertNull(LibraryBrowserDialog.extractAddLibraryUrl("https://x/#addLibrary=javascript%3Aalert(1)"))
        assertNull(LibraryBrowserDialog.extractAddLibraryUrl("https://x/#addLibrary=file%3A%2F%2F%2Fetc%2Fpasswd"))
    }

    @Test
    fun `returns null for empty addLibrary value`() {
        assertNull(LibraryBrowserDialog.extractAddLibraryUrl("https://x/#addLibrary=&token=t"))
    }
}
