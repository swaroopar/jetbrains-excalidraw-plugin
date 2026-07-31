package com.swaroop.excalidraw.plugin.persistence.document

import com.intellij.openapi.vfs.VirtualFile
import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawParseException
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawPersistenceService
import com.swaroop.excalidraw.plugin.persistence.Scene

/**
 * [SceneDocument] adapter for plain `.excalidraw` JSON files.
 *
 * Load and save are both synchronous — no bridge round-trip is required to
 * reconcile the canvas against the file, so [SceneLoadResult.LoadedAwaitingEcho]
 * is always returned (the caller seeds its baseline from the canvas's own render
 * echo instead).
 */
class JsonSceneDocument(
    private val persistenceService: ExcalidrawPersistenceService
) : SceneDocument {

    override fun load(file: VirtualFile, bridge: ExcalidrawJsBridge, onResult: (SceneLoadResult) -> Unit) {
        try {
            val scene = persistenceService.readSceneOrNew(file)
            bridge.installReturnChannel()
            bridge.installClipboardBridge()
            bridge.loadScene(scene)
            onResult(SceneLoadResult.LoadedAwaitingEcho)
        } catch (ex: ExcalidrawParseException) {
            val message = ex.message ?: "Cannot parse Excalidraw file '${ex.filePath}'"
            onResult(SceneLoadResult.Failed(message))
        }
    }

    override fun save(
        file: VirtualFile,
        scene: Scene,
        bridge: ExcalidrawJsBridge,
        onResult: (SceneSaveResult) -> Unit
    ) {
        persistenceService.writeScene(file, scene)
        onResult(SceneSaveResult.Saved)
    }
}
