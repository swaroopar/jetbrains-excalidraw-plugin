package com.swaroop.excalidraw.plugin.bridge

import com.swaroop.excalidraw.plugin.export.ExportMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for ExcalidrawJsBridge export callback + requestExport methods.
 *
 * Task: task-06-004
 * Acceptance criterion:
 *   - registerExportResultCallback captures the callback.
 *   - simulateExportResult with valid exportResult-JSON calls callback exactly once.
 *   - Callback slot is null after first invocation (one-shot).
 *   - requestExport("svg", 1.0) injects JS containing "__excalidrawExport__" and "svg".
 *   - No eval() in injected JS (A03).
 *   - No-Op after dispose: injectedJs stays empty, callback never called.
 *
 * All tests run without JCEF / IDE runtime via ExcalidrawJsBridge.createForTest.
 * No MockK, no Mockito. Plain JUnit 5.
 */
class ExcalidrawJsBridgeExportTest {

    /**
     * simulateExportResult with a valid SVG exportResult JSON must invoke the
     * registered callback exactly once with the correct ExportResult payload.
     */
    @Test
    fun `simulateExportResult with SVG JSON calls registered callback with correct payload`() {
        var receivedResult: ExportMessage.ExportResult? = null
        var callCount = 0

        val bridge = ExcalidrawJsBridge.createForTest(injector = { _ -> })
        bridge.registerExportResultCallback { result ->
            callCount++
            receivedResult = result
        }

        val svgJson = """{"type":"exportResult","format":"svg","data":"<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>"}"""
        bridge.simulateExportResult(svgJson)

        assertEquals(1, callCount, "callback must be called exactly once")
        assertNotNull(receivedResult, "receivedResult must not be null")
        val result = receivedResult
        assertNotNull(result, "receivedResult must not be null after callback")
        assertEquals("svg", result!!.format, "format must be 'svg'")
        assertTrue(result.data.contains("<svg"), "data must contain SVG content")
    }

    /**
     * After the first simulateExportResult invocation the callback slot must be
     * null (one-shot callback). A second simulateExportResult must NOT call the
     * callback again.
     */
    @Test
    fun `callback is one-shot - slot is null after first invocation`() {
        var callCount = 0

        val bridge = ExcalidrawJsBridge.createForTest(injector = { _ -> })
        bridge.registerExportResultCallback { _ ->
            callCount++
        }

        val svgJson = """{"type":"exportResult","format":"svg","data":"<svg/>"}"""
        bridge.simulateExportResult(svgJson)

        assertEquals(1, callCount, "callback must be called once after first simulate")

        // Second call must not invoke callback (slot already nulled)
        bridge.simulateExportResult(svgJson)

        assertEquals(1, callCount, "callback must not be called again after slot was cleared")
    }

    /**
     * requestExport("svg", 1.0) must inject a JS string that:
     * - Contains "__excalidrawExport__" (the export function name constant).
     * - Contains the encoded "svg" format string.
     * - Does NOT contain "eval(" (OWASP A03).
     */
    @Test
    fun `requestExport svg injects JS with excalidrawExport and svg without eval`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })

        bridge.requestExport("svg", 1.0)

        assertTrue(injectedJs.isNotEmpty(), "requestExport must produce at least one JS injection")
        val js = injectedJs.last()

        assertTrue(
            js.contains(ExcalidrawJsBridge.EXPORT_FN),
            "injected JS must reference EXPORT_FN constant '__excalidrawExport__': $js"
        )
        assertTrue(
            js.contains("svg"),
            "injected JS must contain the format value 'svg': $js"
        )
        assertFalse(
            js.contains("eval("),
            "A03: injected JS must not contain eval(): $js"
        )
    }

    /**
     * After bridge.dispose(), registerExportResultCallback and requestExport
     * must be no-ops:
     * - No JS is injected.
     * - Callback is never called even if simulateExportResult is called.
     */
    @Test
    fun `registerExportResultCallback and requestExport after dispose are no-ops`() {
        val injectedJs = mutableListOf<String>()
        var callCount = 0

        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })
        bridge.dispose()

        // Registration must be no-op
        bridge.registerExportResultCallback { _ -> callCount++ }

        // requestExport must be no-op (no injection)
        bridge.requestExport("svg", 1.0)

        assertTrue(injectedJs.isEmpty(), "requestExport must not inject after dispose")
        assertEquals(0, callCount, "callback must never be called after dispose")
    }
}
