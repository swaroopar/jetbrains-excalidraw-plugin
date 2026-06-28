package com.swaroop.excalidraw.plugin.persistence

/**
 * Typed representation of an Excalidraw scene as stored in a `.excalidraw` file.
 *
 * The fields mirror the top-level JSON structure of the Excalidraw file format:
 * - [type]: always "excalidraw" for a standard scene file.
 * - [version]: integer schema version (e.g. 2).
 * - [source]: optional URL of the Excalidraw app that produced the file.
 * - [elements]: ordered list of drawing elements; each element is a raw property map.
 * - [appState]: viewport and UI state as a raw property map.
 * - [files]: optional embedded file assets (binary blobs referenced by elements),
 *   keyed by file ID.
 *
 * This class has no dependency on IDE APIs or JCEF — it is a pure data holder
 * suitable for unit testing without a running IDE.
 */
data class ExcalidrawScene(
    val type: String,
    val version: Int,
    val source: String?,
    val elements: List<Map<String, Any>>,
    val appState: Map<String, Any>,
    val files: Map<String, Any>?
) {
    companion object {
        /**
         * A new, empty Excalidraw scene — a blank canvas with no elements.
         *
         * Used when opening an empty / newly-created `.excalidraw` file: rather than
         * surfacing a parse error, the editor opens a blank drawing (matching
         * excalidraw.com and the VSCode Excalidraw plugin). On the first edit the
         * autosave flow serializes this into valid `.excalidraw` JSON.
         */
        fun newEmpty(): ExcalidrawScene = ExcalidrawScene(
            type = "excalidraw",
            version = 2,
            source = "https://excalidraw.com",
            elements = emptyList(),
            appState = emptyMap(),
            files = emptyMap()
        )
    }
}
