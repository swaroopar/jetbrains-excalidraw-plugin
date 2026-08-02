package com.swaroop.excalidraw.plugin.export

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CanvasRenderer] — the single decode step shared by
 * [ExcalidrawExporter] and [com.swaroop.excalidraw.plugin.persistence.ExcalidrawPersistenceService].
 */
class CanvasRendererTest {

    @Test
    fun `decodeSvg returns UTF-8 bytes of the raw svg string`() {
        val svg = "<svg xmlns='http://www.w3.org/2000/svg'></svg>"
        val bytes = CanvasRenderer.decodeSvg(svg)
        assertTrue(bytes.toString(Charsets.UTF_8).contains("<svg"))
    }

    @Test
    fun `decodeBase64Png decodes valid Base64 to the original bytes`() {
        val original = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val base64 = java.util.Base64.getEncoder().encodeToString(original)
        val decoded = CanvasRenderer.decodeBase64Png(base64)
        assertArrayEquals(original, decoded)
    }

    @Test
    fun `decodeBase64Png returns null for malformed Base64`() {
        assertNull(CanvasRenderer.decodeBase64Png("not valid base64!!!"))
    }
}
