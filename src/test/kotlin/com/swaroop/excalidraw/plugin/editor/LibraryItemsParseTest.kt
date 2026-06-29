package com.swaroop.excalidraw.plugin.editor

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExcalidrawFileEditor.parseLibraryItems] — normalising a .excalidrawlib
 * file (v1 or v2) into the libraryItems array excalidrawAPI.updateLibrary expects.
 */
class LibraryItemsParseTest {

    @Test
    fun `passes through v2 libraryItems`() {
        val v2 = """{"type":"excalidrawlib","version":2,"libraryItems":[
            {"id":"abc","status":"published","created":111,"elements":[{"type":"rectangle"}]}
        ]}"""
        val out = ExcalidrawFileEditor.parseLibraryItems(v2)
        assertTrue(out != null && out.contains("\"abc\"") && out.contains("rectangle"), "v2 items preserved: $out")
    }

    @Test
    fun `wraps v1 library entries into items`() {
        val v1 = """{"type":"excalidrawlib","version":1,"library":[
            [{"type":"ellipse"}],
            [{"type":"diamond"}]
        ]}"""
        val out = ExcalidrawFileEditor.parseLibraryItems(v1)
        assertTrue(out != null, "v1 should parse")
        assertTrue(out!!.contains("imported-0") && out.contains("imported-1"), "v1 entries wrapped with ids: $out")
        assertTrue(out.contains("unpublished") && out.contains("ellipse") && out.contains("diamond"), "elements kept: $out")
    }

    @Test
    fun `returns null for malformed or empty input`() {
        assertNull(ExcalidrawFileEditor.parseLibraryItems("not json"))
        assertNull(ExcalidrawFileEditor.parseLibraryItems("""{"type":"excalidrawlib"}"""))
        assertNull(ExcalidrawFileEditor.parseLibraryItems("""{"type":"excalidrawlib","libraryItems":[]}"""))
    }
}
