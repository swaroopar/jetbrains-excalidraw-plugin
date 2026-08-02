package com.swaroop.excalidraw.plugin.export

/**
 * CanvasRenderer — the single decode step of the "ask the JS side to rasterise the
 * canvas → receive bytes" round-trip.
 *
 * Both [ExcalidrawExporter] (export-to-file) and
 * [com.swaroop.excalidraw.plugin.persistence.document.PngSceneDocument] (PNG
 * autosave re-embedding) request a render from the JS side and get back either a
 * raw SVG string or a Base64-encoded PNG. This is the one place that turns that
 * response into bytes, so a decode bug (e.g. Base64 handling) only needs fixing
 * once. The two callers differ only in where the bytes end up afterwards (a
 * user-chosen file vs. re-embedding into the same `.excalidraw.png`).
 */
object CanvasRenderer {

    /** Encodes [svg] (a raw SVG string from the JS side) to UTF-8 bytes. */
    fun decodeSvg(svg: String): ByteArray = svg.toByteArray(Charsets.UTF_8)

    /**
     * Decodes [base64Png] (a Base64-encoded PNG from the JS side) to bytes, or
     * null if it is not valid Base64. Callers are responsible for logging/handling
     * the null case — this function only isolates the decode step.
     */
    fun decodeBase64Png(base64Png: String): ByteArray? = try {
        java.util.Base64.getDecoder().decode(base64Png)
    } catch (_: IllegalArgumentException) {
        null
    }
}
