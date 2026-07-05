package com.swaroop.excalidraw.plugin.bridge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the clipboard bridge injected by [ExcalidrawJsBridge.installClipboardBridge].
 *
 * These assert the JS-injection *contract* (deterministic, no JCEF/AWT runtime): the shim
 * must replace navigator.clipboard and route reads/writes through the named bridge function.
 * The Kotlin-side [ExcalidrawJsBridge.handleClipboardRequest] round-trip is exercised at
 * runtime in the IDE (it touches the real system clipboard + a JBCefJSQuery Response, neither
 * of which is meaningful in a headless unit-test JVM).
 */
class ExcalidrawJsBridgeClipboardTest {

    @Test
    fun `installClipboardBridge injects navigator_clipboard shim routed through the bridge fn`() {
        val injected = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { injected.add(it) })

        bridge.installClipboardBridge()

        assertTrue(injected.isNotEmpty(), "installClipboardBridge must inject JS")
        val js = injected.last()

        // A03: no eval / Function-constructor in the injected code.
        assertFalse(js.contains("eval("), "A03: injected JS must not contain eval()")
        assertFalse(js.contains("Function("), "A03: injected JS must not contain Function()")

        // Defines the named bridge function and overrides navigator.clipboard.
        assertTrue(js.contains(ExcalidrawJsBridge.CLIPBOARD_FN), "must define the clipboard bridge fn")
        assertTrue(
            js.contains("navigator") && js.contains("clipboard"),
            "must replace/patch navigator.clipboard"
        )

        // Overrides the four methods Excalidraw uses for copy/paste.
        for (method in listOf("readText", "writeText", "read", "write")) {
            assertTrue(js.contains(method), "shim must override navigator.clipboard.$method")
        }

        // Uses the readText/writeText ops and guards against double installation.
        assertTrue(js.contains("readText") && js.contains("writeText"), "must use readText/writeText ops")
        assertTrue(
            js.contains("__excalidrawClipboardInstalled__"),
            "must guard against installing the shim twice"
        )
    }

    @Test
    fun `installClipboardBridge is a no-op after dispose`() {
        val injected = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { injected.add(it) })

        bridge.dispose()
        bridge.installClipboardBridge()

        assertTrue(injected.isEmpty(), "no JS may be injected after dispose()")
    }
}
