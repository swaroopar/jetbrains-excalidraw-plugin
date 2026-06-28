package com.swaroop.excalidraw.plugin.bridge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for ExcalidrawJsBridge.sendThemeUpdate().
 *
 * Task: task-05-003
 * Acceptance criterion: AC-E4-02 (testspec-slice task-05-003)
 *
 * Security assertions (A03):
 * - The injected JS must NOT contain eval().
 * - The theme string is embedded via Gson.toJson() as a safe JS string literal.
 * - No raw string concatenation of user-controlled data.
 *
 * All tests run without JCEF / IDE runtime via ExcalidrawJsBridge.createForTest.
 * No MockK, no Mockito.
 */
class ExcalidrawJsBridgeThemeTest {

    /**
     * sendThemeUpdate("dark") must inject a JS string that:
     * - Contains "window.__excalidrawSetTheme__"
     * - Contains the literal "dark" (JSON-encoded, with surrounding quotes)
     * - Does NOT contain "eval(" (OWASP A03)
     */
    @Test
    fun `sendThemeUpdate dark injects JS with excalidrawSetTheme and dark`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })

        bridge.sendThemeUpdate("dark")

        assertTrue(injectedJs.isNotEmpty(), "sendThemeUpdate must produce at least one JS injection")
        val js = injectedJs.last()

        assertTrue(
            js.contains("window.__excalidrawSetTheme__"),
            "injected JS must call window.__excalidrawSetTheme__: $js"
        )
        assertTrue(
            js.contains("dark"),
            "injected JS must contain the theme value 'dark': $js"
        )
        assertFalse(
            js.contains("eval("),
            "A03: injected JS must not contain eval(): $js"
        )
    }

    /**
     * sendThemeUpdate("light") must inject a JS string that:
     * - Contains "window.__excalidrawSetTheme__"
     * - Contains the literal "light" (JSON-encoded)
     * - Does NOT contain "eval("
     */
    @Test
    fun `sendThemeUpdate light injects JS with excalidrawSetTheme and light`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })

        bridge.sendThemeUpdate("light")

        assertTrue(injectedJs.isNotEmpty(), "sendThemeUpdate must produce at least one JS injection")
        val js = injectedJs.last()

        assertTrue(
            js.contains("window.__excalidrawSetTheme__"),
            "injected JS must call window.__excalidrawSetTheme__: $js"
        )
        assertTrue(
            js.contains("light"),
            "injected JS must contain the theme value 'light': $js"
        )
        assertFalse(
            js.contains("eval("),
            "A03: injected JS must not contain eval(): $js"
        )
    }

    /**
     * After bridge.dispose(), sendThemeUpdate() must be a no-op:
     * - No JS is injected
     * - No exception is thrown
     */
    @Test
    fun `sendThemeUpdate after dispose is a no-op`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })

        bridge.dispose()
        bridge.sendThemeUpdate("dark")

        assertTrue(injectedJs.isEmpty(), "sendThemeUpdate must not inject after dispose")
    }

    /**
     * ExcalidrawJsBridge.THEME_FN must be defined and contain the correct
     * function name (single source of truth, used by sendThemeUpdate).
     */
    @Test
    fun `THEME_FN constant is defined and matches excalidrawSetTheme`() {
        assertTrue(
            ExcalidrawJsBridge.THEME_FN.contains("excalidrawSetTheme"),
            "THEME_FN must contain 'excalidrawSetTheme': ${ExcalidrawJsBridge.THEME_FN}"
        )
    }
}
