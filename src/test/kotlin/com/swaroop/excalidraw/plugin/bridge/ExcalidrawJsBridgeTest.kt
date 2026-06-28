package com.swaroop.excalidraw.plugin.bridge

import com.google.gson.JsonParser
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawScene
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for ExcalidrawJsBridge.
 *
 * Strategy: ExcalidrawJsBridge.createForTest(injector, readyHandler) allows
 * injection of lambda stubs so we can assert the JS call without touching the
 * real JBCefBrowser / JCEF runtime.
 *
 * Security assertions (A03):
 * - The injected JS must NOT contain eval().
 * - The JSON payload must be embedded as a JSON string literal in a known
 *   function call — not built via unguarded string concatenation at runtime.
 * - The payload must contain type="loadScene" and a scene object.
 */
class ExcalidrawJsBridgeTest {

    private fun fixtureScene(): ExcalidrawScene = ExcalidrawScene(
        type = "excalidraw",
        version = 2,
        source = "https://excalidraw.com",
        elements = listOf(mapOf("id" to "elem-1", "type" to "rectangle")),
        appState = mapOf("viewBackgroundColor" to "#ffffff"),
        files = null
    )

    /**
     * loadScene() must produce a JS injection call that contains the correct
     * JSON payload with type="loadScene" and the scene object.
     */
    @Test
    fun `loadScene injects JS call containing loadScene type in JSON payload`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js: String -> injectedJs.add(js) })

        bridge.loadScene(fixtureScene())

        assertTrue(injectedJs.isNotEmpty(), "loadScene must produce at least one JS injection")
        val js = injectedJs.last()

        // A03: no raw eval
        assertTrue(!js.contains("eval("), "A03: injected JS must not contain eval()")

        // The injected call must embed a JSON object — extract it.
        // Gson encodes the JSON payload as a double-quoted string literal with
        // inner quotes escaped as \".  We locate the JSON object by finding the
        // first '{' and matching up to the final '}'.
        val braceStart = js.indexOf('{')
        val braceEnd = js.lastIndexOf('}')
        assertTrue(braceStart >= 0 && braceEnd > braceStart,
            "injected JS must embed a JSON object literal in the call: $js")

        // The slice between braces is the Gson-escaped JSON; unescape \" → "
        val rawEscaped = js.substring(braceStart, braceEnd + 1)
        val raw = rawEscaped.replace("\\\"", "\"")
        val obj = JsonParser.parseString(raw).asJsonObject
        assertEquals("loadScene", obj.get("type").asString)
        assertTrue(obj.has("scene"), "JSON payload must contain scene object")

        val scene = obj.getAsJsonObject("scene")
        assertTrue(scene.has("elements"))
        assertTrue(scene.has("appState"))
    }

    /**
     * The injected JS must reference a named window function, not a bare eval.
     */
    @Test
    fun `loadScene injected JS references named window function not eval`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js: String -> injectedJs.add(js) })

        bridge.loadScene(fixtureScene())

        assertTrue(injectedJs.isNotEmpty())
        val js = injectedJs.last()
        assertTrue(!js.trim().startsWith("eval("), "A03: must not start with eval()")
        assertTrue(
            js.contains("__excalidrawLoadScene__") || js.contains("loadScene"),
            "injected JS must call a named bridge function"
        )
    }

    /**
     * After dispose(), loadScene() must be a no-op (no injection performed).
     */
    @Test
    fun `loadScene after dispose is a no-op`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js: String -> injectedJs.add(js) })

        bridge.dispose()
        bridge.loadScene(fixtureScene())

        assertTrue(injectedJs.isEmpty(), "loadScene must not inject after dispose")
    }

    /**
     * The JS→Kotlin handler registered at construction time must process
     * the "ready" signal string and not throw.
     */
    @Test
    fun `ready handler is registered and processes ready signal without error`() {
        var receivedMessage: String? = null
        val bridge = ExcalidrawJsBridge.createForTest(
            injector = { _: String -> },
            readyHandler = { msg: String -> receivedMessage = msg }
        )

        bridge.simulateReadySignal("ready")

        assertEquals("ready", receivedMessage, "ready handler must receive the ready signal")
    }

    // -------------------------------------------------------------------------
    // Scene-change handler tests (task-03-003)
    // -------------------------------------------------------------------------

    /**
     * simulateSceneChange with a valid sceneChange JSON must invoke the injected
     * sceneChangeHandler exactly once with the correct SceneChangeMessage.
     */
    @Test
    fun `simulateSceneChange with valid JSON calls sceneChangeHandler exactly once`() {
        var callCount = 0
        var receivedScene: SceneChangeMessage? = null

        val bridge = ExcalidrawJsBridge.createForTest(
            injector = { _: String -> },
            sceneChangeHandler = { scene: SceneChangeMessage ->
                callCount++
                receivedScene = scene
            }
        )

        val validJson = """{"type":"sceneChange","elements":[{"type":"rectangle"}],"appState":{"viewBackgroundColor":"#ffffff"}}"""
        bridge.simulateSceneChange(validJson)

        assertEquals(1, callCount, "sceneChangeHandler must be called exactly once")
        assertNotNull(receivedScene, "receivedScene must not be null")
        assertEquals(1, receivedScene!!.elements.size(), "elements must contain 1 item")
    }

    /**
     * simulateSceneChange with invalid JSON must not invoke the handler and must
     * not throw.
     */
    @Test
    fun `simulateSceneChange with invalid JSON does not call handler and does not crash`() {
        var callCount = 0
        val bridge = ExcalidrawJsBridge.createForTest(
            injector = { _: String -> },
            sceneChangeHandler = { _: SceneChangeMessage -> callCount++ }
        )

        bridge.simulateSceneChange("not-valid-json{{{")

        assertEquals(0, callCount, "sceneChangeHandler must not be called for invalid JSON")
    }

    /**
     * After bridge.dispose(), simulateSceneChange must be a no-op (handler not called).
     */
    @Test
    fun `simulateSceneChange after dispose is a no-op`() {
        var callCount = 0
        val bridge = ExcalidrawJsBridge.createForTest(
            injector = { _: String -> },
            sceneChangeHandler = { _: SceneChangeMessage -> callCount++ }
        )

        bridge.dispose()
        val validJson = """{"type":"sceneChange","elements":[],"appState":{}}"""
        bridge.simulateSceneChange(validJson)

        assertEquals(0, callCount, "sceneChangeHandler must not be called after dispose")
    }

    // -------------------------------------------------------------------------
    // Return-channel installation tests (task-03-005)
    // -------------------------------------------------------------------------

    /**
     * installReturnChannel() must inject a JS snippet that defines
     * window.__excalidrawPostToKotlin__ (the stable JS→Kotlin return channel).
     *
     * The snippet must:
     * - Reference [ExcalidrawJsBridge.RETURN_CHANNEL_FN] as the window property name.
     * - Contain a function body that includes the jsQueryInject expression
     *   (in test mode this is the stub placeholder "__jsBridgeStub__").
     * - NOT contain eval().
     */
    @Test
    fun `installReturnChannel injects JS defining the stable return-channel function`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(
            injector = { js: String -> injectedJs.add(js) }
        )

        bridge.installReturnChannel()

        assertTrue(injectedJs.isNotEmpty(), "installReturnChannel must produce at least one JS injection")
        val js = injectedJs.last()

        // Must define the stable named function on window
        assertTrue(
            js.contains(ExcalidrawJsBridge.RETURN_CHANNEL_FN),
            "injected JS must reference the RETURN_CHANNEL_FN constant: $js"
        )
        // Must be a function assignment (not a bare call)
        assertTrue(
            js.contains("function"),
            "injected JS must contain a function definition: $js"
        )
        // Must contain the stub inject expression (test mode uses __jsBridgeStub__)
        assertTrue(
            js.contains("__jsBridgeStub__"),
            "injected JS must contain the jsQueryInject stub expression: $js"
        )
        // A03: no raw eval
        assertTrue(!js.contains("eval("), "A03: installReturnChannel JS must not contain eval()")
    }

    /**
     * installReturnChannel() after dispose() must be a no-op.
     */
    @Test
    fun `installReturnChannel after dispose is a no-op`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(
            injector = { js: String -> injectedJs.add(js) }
        )

        bridge.dispose()
        bridge.installReturnChannel()

        assertTrue(injectedJs.isEmpty(), "installReturnChannel must not inject after dispose")
    }
}
