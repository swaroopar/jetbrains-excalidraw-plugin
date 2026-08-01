package com.swaroop.excalidraw.plugin.persistence.document

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.vfs.VirtualFile
import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge
import com.swaroop.excalidraw.plugin.editor.StubVirtualFile
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawPersistenceService
import com.swaroop.excalidraw.plugin.persistence.ScenePersistence
import com.swaroop.excalidraw.plugin.persistence.Scene
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PngSceneDocument] — the `.excalidraw.png` [SceneDocument] adapter.
 * Both [SceneDocument.load] and [SceneDocument.save] round-trip through the bridge
 * asynchronously; [ExcalidrawJsBridge.simulatePngExtracted] / [ExcalidrawJsBridge.simulatePngExported]
 * drive the callbacks the same way the real JBCefJSQuery handler would in production.
 */
class PngSceneDocumentTest {

    private val stubPngBytes = ByteArray(8) { i -> if (i == 0) 0x89.toByte() else 0 }

    private class RecordingPersistenceService(
        private val real: ScenePersistence = ExcalidrawPersistenceService()
    ) : ScenePersistence {
        var writePngSceneCallCount = 0
        var lastWrittenBase64: String? = null

        override fun readScene(file: VirtualFile) = real.readScene(file)
        override fun readSceneOrNew(file: VirtualFile) = real.readSceneOrNew(file)

        override fun writeScene(file: VirtualFile, scene: Scene) = real.writeScene(file, scene)

        override fun writePngScene(file: VirtualFile, base64Png: String) {
            writePngSceneCallCount++
            lastWrittenBase64 = base64Png
        }
    }

    // -------------------------------------------------------------------------
    // load
    // -------------------------------------------------------------------------

    @Test
    fun `load injects requestPngExtract and reports LoadedAndBaselined on extraction success`() {
        val capturedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> capturedJs.add(js) })
        val file = StubVirtualFile("scene.excalidraw.png", stubPngBytes)
        val document = PngSceneDocument(ExcalidrawPersistenceService())

        var result: SceneLoadResult? = null
        document.load(file, bridge) { result = it }

        assertTrue(capturedJs.any { it.contains("__excalidrawLoadPng__") },
            "load must inject requestPngExtract JS. Got: $capturedJs")
        assertNull(result, "result must not be reported until the async extraction completes")

        val extractedScene = """{"type":"excalidraw","elements":[],"appState":{}}"""
        bridge.simulatePngExtracted(
            """{"type":"pngExtracted","sceneJson":${Gson().toJson(extractedScene)}}"""
        )

        val expected = Scene("excalidraw", 2, null, JsonArray(), JsonObject(), null)
        assertEquals(SceneLoadResult.LoadedAndBaselined(expected), result)
    }

    @Test
    fun `load reports LoadedAndBaselined with an empty scene on extraction failure`() {
        val bridge = ExcalidrawJsBridge.createForTest(injector = { _ -> })
        val file = StubVirtualFile("scene.excalidraw.png", stubPngBytes)
        val document = PngSceneDocument(ExcalidrawPersistenceService())

        var result: SceneLoadResult? = null
        document.load(file, bridge) { result = it }

        bridge.simulatePngExtracted("""{"type":"pngExtracted","error":"No Excalidraw scene found"}""")

        assertTrue(result is SceneLoadResult.LoadedAndBaselined,
            "extraction failure must still open a blank, armed drawing, got: $result")
        assertEquals(0, (result as SceneLoadResult.LoadedAndBaselined).scene.elements.size())
    }

    // -------------------------------------------------------------------------
    // save
    // -------------------------------------------------------------------------

    @Test
    fun `save before extraction reports Skipped and does not touch the bridge`() {
        val capturedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> capturedJs.add(js) })
        val file = StubVirtualFile("scene.excalidraw.png", stubPngBytes)
        val document = PngSceneDocument(ExcalidrawPersistenceService())

        var result: SceneSaveResult? = null
        document.save(file, Scene("excalidraw", 2, null, JsonArray(), JsonObject(), null), bridge) { result = it }

        assertEquals(SceneSaveResult.Skipped, result)
        assertTrue(capturedJs.none { it.contains("__excalidrawExportPng__") },
            "a Skipped save must not inject requestPngExport JS")
    }

    @Test
    fun `save after successful extraction injects requestPngExport and writes on success`() {
        val capturedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> capturedJs.add(js) })
        val file = StubVirtualFile("scene.excalidraw.png", stubPngBytes)
        val fakePersistence = RecordingPersistenceService()
        val document = PngSceneDocument(fakePersistence)

        document.load(file, bridge) { }
        bridge.simulatePngExtracted(
            """{"type":"pngExtracted","sceneJson":${Gson().toJson(
                """{"type":"excalidraw","elements":[],"appState":{}}"""
            )}}"""
        )

        var result: SceneSaveResult? = null
        document.save(file, Scene("excalidraw", 2, null, JsonArray(), JsonObject(), null), bridge) { result = it }

        assertTrue(capturedJs.any { it.contains("__excalidrawExportPng__") },
            "armed save must inject requestPngExport JS. Got: $capturedJs")

        val validBase64Png =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVQI12NgAAIABQAABjE+ibYAAAAASUVORK5CYII="
        bridge.simulatePngExported("""{"type":"pngExported","base64Png":"$validBase64Png"}""")

        assertEquals(1, fakePersistence.writePngSceneCallCount)
        assertEquals(SceneSaveResult.Saved, result)
    }

    @Test
    fun `save after successful extraction reports Failed on export error without writing`() {
        val bridge = ExcalidrawJsBridge.createForTest(injector = { _ -> })
        val file = StubVirtualFile("scene.excalidraw.png", stubPngBytes)
        val fakePersistence = RecordingPersistenceService()
        val document = PngSceneDocument(fakePersistence)

        document.load(file, bridge) { }
        bridge.simulatePngExtracted(
            """{"type":"pngExtracted","sceneJson":${Gson().toJson(
                """{"type":"excalidraw","elements":[],"appState":{}}"""
            )}}"""
        )

        var result: SceneSaveResult? = null
        document.save(file, Scene("excalidraw", 2, null, JsonArray(), JsonObject(), null), bridge) { result = it }
        bridge.simulatePngExported("""{"type":"pngExported","error":"Export failed"}""")

        assertEquals(0, fakePersistence.writePngSceneCallCount)
        assertEquals(SceneSaveResult.Failed("Export failed"), result)
    }
}
