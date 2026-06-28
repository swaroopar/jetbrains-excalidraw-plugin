package com.swaroop.excalidraw.plugin.theme

import com.intellij.openapi.util.Disposer
import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge
import com.swaroop.excalidraw.plugin.editor.ExcalidrawFileEditor
import com.swaroop.excalidraw.plugin.editor.StubVirtualFile
import com.swaroop.excalidraw.plugin.jcef.ExcalidrawJcefHost
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawPersistenceService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExcalidrawThemeController].
 *
 * Tasks: task-05-005, task-05-007
 * Acceptance criteria: AC-E4-01, AC-E4-02
 *
 * Strategy:
 * - Uses [ExcalidrawJsBridge.createForTest] with an injector lambda to capture
 *   all JS injections without JCEF.
 * - Injects a custom [themeProvider] lambda to control which theme value is
 *   returned without needing a real LafManager.
 * - Injects a [listenerRegistrar] lambda to capture the LafManagerListener
 *   callback reference, so tests can fire it manually (no Application message bus).
 * - No MockK, no Mockito.
 *
 * Timing contract (task-05-007):
 *   [ExcalidrawThemeController] no longer pushes in its init block.
 *   [pushCurrentTheme] must be called explicitly (by the editor's loadEnd callback)
 *   before the first theme injection appears in injectedJs.
 *
 * Security (A03): theme values are encoded via Gson inside the bridge, not here.
 */
class ExcalidrawThemeControllerTest {

    // -------------------------------------------------------------------------
    // AC-E4-01: pushCurrentTheme() sends the theme — constructor does NOT
    // -------------------------------------------------------------------------

    /**
     * After construction, no JS injection must have occurred yet.
     * After calling [ExcalidrawThemeController.pushCurrentTheme], exactly one
     * `__excalidrawSetTheme__` injection must be present.
     *
     * Acceptance criterion: constructor does NOT push; pushCurrentTheme() does.
     */
    @Test
    fun `constructor does not push theme pushCurrentTheme sends it exactly once`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })

        var capturedListener: (() -> Unit)? = null
        val controller = ExcalidrawThemeController(
            bridge = bridge,
            themeProvider = { "dark" },
            listenerRegistrar = { onThemeChanged -> capturedListener = onThemeChanged }
        )

        // Constructor must NOT push — __excalidrawSetTheme__ not yet defined in DOM
        assertTrue(
            injectedJs.isEmpty(),
            "Constructor must NOT call sendThemeUpdate before pushCurrentTheme(); got: $injectedJs"
        )

        // Explicit call simulates the loadEnd callback path
        controller.pushCurrentTheme()

        assertEquals(1, injectedJs.size,
            "pushCurrentTheme() must produce exactly one injection; got: $injectedJs")
        assertTrue(
            injectedJs[0].contains("__excalidrawSetTheme__"),
            "Injection must call __excalidrawSetTheme__; got: ${injectedJs[0]}"
        )
        assertTrue(
            injectedJs[0].contains("dark"),
            "Injection must contain the theme value 'dark'; got: ${injectedJs[0]}"
        )
    }

    // -------------------------------------------------------------------------
    // AC-E4-02: Live theme switch via listener callback (after pushCurrentTheme)
    // -------------------------------------------------------------------------

    /**
     * After [pushCurrentTheme] is called (ready=true), a LafManagerListener callback
     * must produce a second bridge call. injectedJs.size must be 2 after the callback.
     *
     * Acceptance criterion: injectedJs.size == 2 after listener trigger (post-ready)
     */
    @Test
    fun `listener callback after pushCurrentTheme pushes updated theme as second bridge call`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })

        var capturedListener: (() -> Unit)? = null
        var currentTheme = "light"

        val controller = ExcalidrawThemeController(
            bridge = bridge,
            themeProvider = { currentTheme },
            listenerRegistrar = { onThemeChanged -> capturedListener = onThemeChanged }
        )

        // Simulate loadEnd: push initial theme, mark ready
        controller.pushCurrentTheme()
        assertEquals(1, injectedJs.size, "Initial push must produce exactly one entry")

        // Simulate IDE theme switch
        currentTheme = "dark"
        capturedListener!!.invoke()

        assertEquals(2, injectedJs.size,
            "Listener callback must produce a second injection; got: $injectedJs")
        assertTrue(
            injectedJs[1].contains("__excalidrawSetTheme__"),
            "Second injection must call __excalidrawSetTheme__; got: ${injectedJs[1]}"
        )
        assertTrue(
            injectedJs[1].contains("dark"),
            "Second injection must contain updated theme 'dark'; got: ${injectedJs[1]}"
        )
    }

    /**
     * A LafManagerListener callback received BEFORE [pushCurrentTheme] must be
     * suppressed (ready-guard: page not loaded yet).
     *
     * Acceptance criterion: listener callback before pushCurrentTheme() is a no-op
     */
    @Test
    fun `listener callback before pushCurrentTheme is suppressed by ready guard`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })

        var capturedListener: (() -> Unit)? = null

        ExcalidrawThemeController(
            bridge = bridge,
            themeProvider = { "dark" },
            listenerRegistrar = { onThemeChanged -> capturedListener = onThemeChanged }
        )

        // Fire listener BEFORE pushCurrentTheme — must be suppressed
        capturedListener!!.invoke()
        assertTrue(
            injectedJs.isEmpty(),
            "Listener callback before pushCurrentTheme() must be suppressed; got: $injectedJs"
        )
    }

    // -------------------------------------------------------------------------
    // AC-E4-02: No bridge calls after dispose()
    // -------------------------------------------------------------------------

    /**
     * After [ExcalidrawThemeController.dispose], further listener callbacks
     * must not produce any more bridge calls.
     *
     * Acceptance criterion: injectedJs.size unchanged after dispose + callback
     */
    @Test
    fun `after dispose listener callback does not push any further theme update`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })

        var capturedListener: (() -> Unit)? = null

        val controller = ExcalidrawThemeController(
            bridge = bridge,
            themeProvider = { "light" },
            listenerRegistrar = { onThemeChanged -> capturedListener = onThemeChanged }
        )

        // Simulate loadEnd to set ready=true
        controller.pushCurrentTheme()
        assertEquals(1, injectedJs.size, "pushCurrentTheme() must push exactly once")

        controller.dispose()

        // Attempt to trigger listener after dispose — must be no-op
        capturedListener!!.invoke()

        assertEquals(1, injectedJs.size,
            "After dispose, listener callback must NOT produce any injection; got: $injectedJs")
    }

    // -------------------------------------------------------------------------
    // task-05-007: Editor–ThemeController integration (AC-E4-01, lifecycle)
    // -------------------------------------------------------------------------

    /**
     * AC-E4-01 (integration, timing): Before [ExcalidrawJcefHost.fireLoadEnd], no
     * `__excalidrawSetTheme__` call must appear in injectedJs. After [fireLoadEnd],
     * the call must be present.
     *
     * This verifies the loadEnd-timing contract: the initial theme push is wired
     * inside wireLoadEndCallback() — after installReturnChannel + loadScene — so
     * `window.__excalidrawSetTheme__` is guaranteed to exist (task-05-007).
     */
    @Test
    fun `initial sendThemeUpdate appears in injectedJs only after loadEnd fires`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })

        val themeController = ExcalidrawThemeController(
            bridge = bridge,
            themeProvider = { "dark" },
            listenerRegistrar = { _ -> /* no-op: no real message bus in tests */ }
        )

        val stubHost = ExcalidrawJcefHost.createForTest()
        val file = StubVirtualFile(
            "test.excalidraw",
            """{"type":"excalidraw","version":2,"source":"test","elements":[],"appState":{},"files":{}}""".toByteArray()
        )

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = bridge,
            persistenceService = ExcalidrawPersistenceService(),
            themeController = themeController
        )

        // BEFORE loadEnd: no __excalidrawSetTheme__ must have been injected
        assertFalse(
            injectedJs.any { it.contains("__excalidrawSetTheme__") },
            "Before loadEnd, injectedJs must NOT contain __excalidrawSetTheme__; got: $injectedJs"
        )

        // Trigger the loadEnd callback (simulates JCEF page-load complete)
        stubHost.fireLoadEnd()

        // AFTER loadEnd: exactly one __excalidrawSetTheme__ call must be present
        assertTrue(
            injectedJs.any { it.contains("__excalidrawSetTheme__") },
            "After loadEnd, injectedJs must contain __excalidrawSetTheme__; got: $injectedJs"
        )
        assertTrue(
            injectedJs.any { it.contains("dark") },
            "The theme call must contain the theme value 'dark'; got: $injectedJs"
        )

        editor.dispose()
    }

    /**
     * Ordering: the `__excalidrawSetTheme__` call must appear AFTER the
     * `__excalidrawLoadScene__` call in the injected JS sequence.
     *
     * This verifies that `window.__excalidrawSetTheme__` (registered in index.jsx
     * after render) is defined before the theme push arrives (AC-E4-01 timing).
     */
    @Test
    fun `theme push appears after loadScene in the injectedJs sequence`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })

        val themeController = ExcalidrawThemeController(
            bridge = bridge,
            themeProvider = { "light" },
            listenerRegistrar = { _ -> /* no-op */ }
        )

        val stubHost = ExcalidrawJcefHost.createForTest()
        val file = StubVirtualFile(
            "order.excalidraw",
            """{"type":"excalidraw","version":2,"source":"test","elements":[],"appState":{},"files":{}}""".toByteArray()
        )

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = bridge,
            persistenceService = ExcalidrawPersistenceService(),
            themeController = themeController
        )

        stubHost.fireLoadEnd()

        val loadSceneIdx = injectedJs.indexOfFirst { it.contains("__excalidrawLoadScene__") }
        val themeIdx = injectedJs.indexOfFirst { it.contains("__excalidrawSetTheme__") }

        assertTrue(loadSceneIdx >= 0, "loadScene call must be present; got: $injectedJs")
        assertTrue(themeIdx >= 0, "setTheme call must be present; got: $injectedJs")
        assertTrue(
            themeIdx > loadSceneIdx,
            "theme push must come AFTER loadScene (themeIdx=$themeIdx, loadSceneIdx=$loadSceneIdx)"
        )

        editor.dispose()
    }

    /**
     * Lifecycle binding: after [Disposer.dispose](editor), the [ExcalidrawThemeController]
     * registered via [Disposer.register] must be disposed (Disposer.isDisposed returns true).
     *
     * Uses [Disposer.dispose] rather than calling [ExcalidrawFileEditor.dispose] directly
     * so the full Disposer tree traversal runs and child Disposables are notified.
     *
     * This ensures no listener leak: the controller's disposed flag is set, so any
     * subsequent LafManagerListener callbacks become no-ops (AC-E4-02, leak-free).
     */
    @Test
    fun `editor dispose causes Disposer to dispose the registered ThemeController`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })

        // Capture the listener to verify no further calls after dispose
        var capturedListener: (() -> Unit)? = null
        val themeController = ExcalidrawThemeController(
            bridge = bridge,
            themeProvider = { "light" },
            listenerRegistrar = { onThemeChanged -> capturedListener = onThemeChanged }
        )

        val stubHost = ExcalidrawJcefHost.createForTest()
        val file = StubVirtualFile(
            "lifecycle.excalidraw",
            """{"type":"excalidraw","version":2,"source":"test","elements":[],"appState":{},"files":{}}""".toByteArray()
        )

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = stubHost,
            bridge = bridge,
            persistenceService = ExcalidrawPersistenceService(),
            themeController = themeController
        )

        // Trigger loadEnd so pushCurrentTheme() is called (sets ready=true)
        stubHost.fireLoadEnd()

        // Record injection count after loadEnd
        val countAfterLoadEnd = injectedJs.size

        // Dispose the editor via Disposer so the full child-disposal chain runs.
        Disposer.dispose(editor)

        // After disposal, Disposer.isDisposed must return true for the controller
        // (Disposer.register registered it as a child of the editor).
        assertTrue(
            Disposer.isDisposed(themeController),
            "ThemeController must be disposed after Disposer.dispose(editor) via Disposer chain"
        )

        // No further bridge injections must occur after dispose (listener becomes no-op).
        capturedListener?.invoke()
        assertEquals(
            countAfterLoadEnd, injectedJs.size,
            "After dispose, listener callback must NOT produce any new injection; got: $injectedJs"
        )
    }
}
