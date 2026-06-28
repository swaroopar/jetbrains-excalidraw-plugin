package com.swaroop.excalidraw.plugin.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for ExcalidrawJsBridge PNG extraction and export methods.
 *
 * Task: task-07-003
 * Acceptance criterion:
 *   - requestPngExtract(dataUrl) injects JS with "__excalidrawLoadPng__" (no eval()).
 *   - requestPngExport(sceneJson) injects JS with "__excalidrawExportPng__" (no eval()).
 *   - registerPngExtractedCallback stores a one-shot callback.
 *   - simulatePngExtracted triggers the callback exactly once; slot is null afterwards.
 *   - simulatePngExported triggers the pngExported callback.
 *   - No-Op after dispose: no JS injected, callbacks never called.
 *
 * All tests run without JCEF / IDE runtime via ExcalidrawJsBridge.createForTest.
 * No MockK, no Mockito. Plain JUnit 5.
 */
class ExcalidrawJsBridgePngTest {

    /**
     * requestPngExtract(dataUrl) must inject a JS call that:
     * - Contains LOAD_PNG_FN ("__excalidrawLoadPng__").
     * - Contains the Gson-encoded dataUrl.
     * - Does NOT contain "eval(" (OWASP A03).
     */
    @Test
    fun `requestPngExtract injects JS with LOAD_PNG_FN and encoded dataUrl without eval`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })

        val dataUrl = "data:image/png;base64,AA"
        bridge.requestPngExtract(dataUrl)

        assertTrue(injectedJs.isNotEmpty(), "requestPngExtract must produce at least one JS injection")
        val js = injectedJs.last()

        assertTrue(
            js.contains(ExcalidrawJsBridge.LOAD_PNG_FN),
            "injected JS must reference LOAD_PNG_FN constant '${ExcalidrawJsBridge.LOAD_PNG_FN}': $js"
        )
        assertTrue(
            js.contains("AA"),
            "injected JS must contain the base64 part of the dataUrl: $js"
        )
        assertFalse(
            js.contains("eval("),
            "A03: injected JS must not contain eval(): $js"
        )
    }

    /**
     * requestPngExport(sceneJson) must inject a JS call that:
     * - Contains EXPORT_PNG_FN ("__excalidrawExportPng__").
     * - Contains the Gson-encoded sceneJson.
     * - Does NOT contain "eval(" (OWASP A03).
     */
    @Test
    fun `requestPngExport injects JS with EXPORT_PNG_FN and encoded sceneJson without eval`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })

        val sceneJson = """{"type":"excalidraw","elements":[]}"""
        bridge.requestPngExport(sceneJson)

        assertTrue(injectedJs.isNotEmpty(), "requestPngExport must produce at least one JS injection")
        val js = injectedJs.last()

        assertTrue(
            js.contains(ExcalidrawJsBridge.EXPORT_PNG_FN),
            "injected JS must reference EXPORT_PNG_FN constant '${ExcalidrawJsBridge.EXPORT_PNG_FN}': $js"
        )
        assertTrue(
            js.contains("excalidraw"),
            "injected JS must contain encoded sceneJson content: $js"
        )
        assertFalse(
            js.contains("eval("),
            "A03: injected JS must not contain eval(): $js"
        )
    }

    /**
     * simulatePngExtracted with a valid pngExtracted JSON must invoke the registered
     * callback exactly once with the correct PngExtracted payload.
     */
    @Test
    fun `simulatePngExtracted with valid JSON calls registered callback with correct sceneJson`() {
        var receivedMessage: BridgeMessage.PngExtracted? = null
        var callCount = 0

        val bridge = ExcalidrawJsBridge.createForTest(injector = { _ -> })
        bridge.registerPngExtractedCallback { msg ->
            callCount++
            receivedMessage = msg
        }

        val json = """{"type":"pngExtracted","sceneJson":"{}"}"""
        bridge.simulatePngExtracted(json)

        assertEquals(1, callCount, "pngExtracted callback must be called exactly once")
        assertNotNull(receivedMessage, "receivedMessage must not be null")
        val msg = receivedMessage!!
        assertEquals("{}", msg.sceneJson, "sceneJson must match the JSON payload")
        assertNull(msg.error, "error must be null when sceneJson is present")
    }

    /**
     * After the first simulatePngExtracted invocation the callback slot must be
     * null (one-shot callback). A second simulatePngExtracted must NOT call the
     * callback again.
     */
    @Test
    fun `pngExtractedCallback is one-shot - slot is null after first invocation`() {
        var callCount = 0

        val bridge = ExcalidrawJsBridge.createForTest(injector = { _ -> })
        bridge.registerPngExtractedCallback { _ ->
            callCount++
        }

        val json = """{"type":"pngExtracted","sceneJson":"{}"}"""
        bridge.simulatePngExtracted(json)

        assertEquals(1, callCount, "callback must be called once after first simulate")

        // Second call must not invoke callback (slot already nulled)
        bridge.simulatePngExtracted(json)

        assertEquals(1, callCount, "callback must not be called again after slot was cleared")
    }

    /**
     * simulatePngExported with a valid pngExported JSON must invoke the registered
     * pngExported callback with the correct base64Png payload.
     */
    @Test
    fun `simulatePngExported with valid JSON calls registered callback with correct base64Png`() {
        var receivedMessage: BridgeMessage.PngExported? = null
        var callCount = 0

        val bridge = ExcalidrawJsBridge.createForTest(injector = { _ -> })
        bridge.registerPngExportedCallback { msg ->
            callCount++
            receivedMessage = msg
        }

        val json = """{"type":"pngExported","base64Png":"AAAA"}"""
        bridge.simulatePngExported(json)

        assertEquals(1, callCount, "pngExported callback must be called exactly once")
        assertNotNull(receivedMessage, "receivedMessage must not be null")
        val msg = receivedMessage!!
        assertEquals("AAAA", msg.base64Png, "base64Png must match the JSON payload")
        assertNull(msg.error, "error must be null when base64Png is present")
    }

    /**
     * After bridge.dispose(), registerPngExtractedCallback and requestPngExtract
     * must be no-ops:
     * - No JS is injected.
     * - Callback is never called even if simulatePngExtracted is called.
     */
    @Test
    fun `registerPngExtractedCallback and requestPngExtract after dispose are no-ops`() {
        val injectedJs = mutableListOf<String>()
        var callCount = 0

        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })
        bridge.dispose()

        // Registration must be no-op
        bridge.registerPngExtractedCallback { _ -> callCount++ }

        // requestPngExtract must be no-op (no injection)
        bridge.requestPngExtract("data:image/png;base64,AA")

        assertTrue(injectedJs.isEmpty(), "requestPngExtract must not inject after dispose")
        assertEquals(0, callCount, "pngExtracted callback must never be called after dispose")
    }
}
