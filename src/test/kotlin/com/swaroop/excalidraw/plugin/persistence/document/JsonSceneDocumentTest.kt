package com.swaroop.excalidraw.plugin.persistence.document

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.vfs.VirtualFile
import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge
import com.swaroop.excalidraw.plugin.editor.StubVirtualFile
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawPersistenceService
import com.swaroop.excalidraw.plugin.persistence.Scene
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [JsonSceneDocument] — the `.excalidraw` (plain JSON) [SceneDocument]
 * adapter. Both [SceneDocument.load] and [SceneDocument.save] complete synchronously
 * for this format, so [onResult] fires before the call returns in every test here.
 */
class JsonSceneDocumentTest {

    private val validSceneJson = """
        {"type":"excalidraw","version":2,"elements":[{"id":"el1","type":"rectangle"}],"appState":{}}
    """.trimIndent()

    private val corruptSceneJson = "{ not valid JSON !!!"

    // -------------------------------------------------------------------------
    // load
    // -------------------------------------------------------------------------

    @Test
    fun `load with valid scene calls bridge loadScene and reports LoadedAwaitingEcho`() {
        val capturedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> capturedJs.add(js) })
        val file = StubVirtualFile("scene.excalidraw", validSceneJson.toByteArray(Charsets.UTF_8))
        val document = JsonSceneDocument(ExcalidrawPersistenceService())

        var result: SceneLoadResult? = null
        document.load(file, bridge) { result = it }

        assertTrue(capturedJs.any { it.contains("__excalidrawLoadScene__") },
            "load must inject a loadScene call. Got: $capturedJs")
        assertEquals(SceneLoadResult.LoadedAwaitingEcho, result)
    }

    @Test
    fun `load with corrupt scene reports Failed and does not call bridge loadScene`() {
        val capturedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> capturedJs.add(js) })
        val file = StubVirtualFile("corrupt.excalidraw", corruptSceneJson.toByteArray(Charsets.UTF_8))
        val document = JsonSceneDocument(ExcalidrawPersistenceService())

        var result: SceneLoadResult? = null
        document.load(file, bridge) { result = it }

        assertTrue(result is SceneLoadResult.Failed, "corrupt input must report Failed, got: $result")
        assertTrue((result as SceneLoadResult.Failed).message.isNotBlank())
        assertTrue(capturedJs.isEmpty(), "no JS must be injected for a failed load")
    }

    // -------------------------------------------------------------------------
    // save
    // -------------------------------------------------------------------------

    private class RecordingPersistenceService : ExcalidrawPersistenceService() {
        var writeSceneCallCount = 0
        var lastWrittenScene: Scene? = null

        override fun writeScene(file: VirtualFile, scene: Scene) {
            writeSceneCallCount++
            lastWrittenScene = scene
        }
    }

    @Test
    fun `save delegates directly to persistenceService writeScene, reporting Saved`() {
        val fakePersistence = RecordingPersistenceService()
        val document = JsonSceneDocument(fakePersistence)
        val bridge = ExcalidrawJsBridge.createForTest(injector = { _ -> })
        val file = StubVirtualFile("scene.excalidraw", ByteArray(0))
        val scene = Scene("excalidraw", 2, null, JsonArray(), JsonObject(), null)

        var result: SceneSaveResult? = null
        document.save(file, scene, bridge) { result = it }

        assertEquals(1, fakePersistence.writeSceneCallCount)
        assertEquals(scene, fakePersistence.lastWrittenScene)
        assertEquals(SceneSaveResult.Saved, result)
    }
}
