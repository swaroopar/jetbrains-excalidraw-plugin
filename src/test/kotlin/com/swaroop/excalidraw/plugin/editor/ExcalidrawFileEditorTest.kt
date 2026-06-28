package com.swaroop.excalidraw.plugin.editor

import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge
import com.swaroop.excalidraw.plugin.jcef.ExcalidrawJcefHost
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawPersistenceService
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawScene
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExcalidrawFileEditor] open+render lifecycle.
 *
 * Strategy: uses [ExcalidrawFileEditor.createForTest] to inject stub
 * [ExcalidrawJcefHost] and [ExcalidrawJsBridge] implementations so that
 * no JCEF runtime is required. The [notifier] hook verifies that AC-E1-02
 * ("error is shown") is met without needing a live IDE notification subsystem.
 *
 * Scenarios covered (task-02-007 acceptance criteria):
 *   (a) AC-E1-01: valid fixture file causes bridge.loadScene to be called
 *       with the correctly parsed scene data.
 *   (b) AC-E1-02: corrupt fixture file causes the notifier to be called exactly
 *       once, VirtualFile content remains unchanged, bridge.loadScene is not
 *       called, and no uncaught exception escapes.
 */
class ExcalidrawFileEditorTest {

    // -------------------------------------------------------------------------
    // Helpers — stub VirtualFile backed by in-memory bytes
    // -------------------------------------------------------------------------

    /**
     * Creates an in-memory VirtualFile stub backed by [content] bytes.
     * Backed by [StubVirtualFile] — no real IDE VFS involved.
     */
    private fun stubVirtualFile(name: String, content: String): StubVirtualFile =
        StubVirtualFile(name, content.toByteArray(Charsets.UTF_8))

    private val validSceneJson = """{
  "type": "excalidraw",
  "version": 2,
  "source": "https://excalidraw.com",
  "elements": [{"id": "el1", "type": "rectangle"}],
  "appState": {"viewBackgroundColor": "#ffffff"},
  "files": {}
}"""

    private val corruptSceneJson = "{ this is not valid JSON at all !!!"

    // -------------------------------------------------------------------------
    // AC-E1-01: valid file → bridge.loadScene called with correct scene data
    // -------------------------------------------------------------------------

    /**
     * AC-E1-01: Opening a valid .excalidraw file causes bridge.loadScene to be
     * called with the correctly parsed ExcalidrawScene.
     *
     * The test uses the createForTest factory to inject:
     * - A stub JcefHost that fires loadEnd synchronously.
     * - A stub bridge (createForTest) that captures the loadScene call.
     */
    @Test
    fun `valid fixture file causes bridge loadScene to be called with correct scene data`() {
        val capturedJs = mutableListOf<String>()
        val notifierCalls = mutableListOf<String>()

        val stubBridge = ExcalidrawJsBridge.createForTest(
            injector = { js -> capturedJs.add(js) }
        )

        val file = stubVirtualFile("test.excalidraw", validSceneJson)
        val stubHost = ExcalidrawJcefHost.createForTest()

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = stubBridge,
            persistenceService = ExcalidrawPersistenceService(),
            notifier = { msg -> notifierCalls.add(msg) }
        )

        // Trigger the loadEnd callback (simulates JCEF page-load complete)
        stubHost.fireLoadEnd()

        // AC-E1-01: bridge.loadScene must have been called with correct scene
        assertTrue(capturedJs.isNotEmpty(), "bridge.loadScene must produce JS injection after loadEnd")

        val js = capturedJs.last()
        assertTrue(js.contains("loadScene"), "JS call must reference loadScene")
        assertTrue(
            js.contains("excalidraw") || js.contains("rectangle"),
            "JS payload must contain scene data from the fixture"
        )

        // No error on valid scene
        assertTrue(notifierCalls.isEmpty(), "notifier must NOT be called for a valid scene")

        editor.dispose()
    }

    /**
     * AC-E1-01 (scene fields): the scene passed to bridge must match the fixture's elements.
     *
     * Verifies that the payload contains the "el1" element id from the fixture JSON.
     */
    @Test
    fun `valid fixture scene data contains expected element id in JS payload`() {
        val capturedJs = mutableListOf<String>()
        val stubBridge = ExcalidrawJsBridge.createForTest(
            injector = { js -> capturedJs.add(js) }
        )

        val file = stubVirtualFile("scene.excalidraw", validSceneJson)
        val stubHost = ExcalidrawJcefHost.createForTest()

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = stubBridge,
            persistenceService = ExcalidrawPersistenceService()
        )

        stubHost.fireLoadEnd()

        assertTrue(capturedJs.isNotEmpty(), "No JS injected after loadEnd")
        // The fixture contains el1 — the serialised payload must carry it
        val js = capturedJs.last()
        assertTrue(js.contains("el1"), "Serialised payload must contain element id 'el1' from fixture")

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // AC-E1-02: corrupt file → notifier called once, VirtualFile unchanged, no crash
    // -------------------------------------------------------------------------

    /**
     * AC-E1-02 (core): Opening a corrupt .excalidraw file must:
     * - NOT call bridge.loadScene.
     * - Call the notifier exactly once with a non-blank error message.
     * - NOT modify the VirtualFile content.
     * - NOT throw an uncaught exception.
     */
    @Test
    fun `corrupt fixture file calls notifier exactly once and does not call bridge loadScene`() {
        val capturedJs = mutableListOf<String>()
        val notifierCalls = mutableListOf<String>()

        val stubBridge = ExcalidrawJsBridge.createForTest(
            injector = { js -> capturedJs.add(js) }
        )

        val file = stubVirtualFile("corrupt.excalidraw", corruptSceneJson)
        val stubHost = ExcalidrawJcefHost.createForTest()

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = stubBridge,
            persistenceService = ExcalidrawPersistenceService(),
            notifier = { msg -> notifierCalls.add(msg) }
        )

        // Must not throw — ExcalidrawParseException is caught internally (AC-E1-02)
        stubHost.fireLoadEnd()

        // Notifier called exactly once with non-blank message
        assertEquals(1, notifierCalls.size,
            "notifier must be called exactly once when parse fails (AC-E1-02)")
        assertTrue(notifierCalls[0].isNotBlank(),
            "notifier message must not be blank")

        // bridge.loadScene must NOT have been called for corrupt input
        assertTrue(capturedJs.isEmpty(),
            "bridge.loadScene must NOT be called for corrupt fixture input")

        editor.dispose()
    }

    /**
     * AC-E1-02 (VirtualFile guard): VirtualFile content is unchanged after a parse error.
     * No bytes written via getOutputStream (AD-03 no-mutation guarantee).
     */
    @Test
    fun `corrupt fixture VirtualFile content is unchanged after parse error`() {
        val originalBytes = corruptSceneJson.toByteArray(Charsets.UTF_8)
        val file = stubVirtualFile("corrupt-vf.excalidraw", corruptSceneJson)
        val stubHost = ExcalidrawJcefHost.createForTest()

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = ExcalidrawJsBridge.createForTest(injector = { _ -> }),
            persistenceService = ExcalidrawPersistenceService(),
            notifier = { _ -> }
        )

        stubHost.fireLoadEnd()

        // No writes via getOutputStream (close-captured)
        assertTrue(file.capturedWrites.isEmpty(),
            "VirtualFile must not be written when parse fails (AD-03)")
        // In-memory content byte-for-byte identical
        assertEquals(
            originalBytes.toString(Charsets.UTF_8),
            file.contentsToByteArray().toString(Charsets.UTF_8),
            "VirtualFile content must be unchanged after corrupt parse attempt"
        )

        editor.dispose()
    }

    /**
     * AC-E1-02 (resilience): after a parse error, dispose() must not throw.
     */
    @Test
    fun `editor dispose after corrupt file parse error does not throw`() {
        val file = stubVirtualFile("corrupt2.excalidraw", corruptSceneJson)
        val stubHost = ExcalidrawJcefHost.createForTest()

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = ExcalidrawJsBridge.createForTest(injector = { _ -> }),
            persistenceService = ExcalidrawPersistenceService(),
            notifier = { _ -> }
        )

        stubHost.fireLoadEnd()

        // Dispose must be safe regardless of whether loadEnd triggered an error
        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // Return-channel installation (task-03-005)
    // -------------------------------------------------------------------------

    /**
     * task-03-005: On loadEnd, the editor must install the JS→Kotlin return
     * channel (window.__excalidrawPostToKotlin__) BEFORE loading the scene,
     * so the function is available when the first onChange fires.
     *
     * Verified by inspecting the order of JS injections captured by the stub
     * bridge injector: the return-channel definition must appear before the
     * loadScene call in the sequence of injected strings.
     */
    @Test
    fun `loadEnd installs return channel before loadScene on valid file`() {
        val injectedJs = mutableListOf<String>()
        val stubBridge = ExcalidrawJsBridge.createForTest(
            injector = { js -> injectedJs.add(js) }
        )

        val file = stubVirtualFile("scene.excalidraw", validSceneJson)
        val stubHost = ExcalidrawJcefHost.createForTest()

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = stubBridge,
            persistenceService = ExcalidrawPersistenceService()
        )

        stubHost.fireLoadEnd()

        // At least two JS injections: channel definition + loadScene call
        assertTrue(injectedJs.size >= 2,
            "loadEnd must inject at least the return channel and loadScene; got: $injectedJs")

        val channelIdx = injectedJs.indexOfFirst { it.contains(ExcalidrawJsBridge.RETURN_CHANNEL_FN) }
        val loadSceneIdx = injectedJs.indexOfFirst { it.contains("__excalidrawLoadScene__") }

        assertTrue(channelIdx >= 0,
            "injected JS must contain the return-channel definition (${ExcalidrawJsBridge.RETURN_CHANNEL_FN})")
        assertTrue(loadSceneIdx >= 0,
            "injected JS must contain the loadScene call")
        assertTrue(channelIdx < loadSceneIdx,
            "return channel must be installed before loadScene (channel at $channelIdx, loadScene at $loadSceneIdx)")

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // Structural invariants
    // -------------------------------------------------------------------------

    /**
     * ExcalidrawFileEditor must implement FileEditor.
     * Regression guard — ensures task-02-007 does not break the Phase-01 contract.
     */
    @Test
    fun `ExcalidrawFileEditor still implements FileEditor after task-02-007`() {
        val fileEditorClass = Class.forName("com.intellij.openapi.fileEditor.FileEditor")
        assertTrue(
            fileEditorClass.isAssignableFrom(ExcalidrawFileEditor::class.java),
            "ExcalidrawFileEditor must still implement FileEditor after task-02-007"
        )
    }

    /**
     * EDITOR_NAME constant must be non-blank.
     * Regression guard.
     */
    @Test
    fun `EDITOR_NAME constant is non-blank after task-02-007`() {
        assertTrue(
            ExcalidrawFileEditor.EDITOR_NAME.isNotBlank(),
            "EDITOR_NAME must remain non-blank after task-02-007"
        )
    }
}
