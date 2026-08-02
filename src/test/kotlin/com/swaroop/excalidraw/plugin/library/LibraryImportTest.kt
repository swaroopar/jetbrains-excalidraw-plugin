package com.swaroop.excalidraw.plugin.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LibraryImport]'s pure functions: request detection, URL extraction
 * from the library site's return navigation, and `.excalidrawlib` normalisation.
 */
class LibraryImportTest {

    @Test
    fun `detects the library browse url`() {
        assertTrue(LibraryImport.isLibraryBrowseRequest("https://libraries.excalidraw.com/"))
        assertTrue(LibraryImport.isLibraryBrowseRequest("https://libraries.excalidraw.com/libraries/foo.excalidrawlib"))
    }

    @Test
    fun `does not detect unrelated urls as library browse requests`() {
        assertTrue(!LibraryImport.isLibraryBrowseRequest("https://example.com/"))
    }

    @Test
    fun `extracts library url from the real return form`() {
        // The exact shape observed from libraries.excalidraw.com (opaque-origin referrer).
        val url = "https://libraries.excalidraw.com/null/index.html" +
            "#addLibrary=https%3A%2F%2Flibraries.excalidraw.com%2Flibraries%2Fyouritjang%2Fsoftware-architecture.excalidrawlib" +
            "&token=I6XXf0ZGpfR7XVzOpuUbi"
        assertEquals(
            "https://libraries.excalidraw.com/libraries/youritjang/software-architecture.excalidrawlib",
            LibraryImport.extractAddLibraryUrl(url),
        )
    }

    @Test
    fun `extracts library url from query form`() {
        val url = "https://x.invalid/return?addLibrary=https%3A%2F%2Fexample.com%2Fa.excalidrawlib&token=t"
        assertEquals("https://example.com/a.excalidrawlib", LibraryImport.extractAddLibraryUrl(url))
    }

    @Test
    fun `returns null when no addLibrary param`() {
        assertNull(LibraryImport.extractAddLibraryUrl("https://libraries.excalidraw.com/"))
    }

    @Test
    fun `rejects non-http(s) schemes`() {
        assertNull(LibraryImport.extractAddLibraryUrl("https://x/#addLibrary=javascript%3Aalert(1)"))
        assertNull(LibraryImport.extractAddLibraryUrl("https://x/#addLibrary=file%3A%2F%2F%2Fetc%2Fpasswd"))
    }

    @Test
    fun `returns null for empty addLibrary value`() {
        assertNull(LibraryImport.extractAddLibraryUrl("https://x/#addLibrary=&token=t"))
    }

    @Test
    fun `passes through v2 libraryItems`() {
        val v2 = """{"type":"excalidrawlib","version":2,"libraryItems":[
            {"id":"abc","status":"published","created":111,"elements":[{"type":"rectangle"}]}
        ]}"""
        val out = LibraryImport.parseLibraryItems(v2)
        assertTrue(out != null && out.contains("\"abc\"") && out.contains("rectangle"), "v2 items preserved: $out")
    }

    @Test
    fun `wraps v1 library entries into items`() {
        val v1 = """{"type":"excalidrawlib","version":1,"library":[
            [{"type":"ellipse"}],
            [{"type":"diamond"}]
        ]}"""
        val out = LibraryImport.parseLibraryItems(v1)
        assertTrue(out != null, "v1 should parse")
        assertTrue(out!!.contains("imported-0") && out.contains("imported-1"), "v1 entries wrapped with ids: $out")
        assertTrue(out.contains("unpublished") && out.contains("ellipse") && out.contains("diamond"), "elements kept: $out")
    }

    @Test
    fun `returns null for malformed or empty input`() {
        assertNull(LibraryImport.parseLibraryItems("not json"))
        assertNull(LibraryImport.parseLibraryItems("""{"type":"excalidrawlib"}"""))
        assertNull(LibraryImport.parseLibraryItems("""{"type":"excalidrawlib","libraryItems":[]}"""))
    }
}
