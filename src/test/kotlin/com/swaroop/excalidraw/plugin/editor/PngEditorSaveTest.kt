package com.swaroop.excalidraw.plugin.editor

import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge
import com.swaroop.excalidraw.plugin.bridge.SceneChangeMessage
import com.swaroop.excalidraw.plugin.jcef.ExcalidrawJcefHost
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawPersistenceService
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExcalidrawFileEditor] async PNG-save path (task-07-008).
 *
 * Acceptance criteria (AC-E6-02):
 *   - onSceneChanged + flushDebounce on a .excalidraw.png file injects
 *     __excalidrawExportPng__ into the JS bridge (not __excalidrawLoadScene__).
 *   - After simulatePngExported with a valid base64Png payload, writePngScene
 *     is invoked; the decoded bytes begin with PNG magic byte 0x89.
 *   - _modified is cleared to false after successful writePngScene.
 *   - On error (msg.error != null): writePngScene is NOT called; _modified
 *     remains true (no write on error).
 *
 * All tests use createForTest factories — no JCEF runtime required.
 * No MockK, no Mockito. Plain JUnit 5.
 */
class PngEditorSaveTest {

    // -------------------------------------------------------------------------
    // Fake persistence service that records writePngScene calls
    // -------------------------------------------------------------------------

    /**
     * In-test fake for [ExcalidrawPersistenceService].
     *
     * Overrides both [writeScene] and [writePngScene] to record calls without
     * requiring a live IntelliJ Application or FileDocumentManager.
     */
    private class FakePersistenceService : ExcalidrawPersistenceService() {
        var writePngSceneCallCount: Int = 0
        var writtenPngBytes: ByteArray? = null
        var writeSceneCallCount: Int = 0

        override fun writeScene(file: VirtualFile, json: String) {
            writeSceneCallCount++
        }

        override fun writePngScene(file: VirtualFile, base64Png: String) {
            writePngSceneCallCount++
            writtenPngBytes = java.util.Base64.getDecoder().decode(base64Png)
        }
    }

    // -------------------------------------------------------------------------
    // A tiny 1x1 PNG with an embedded Excalidraw scene (base64-encoded).
    // This is a real minimal PNG produced by Excalidraw with exportEmbedScene:true.
    // For testing purposes we use a known valid base64 string that starts with
    // the PNG magic header bytes (0x89 50 4E 47 0D 0A 1A 0A).
    // -------------------------------------------------------------------------

    /**
     * Minimal base64-encoded PNG that starts with PNG magic bytes (0x89 ...).
     * Used to verify that writePngScene decodes the bytes correctly and that
     * the first byte is 0x89 (PNG magic).
     */
    private val validBase64Png =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVQI12NgAAIABQ" +
        "AABjE+ibYAAAAASUVORK5CYII="

    // -------------------------------------------------------------------------
    // Helper: build a PNG editor with fake persistence and debounce executor
    // -------------------------------------------------------------------------

    /**
     * Builds a [ExcalidrawFileEditor] for a .excalidraw.png file, wired for
     * debounce testing with a [FakePersistenceService].
     *
     * @return Triple of (editor, bridge, fakePersistenceService)
     */
    private fun buildPngEditor(
        fileName: String = "save.excalidraw.png",
        pngBytes: ByteArray = ByteArray(8) { i -> if (i == 0) 0x89.toByte() else 0 }
    ): Triple<ExcalidrawFileEditor, ExcalidrawJsBridge, FakePersistenceService> {
        val fakePersistence = FakePersistenceService()

        var editorHolder: ExcalidrawFileEditor? = null

        val capturedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(
            injector = { js -> capturedJs.add(js) },
            sceneChangeHandler = { scene: SceneChangeMessage ->
                editorHolder?.onSceneChanged(scene)
            }
        )

        val file = StubVirtualFile(fileName, pngBytes)
        val stubHost = ExcalidrawJcefHost.createForTest()

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = bridge,
            persistenceService = fakePersistence,
            notifier = { _ -> },
            debounceExecutor = {}
        )

        editorHolder = editor
        // Arm the PNG editor through the realistic open path: fire loadEnd, then deliver a
        // successful extraction. This sets pngSceneExtracted=true and seeds the baseline from
        // the extracted scene (one __baseline__ element), so the empty-elements edit the tests
        // fire afterwards counts as a genuine change. Without this, autosave stays disabled —
        // a .excalidraw.png whose scene was never extracted must never be overwritten.
        stubHost.fireLoadEnd()
        bridge.simulatePngExtracted(EXTRACTED_BASELINE_PAYLOAD)
        return Triple(editor, bridge, fakePersistence)
    }

    private companion object {
        // Unedited-baseline scene-change (mirrors the initial onChange on scene load) used
        // by the plain-.excalidraw (JSON) regression test, which is not PNG-gated.
        const val BASELINE_PAYLOAD =
            """{"type":"sceneChange","elements":[{"type":"__baseline__"}],"appState":{}}"""

        // A successful PNG extraction carrying one __baseline__ element. Delivering it arms
        // autosave and seeds the change-detection baseline; the tests' empty-elements payload
        // differs from it and therefore registers as a real edit.
        val EXTRACTED_BASELINE_PAYLOAD: String =
            """{"type":"pngExtracted","sceneJson":${com.google.gson.Gson().toJson(
                """{"type":"excalidraw","elements":[{"type":"__baseline__"}],"appState":{}}"""
            )}}"""
    }

    // -------------------------------------------------------------------------
    // Test 1 (AC-E6-02): scheduleAutosave on PNG injects __excalidrawExportPng__
    // -------------------------------------------------------------------------

    /**
     * AC-E6-02 (part A): After onSceneChanged + flushDebounce on a .excalidraw.png
     * file, at least one injected JS string must contain "__excalidrawExportPng__".
     *
     * The .excalidraw path must NOT be taken — __excalidrawLoadScene__ must not
     * appear in the autosave JS calls.
     */
    @Test
    fun `autosave on png file injects requestPngExport JS with EXPORT_PNG_FN`() {
        val capturedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> capturedJs.add(js) })

        val file = StubVirtualFile("save.excalidraw.png",
            ByteArray(8) { i -> if (i == 0) 0x89.toByte() else 0 })
        val stubHost = ExcalidrawJcefHost.createForTest()
        val fakePersistence = FakePersistenceService()

        var editorHolder: ExcalidrawFileEditor? = null
        val bridgeForScene = ExcalidrawJsBridge.createForTest(
            injector = { js -> capturedJs.add(js) },
            sceneChangeHandler = { scene: SceneChangeMessage ->
                editorHolder?.onSceneChanged(scene)
            }
        )

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = bridgeForScene,
            persistenceService = fakePersistence,
            notifier = { _ -> },
            debounceExecutor = {}
        )
        editorHolder = editor
        // Arm via the realistic open path before exercising autosave.
        stubHost.fireLoadEnd()
        bridgeForScene.simulatePngExtracted(EXTRACTED_BASELINE_PAYLOAD)

        // Trigger a scene change so currentSceneJson is set
        val scenePayload =
            """{"type":"sceneChange","elements":[],"appState":{"viewBackgroundColor":"#ffffff"}}"""
        bridgeForScene.simulateSceneChange(scenePayload)

        // Flush the debounce — this should inject __excalidrawExportPng__
        editor.flushDebounce()

        assertTrue(
            capturedJs.any { it.contains("__excalidrawExportPng__") },
            "autosave on .excalidraw.png must inject __excalidrawExportPng__. Got: $capturedJs"
        )

        // The .excalidraw (JSON) path must NOT be taken
        assertFalse(
            capturedJs.any { it.contains("__excalidrawLoadScene__") },
            "PNG save path must NOT inject __excalidrawLoadScene__. Got: $capturedJs"
        )

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // Test 2 (AC-E6-02): writePngScene called after simulatePngExported success
    // -------------------------------------------------------------------------

    /**
     * AC-E6-02 (part B): After simulatePngExported with a valid base64Png payload,
     * writePngScene must be called exactly once; the decoded bytes must start with
     * the PNG magic byte 0x89.
     */
    @Test
    fun `simulatePngExported success calls writePngScene and bytes start with PNG magic`() {
        val (editor, bridge, fakePersistence) = buildPngEditor()

        // Set currentSceneJson via a scene change event
        val scenePayload =
            """{"type":"sceneChange","elements":[],"appState":{"viewBackgroundColor":"#ffffff"}}"""
        bridge.simulateSceneChange(scenePayload)

        // Flush the debounce to trigger the PNG export request
        editor.flushDebounce()

        // Simulate the JS side returning the PNG export result
        bridge.simulatePngExported(
            """{"type":"pngExported","base64Png":"$validBase64Png"}"""
        )

        assertEquals(
            1,
            fakePersistence.writePngSceneCallCount,
            "writePngScene must be called exactly once after successful simulatePngExported (AC-E6-02)"
        )

        val bytes = fakePersistence.writtenPngBytes
        assertNotNull(bytes, "writtenPngBytes must not be null after successful export")
        assertEquals(
            0x89.toByte(),
            bytes!![0],
            "First byte of written PNG must be 0x89 (PNG magic byte) (AC-E6-02)"
        )

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // Test 3 (AC-E6-02): _modified cleared after successful writePngScene
    // -------------------------------------------------------------------------

    /**
     * AC-E6-02 (modified flag): After a successful PNG export + writePngScene,
     * isModified() must return false (the file has been saved).
     */
    @Test
    fun `isModified is false after successful PNG export and write`() {
        val (editor, bridge, _) = buildPngEditor()

        val scenePayload =
            """{"type":"sceneChange","elements":[],"appState":{"viewBackgroundColor":"#ffffff"}}"""
        bridge.simulateSceneChange(scenePayload)

        assertTrue(editor.isModified(), "isModified must be true after scene change (pre-save)")

        editor.flushDebounce()

        // Simulate success
        bridge.simulatePngExported(
            """{"type":"pngExported","base64Png":"$validBase64Png"}"""
        )

        assertFalse(
            editor.isModified(),
            "isModified must be false after successful PNG export and write (AC-E6-02)"
        )

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // Test 4 (AC-E6-02 error): error → no writePngScene, _modified stays true
    // -------------------------------------------------------------------------

    /**
     * AC-E6-02 (error path): When simulatePngExported contains error != null,
     * writePngScene must NOT be called, and isModified() must remain true
     * (the file was not saved due to the error).
     */
    @Test
    fun `simulatePngExported error does not call writePngScene and modified stays true`() {
        val notifierCalls = mutableListOf<String>()
        val fakePersistence = FakePersistenceService()
        var editorHolder: ExcalidrawFileEditor? = null

        val bridge = ExcalidrawJsBridge.createForTest(
            injector = { _ -> },
            sceneChangeHandler = { scene: SceneChangeMessage ->
                editorHolder?.onSceneChanged(scene)
            }
        )

        val file = StubVirtualFile("save.excalidraw.png",
            ByteArray(8) { i -> if (i == 0) 0x89.toByte() else 0 })
        val stubHost = ExcalidrawJcefHost.createForTest()

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = bridge,
            persistenceService = fakePersistence,
            notifier = { msg -> notifierCalls.add(msg) },
            debounceExecutor = {}
        )
        editorHolder = editor
        // Arm via the realistic open path before exercising autosave.
        stubHost.fireLoadEnd()
        bridge.simulatePngExtracted(EXTRACTED_BASELINE_PAYLOAD)

        // Set currentSceneJson
        val scenePayload =
            """{"type":"sceneChange","elements":[],"appState":{"viewBackgroundColor":"#ffffff"}}"""
        bridge.simulateSceneChange(scenePayload)

        editor.flushDebounce()

        // Simulate export error
        bridge.simulatePngExported(
            """{"type":"pngExported","error":"Export failed: canvas is empty"}"""
        )

        assertEquals(
            0,
            fakePersistence.writePngSceneCallCount,
            "writePngScene must NOT be called when pngExported contains an error (AC-E6-02)"
        )

        assertTrue(
            editor.isModified(),
            "isModified must remain true when PNG export fails (file not saved)"
        )

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // Test 5: .excalidraw file still uses existing writeScene (regression)
    // -------------------------------------------------------------------------

    /**
     * Regression: A plain .excalidraw file must still use the synchronous
     * writeScene path — writePngScene must NOT be called.
     */
    @Test
    fun `autosave on excalidraw file uses writeScene not writePngScene`() {
        val fakePersistence = FakePersistenceService()
        var editorHolder: ExcalidrawFileEditor? = null

        val bridge = ExcalidrawJsBridge.createForTest(
            injector = { _ -> },
            sceneChangeHandler = { scene: SceneChangeMessage ->
                editorHolder?.onSceneChanged(scene)
            }
        )

        val file = StubVirtualFile("diagram.excalidraw", "{}".toByteArray(Charsets.UTF_8))
        val stubHost = ExcalidrawJcefHost.createForTest()

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = bridge,
            persistenceService = fakePersistence,
            notifier = { _ -> },
            debounceExecutor = {}
        )
        editorHolder = editor
        bridge.simulateSceneChange(BASELINE_PAYLOAD)

        val scenePayload =
            """{"type":"sceneChange","elements":[],"appState":{"viewBackgroundColor":"#ffffff"}}"""
        bridge.simulateSceneChange(scenePayload)
        editor.flushDebounce()

        assertEquals(
            1,
            fakePersistence.writeSceneCallCount,
            ".excalidraw file must use writeScene (existing path, regression)"
        )
        assertEquals(
            0,
            fakePersistence.writePngSceneCallCount,
            ".excalidraw file must NOT call writePngScene (regression)"
        )

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // Test 6 (regression): PNG without an embedded scene is never overwritten
    // -------------------------------------------------------------------------

    /**
     * Regression — a `.excalidraw.png` whose embedded scene could NOT be extracted
     * (e.g. a plain raster PNG exported by another tool) must never be overwritten by
     * autosave, even when the canvas keeps firing scene-change events with stale or
     * empty content.
     *
     * Before this fix, the first canvas onChange seeded an empty baseline and a later
     * (stale) onChange looked like a user edit, so autosave re-rasterised the wrong
     * scene over the user's image — silently destroying it and making the file "render
     * old/wrong elements". With [ExcalidrawFileEditor.pngSceneExtracted] gating the
     * write, an unextracted PNG is left byte-for-byte untouched.
     */
    @Test
    fun `png with failed extraction is never written despite scene change events`() {
        val fakePersistence = FakePersistenceService()
        var editorHolder: ExcalidrawFileEditor? = null

        val bridge = ExcalidrawJsBridge.createForTest(
            injector = { _ -> },
            sceneChangeHandler = { scene: SceneChangeMessage ->
                editorHolder?.onSceneChanged(scene)
            }
        )

        val file = StubVirtualFile(
            "foreign.excalidraw.png",
            ByteArray(8) { i -> if (i == 0) 0x89.toByte() else 0 }
        )
        val stubHost = ExcalidrawJcefHost.createForTest()

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = bridge,
            persistenceService = fakePersistence,
            notifier = { _ -> },
            debounceExecutor = {}
        )
        editorHolder = editor

        // Open path: loadEnd fires, but extraction FAILS — the PNG has no embedded scene.
        stubHost.fireLoadEnd()
        bridge.simulatePngExtracted(
            """{"type":"pngExtracted","error":"No Excalidraw scene found"}"""
        )

        // The canvas now emits stale/empty scene-change events (its own residual state).
        bridge.simulateSceneChange(
            """{"type":"sceneChange","elements":[{"type":"rectangle","id":"stale"}],"appState":{}}"""
        )
        bridge.simulateSceneChange(
            """{"type":"sceneChange","elements":[],"appState":{}}"""
        )

        // Even if a debounce had somehow been scheduled, firing it must not write.
        editor.flushDebounce()

        assertEquals(
            0,
            fakePersistence.writePngSceneCallCount,
            "A .excalidraw.png whose scene was not extracted must NEVER be overwritten by autosave"
        )
        assertFalse(
            editor.isModified(),
            "An unextracted PNG must not be marked modified by stale canvas events"
        )

        editor.dispose()
    }
}
