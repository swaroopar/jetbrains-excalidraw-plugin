package com.swaroop.excalidraw.plugin.jcef

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Field

/**
 * Unit tests for [ExcalidrawJcefHost.addLoadEndListener].
 *
 * JCEF runtime is not available in unit tests (no live IDE instance), so these tests
 * verify the listener-management logic directly via reflection — without instantiating
 * a real [com.intellij.ui.jcef.JBCefBrowser].
 *
 * Scenarios covered:
 *   1. Registering a listener stores it inside the host (verifiable via reflection).
 *   2. Simulating onLoadEnd invokes the listener exactly once.
 *   3. After dispose(), simulating onLoadEnd does NOT invoke the listener.
 */
class ExcalidrawJcefHostLoadEndTest {

    // ---------------------------------------------------------------------------
    // Helpers — access internal state via reflection (no live JCEF needed)
    // ---------------------------------------------------------------------------

    /**
     * Reads the private [ExcalidrawJcefHost.loadEndListeners] field via reflection.
     * Returns the list (may be empty, must not be null).
     */
    @Suppress("UNCHECKED_CAST")
    private fun getListeners(host: ExcalidrawJcefHost): List<() -> Unit> {
        val field: Field = ExcalidrawJcefHost::class.java
            .getDeclaredField("loadEndListeners")
        field.isAccessible = true
        return field.get(host) as List<() -> Unit>
    }

    /**
     * Reads the private [ExcalidrawJcefHost.disposed] flag via reflection.
     */
    private fun isDisposed(host: ExcalidrawJcefHost): Boolean {
        val field: Field = ExcalidrawJcefHost::class.java
            .getDeclaredField("disposed")
        field.isAccessible = true
        return field.get(host) as Boolean
    }

    /**
     * Calls the internal [ExcalidrawJcefHost.fireLoadEnd] method via reflection,
     * simulating a JCEF onLoadEnd event without a real browser.
     *
     * Kotlin's `internal` visibility mangles the method name in the JVM bytecode to
     * prevent accidental access from other modules. The public method takes zero
     * parameters; we locate it by prefix and zero parameter count to distinguish it
     * from Kotlin-generated lambda helper methods (which carry extra arguments).
     */
    private fun simulateLoadEnd(host: ExcalidrawJcefHost) {
        val method = ExcalidrawJcefHost::class.java.declaredMethods
            .first { it.name.startsWith("fireLoadEnd") && it.parameterCount == 0 }
        method.isAccessible = true
        method.invoke(host)
    }

    // ---------------------------------------------------------------------------
    // Scenario 1 — Listener is stored after addLoadEndListener
    // ---------------------------------------------------------------------------

    @Test
    fun `addLoadEndListener stores the listener in the host`() {
        val host = ExcalidrawJcefHost.createForTest()
        val listener: () -> Unit = {}

        host.addLoadEndListener(listener)

        val listeners = getListeners(host)
        assertNotNull(listeners, "loadEndListeners must not be null")
        assertTrue(listeners.contains(listener), "Listener must be stored in loadEndListeners after registration")
    }

    // ---------------------------------------------------------------------------
    // Scenario 2 — Listener fires exactly once on simulated onLoadEnd
    // ---------------------------------------------------------------------------

    @Test
    fun `registered listener is invoked exactly once on simulated loadEnd`() {
        val host = ExcalidrawJcefHost.createForTest()
        var callCount = 0
        host.addLoadEndListener { callCount++ }

        // Simulate onLoadEnd being fired by the JCEF engine.
        // fireLoadEnd is internal but testable via reflection.
        simulateLoadEnd(host)

        assertEquals(1, callCount, "Listener must be invoked exactly once on loadEnd")
    }

    @Test
    fun `listener is NOT invoked more than once when loadEnd fires multiple times`() {
        val host = ExcalidrawJcefHost.createForTest()
        var callCount = 0
        host.addLoadEndListener { callCount++ }

        simulateLoadEnd(host)
        simulateLoadEnd(host) // second fire must not re-invoke

        assertEquals(1, callCount, "Listener must fire at most once, even if onLoadEnd fires multiple times")
    }

    // ---------------------------------------------------------------------------
    // Scenario 3 — No callback after dispose()
    // ---------------------------------------------------------------------------

    @Test
    fun `listener does NOT fire after dispose()`() {
        val host = ExcalidrawJcefHost.createForTest()
        var callCount = 0
        host.addLoadEndListener { callCount++ }

        host.disposeForTest() // dispose without touching real JBCefBrowser

        simulateLoadEnd(host)

        assertEquals(0, callCount, "Listener must NOT fire after dispose()")
    }

    @Test
    fun `disposed flag is true after disposeForTest()`() {
        val host = ExcalidrawJcefHost.createForTest()
        host.disposeForTest()
        assertTrue(isDisposed(host), "disposed flag must be true after dispose()")
    }

    // ---------------------------------------------------------------------------
    // Backward-compatibility guard — constants and Disposable contract still hold
    // ---------------------------------------------------------------------------

    @Test
    fun `START_URL constant is unchanged after task-02-005 extension`() {
        assertEquals("excalidraw://app/index.html", ExcalidrawJcefHost.START_URL)
    }

    @Test
    fun `SCHEME constant is unchanged after task-02-005 extension`() {
        assertEquals("excalidraw", ExcalidrawJcefHost.SCHEME)
    }

    @Test
    fun `ExcalidrawJcefHost still implements Disposable after task-02-005 extension`() {
        val disposableClass = Class.forName("com.intellij.openapi.Disposable")
        assertTrue(
            disposableClass.isAssignableFrom(ExcalidrawJcefHost::class.java),
            "ExcalidrawJcefHost must still implement Disposable (AD-3)"
        )
    }
}
