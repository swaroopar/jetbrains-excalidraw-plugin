package com.swaroop.excalidraw.plugin.persistence.document

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawPersistenceService

/**
 * [SceneDocument] adapter for scene-embedded `.excalidraw.png` files.
 *
 * Both load and save round-trip through the bridge asynchronously: load extracts
 * the embedded scene JSON from the PNG bytes via the JS side, and save re-embeds
 * the current scene into a freshly rendered PNG. One instance is created per open
 * editor, so [extracted] tracks whether *this* file's extraction round-trip has
 * settled the canvas — [save] refuses to run until it has, since writing before
 * that point would overwrite the file with canvas state unrelated to it.
 */
class PngSceneDocument(
    private val persistenceService: ExcalidrawPersistenceService
) : SceneDocument {

    @Volatile
    private var extracted: Boolean = false

    override fun load(file: VirtualFile, bridge: ExcalidrawJsBridge, onResult: (SceneLoadResult) -> Unit) {
        val application = ApplicationManager.getApplication()
        val bytes: ByteArray = if (application != null) {
            ReadAction.compute<ByteArray, Throwable> { file.contentsToByteArray() }
        } else {
            file.contentsToByteArray()
        }
        val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val dataUrl = "data:image/png;base64,$base64"

        bridge.installReturnChannel()
        bridge.installClipboardBridge()

        bridge.registerPngExtractedCallback { msg ->
            val deliver: () -> Unit = {
                if (msg.error != null) {
                    LOG.info(
                        "PngSceneDocument: '${file.path}' has no embedded scene " +
                            "(${msg.error}) — opening as a new blank drawing"
                    )
                    extracted = true
                    onResult(SceneLoadResult.LoadedAndBaselined(EMPTY_SCENE_JSON))
                } else {
                    extracted = true
                    onResult(SceneLoadResult.LoadedAndBaselined(msg.sceneJson ?: EMPTY_SCENE_JSON))
                }
            }
            if (application != null) {
                application.invokeLater(deliver)
            } else {
                deliver()
            }
        }

        bridge.requestPngExtract(dataUrl)
    }

    override fun save(
        file: VirtualFile,
        sceneJson: String,
        bridge: ExcalidrawJsBridge,
        onResult: (SceneSaveResult) -> Unit
    ) {
        if (!extracted) {
            onResult(SceneSaveResult.Skipped)
            return
        }

        val application = ApplicationManager.getApplication()

        bridge.registerPngExportedCallback { msg ->
            val deliver: () -> Unit = {
                if (msg.error != null) {
                    onResult(SceneSaveResult.Failed(msg.error))
                } else if (msg.base64Png != null) {
                    persistenceService.writePngScene(file, msg.base64Png)
                    onResult(SceneSaveResult.Saved)
                }
                // base64Png == null AND error == null: unexpected — no write, no crash.
            }
            if (application != null) {
                application.invokeLater(deliver)
            } else {
                deliver()
            }
        }

        bridge.requestPngExport(sceneJson)
    }

    companion object {
        private val LOG: Logger = Logger.getInstance(PngSceneDocument::class.java)

        /**
         * Canonical empty Excalidraw scene used when a `.excalidraw.png` is opened with no
         * embedded scene (it becomes a fresh, savable blank drawing).
         */
        const val EMPTY_SCENE_JSON: String =
            """{"type":"excalidraw","version":2,"elements":[],"appState":{},"files":{}}"""
    }
}
