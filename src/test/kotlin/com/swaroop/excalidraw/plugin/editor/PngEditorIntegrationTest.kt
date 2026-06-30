package com.swaroop.excalidraw.plugin.editor

import com.intellij.openapi.vfs.VirtualFile
import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge
import com.swaroop.excalidraw.plugin.bridge.SceneChangeMessage
import com.swaroop.excalidraw.plugin.jcef.ExcalidrawJcefHost
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawPersistenceService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end integration tests for the `.excalidraw.png` editor workflow (E6).
 *
 * Covers:
 *  - AC-E6-01: Opening a `.excalidraw.png` file — async PNG extraction via bridge,
 *    currentSceneJson set on success, no notifications on success.
 *  - AC-E6-02: Auto-saving a `.excalidraw.png` file — async PNG export via bridge,
 *    decoded bytes begin with PNG magic byte 0x89, written exactly once.
 *  - AC-E6-03: PNG open with no embedded scene — opens as a blank, editable drawing;
 *    no file-write on open; the user's first edit saves (creating the round-trip).
 *
 * Test harness: plain JUnit 5 + createForTest factories (no JCEF runtime, no MockK,
 * no Mockito). Uses FakePersistenceService to capture writePngScene calls in-process.
 *
 * Package: com.swaroop.excalidraw.plugin.editor (feature E6, task-07-009).
 */
class PngEditorIntegrationTest {

    // -------------------------------------------------------------------------
    // Fake persistence service — records writePngScene calls in-process
    // -------------------------------------------------------------------------

    /**
     * In-test fake for [ExcalidrawPersistenceService].
     *
     * Overrides [writeScene] and [writePngScene] to record calls without
     * requiring a live IntelliJ Application or VFS WriteAction.
     * [writtenBytes] captures the decoded bytes produced by [writePngScene]
     * so tests can verify the PNG magic byte.
     */
    private class FakePersistenceService : ExcalidrawPersistenceService() {
        var writtenBytes: ByteArray? = null
        var writeSceneCallCount: Int = 0
        var writePngSceneCallCount: Int = 0

        override fun writeScene(file: VirtualFile, json: String) {
            writeSceneCallCount++
        }

        override fun writePngScene(file: VirtualFile, base64Png: String) {
            writePngSceneCallCount++
            writtenBytes = java.util.Base64.getDecoder().decode(base64Png)
        }
    }

    // -------------------------------------------------------------------------
    // Test constants
    // -------------------------------------------------------------------------

    /** Minimal PNG-magic bytes for the stub file (0x89 followed by zeros). */
    private val stubPngBytes = ByteArray(8) { i -> if (i == 0) 0x89.toByte() else 0 }

    /**
     * A tiny 1×1 PNG with PNG magic header (0x89 50 4E 47 ...) encoded as base64.
     * Used as the simulated export result payload in AC-E6-02.
     */
    private val validBase64Png =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVQI12NgAAIABQ" +
        "AABjE+ibYAAAAASUVORK5CYII="

    // -------------------------------------------------------------------------
    // AC-E6-01 — PNG open: bridge receives __excalidrawLoadPng__, currentSceneJson set
    // -------------------------------------------------------------------------

    /**
     * AC-E6-01: Opening a `.excalidraw.png` file triggers an async PNG extraction.
     *
     * After [ExcalidrawJcefHost.fireLoadEnd]:
     *  - At least one captured JS string contains "__excalidrawLoadPng__"
     *    (the bridge injected requestPngExtract).
     *
     * After [ExcalidrawJsBridge.simulatePngExtracted] with a valid scene:
     *  - [ExcalidrawFileEditor.currentSceneJson] is not null.
     *  - No notifications were raised (success path, no error).
     */
    @Test
    fun `AC-E6-01 PNG open sets currentSceneJson and injects LOAD_PNG_FN without notification`() {
        val capturedJs = mutableListOf<String>()
        val capturedNotifications = mutableListOf<String>()

        val bridge = ExcalidrawJsBridge.createForTest(
            injector = { js -> capturedJs.add(js) }
        )
        val file = StubVirtualFile("valid.excalidraw.png", stubPngBytes)
        val stubHost = ExcalidrawJcefHost.createForTest()
        val fakePersistence = FakePersistenceService()

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = bridge,
            persistenceService = fakePersistence,
            notifier = { msg -> capturedNotifications.add(msg) }
        )

        // Simulate the JCEF loadEnd event — triggers the PNG async path
        stubHost.fireLoadEnd()

        assertTrue(
            capturedJs.any { "__excalidrawLoadPng__" in it },
            "After loadEnd on .excalidraw.png, capturedJs must contain '__excalidrawLoadPng__'. " +
                "Got: $capturedJs"
        )

        // Simulate the JS side returning a successfully extracted scene
        val extractedScene = """{"type":"excalidraw","elements":[],"appState":{}}"""
        bridge.simulatePngExtracted(
            """{"type":"pngExtracted","sceneJson":${com.google.gson.Gson().toJson(extractedScene)}}"""
        )

        assertNotNull(
            editor.currentSceneJson,
            "AC-E6-01: currentSceneJson must be set after successful PNG extraction"
        )
        assertTrue(
            capturedNotifications.isEmpty(),
            "AC-E6-01: notifier must NOT be called on success path. Calls: $capturedNotifications"
        )

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // AC-E6-02 — PNG save: bridge receives __excalidrawExportPng__, PNG magic written
    // -------------------------------------------------------------------------

    /**
     * AC-E6-02: Auto-saving a `.excalidraw.png` file re-embeds the scene.
     *
     * After a scene-change event and [ExcalidrawFileEditor.flushDebounce]:
     *  - At least one captured JS string contains "__excalidrawExportPng__".
     *
     * After [ExcalidrawJsBridge.simulatePngExported] with a valid base64 payload:
     *  - [FakePersistenceService.writtenBytes] is not null.
     *  - The first byte is 0x89 (PNG magic byte).
     *  - [FakePersistenceService.writePngSceneCallCount] == 1 (written exactly once).
     */
    @Test
    fun `AC-E6-02 PNG save injects EXPORT_PNG_FN and writes bytes with PNG magic`() {
        val capturedJs = mutableListOf<String>()
        val fakePersistence = FakePersistenceService()
        var editorHolder: ExcalidrawFileEditor? = null

        val bridge = ExcalidrawJsBridge.createForTest(
            injector = { js -> capturedJs.add(js) },
            sceneChangeHandler = { scene: SceneChangeMessage ->
                editorHolder?.onSceneChanged(scene)
            }
        )
        val file = StubVirtualFile("save.excalidraw.png", stubPngBytes)
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
        // Arm the PNG editor through the realistic open path: loadEnd, then a successful
        // extraction whose scene has one __baseline__ element. This sets pngSceneExtracted
        // and seeds the baseline, so the empty-elements edit below counts as a real change.
        stubHost.fireLoadEnd()
        bridge.simulatePngExtracted(
            """{"type":"pngExtracted","sceneJson":${com.google.gson.Gson().toJson(
                """{"type":"excalidraw","elements":[{"type":"__baseline__"}],"appState":{}}"""
            )}}"""
        )

        // Trigger a scene change so currentSceneJson is set
        val scenePayload =
            """{"type":"sceneChange","elements":[],"appState":{"viewBackgroundColor":"#ffffff"}}"""
        bridge.simulateSceneChange(scenePayload)

        // Flush the debounce — this triggers the PNG export request
        editor.flushDebounce()

        assertTrue(
            capturedJs.any { "__excalidrawExportPng__" in it },
            "AC-E6-02: capturedJs must contain '__excalidrawExportPng__' after flushDebounce. " +
                "Got: $capturedJs"
        )

        // Simulate the JS side returning the PNG export result
        bridge.simulatePngExported(
            """{"type":"pngExported","base64Png":"$validBase64Png"}"""
        )

        assertEquals(
            1,
            fakePersistence.writePngSceneCallCount,
            "AC-E6-02: writePngScene must be called exactly once after simulatePngExported"
        )
        val writtenBytes = fakePersistence.writtenBytes
        assertNotNull(writtenBytes, "AC-E6-02: writtenBytes must not be null after successful export")
        assertEquals(
            0x89.toByte(),
            writtenBytes!![0],
            "AC-E6-02: first byte of written PNG must be 0x89 (PNG magic byte)"
        )

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // AC-E6-03 — PNG open error: notifier called, no write, currentSceneJson null
    // -------------------------------------------------------------------------

    /**
     * AC-E6-03: Opening a `.excalidraw.png` without an embedded Excalidraw scene
     * must show a non-destructive error and leave the file untouched.
     *
     * After [ExcalidrawJsBridge.simulatePngExtracted] with error != null (no embedded scene):
     *  - The file is opened as a blank, editable drawing (currentSceneJson is armed, non-null).
     *  - Opening is non-destructive: writePngScene is NOT called just by opening.
     *  - A subsequent real edit IS persisted (writePngScene called once), turning the file
     *    into a proper `.excalidraw.png` with an embedded scene.
     */
    @Test
    fun `AC-E6-03 PNG with no embedded scene opens blank, no write on open, edit then saves`() {
        val capturedJs = mutableListOf<String>()
        val capturedNotifications = mutableListOf<String>()
        val fakePersistence = FakePersistenceService()
        var editorHolder: ExcalidrawFileEditor? = null

        val bridge = ExcalidrawJsBridge.createForTest(
            injector = { js -> capturedJs.add(js) },
            sceneChangeHandler = { scene: SceneChangeMessage ->
                editorHolder?.onSceneChanged(scene)
            }
        )
        val file = StubVirtualFile("valid.excalidraw.png", stubPngBytes)
        val stubHost = ExcalidrawJcefHost.createForTest()

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = bridge,
            persistenceService = fakePersistence,
            notifier = { msg -> capturedNotifications.add(msg) },
            debounceExecutor = {}
        )
        editorHolder = editor

        // Simulate the JCEF loadEnd event
        stubHost.fireLoadEnd()

        // Simulate PNG extraction failure — no embedded Excalidraw scene
        bridge.simulatePngExtracted(
            """{"type":"pngExtracted","error":"No Excalidraw scene found in PNG"}"""
        )

        // Opened as a blank, editable drawing — armed but nothing written yet.
        assertNotNull(
            editor.currentSceneJson,
            "AC-E6-03: a scene-less PNG must open as an armed blank drawing (currentSceneJson set)"
        )
        assertEquals(
            0,
            fakePersistence.writePngSceneCallCount,
            "AC-E6-03: opening a scene-less PNG must not write the file"
        )

        // The user draws something — this real edit must be persisted.
        bridge.simulateSceneChange(
            """{"type":"sceneChange","elements":[{"type":"rectangle","id":"drawn"}],"appState":{}}"""
        )
        editor.flushDebounce()
        bridge.simulatePngExported(
            """{"type":"pngExported","base64Png":"$validBase64Png"}"""
        )

        assertEquals(
            1,
            fakePersistence.writePngSceneCallCount,
            "AC-E6-03: the user's first edit on a scene-less PNG must be saved (round-trip created)"
        )

        editor.dispose()
    }
}
