package com.swaroop.excalidraw.plugin.persistence

import com.intellij.openapi.vfs.VirtualFile

/**
 * Port for reading and writing an Excalidraw [Scene] to/from a file. Production code
 * depends on this interface, not on [ExcalidrawPersistenceService] directly, so tests can
 * satisfy it with a small in-test implementation instead of subclassing production code.
 */
interface ScenePersistence {

    /**
     * Reads the content of [file] and parses it as an Excalidraw [Scene].
     *
     * @throws ExcalidrawParseException if the content is empty, is not valid JSON,
     *         or is missing mandatory fields (elements, appState).
     */
    fun readScene(file: VirtualFile): Scene

    /**
     * Like [readScene], but treats an empty / blank file as a NEW blank drawing
     * ([Scene.empty]) instead of throwing.
     */
    fun readSceneOrNew(file: VirtualFile): Scene

    /** Writes [scene]'s canonical JSON to [file]. */
    fun writeScene(file: VirtualFile, scene: Scene)

    /** Writes raw PNG bytes (standard Base64-encoded in [base64Png]) to [file]. */
    fun writePngScene(file: VirtualFile, base64Png: String)
}
