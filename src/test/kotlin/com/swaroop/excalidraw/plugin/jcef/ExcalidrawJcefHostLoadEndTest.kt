package com.swaroop.excalidraw.plugin.jcef

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExcalidrawJcefHost.addLoadEndListener].
 *
 * JCEF runtime is not available in unit tests (no live IDE instance), so these tests drive
 * the host through a [FakeCefBrowserHandle] passed to [ExcalidrawJcefHost.createForTest] —
 * simulating `onLoadEnd`/`onLoadError` exactly as [JBCefBrowserHandle] would forward them
 * from a real browser, with no reflection into the host's private state.
 *
 * Scenarios covered:
 *   1. Registering a listener and simulating loadEnd invokes it exactly once.
 *   2. A second simulated loadEnd does not re-invoke it (fire-once guard).
 *   3. A scheme-not-ready retry (simulated load error) re-arms the guard so the retry's
 *      own loadEnd fires the listener again.
 *   4. After dispose(), simulating loadEnd does NOT invoke the listener.
 */
class ExcalidrawJcefHostLoadEndTest {

    // ---------------------------------------------------------------------------
    // Scenario 1 — Listener fires exactly once on simulated loadEnd
    // ---------------------------------------------------------------------------

    @Test
    fun `registered listener is invoked exactly once on simulated loadEnd`() {
        val fakeHandle = FakeCefBrowserHandle()
        val host = ExcalidrawJcefHost.createForTest(fakeHandle)
        var callCount = 0
        host.addLoadEndListener { callCount++ }

        fakeHandle.simulateLoadEnd()

        assertEquals(1, callCount, "Listener must be invoked exactly once on loadEnd")
    }

    @Test
    fun `listener is NOT invoked more than once when loadEnd fires multiple times`() {
        val fakeHandle = FakeCefBrowserHandle()
        val host = ExcalidrawJcefHost.createForTest(fakeHandle)
        var callCount = 0
        host.addLoadEndListener { callCount++ }

        fakeHandle.simulateLoadEnd()
        fakeHandle.simulateLoadEnd() // second fire must not re-invoke

        assertEquals(1, callCount, "Listener must fire at most once, even if loadEnd fires multiple times")
    }

    // ---------------------------------------------------------------------------
    // Scenario 2 — Scheme-not-ready retry re-arms the fire-once guard
    // ---------------------------------------------------------------------------

    @Test
    fun `a scheme-not-ready retry re-arms the listener so the reload's loadEnd re-fires`() {
        // Models the scheme-not-ready startup race end to end, through the same
        // CefBrowserHandle interface production uses: the initial load fails
        // (ERR_UNKNOWN_URL_SCHEME-style error, already having fired loadEnd for its error
        // page), which schedules a retry reload; that reload's own loadEnd must re-fire the
        // registered listener — otherwise the first restored editor on IDE restart renders
        // empty. FakeCefBrowserHandle.scheduleReload runs immediately, so this is synchronous.
        val fakeHandle = FakeCefBrowserHandle()
        val host = ExcalidrawJcefHost.createForTest(fakeHandle)
        var callCount = 0
        host.addLoadEndListener { callCount++ }

        fakeHandle.simulateLoadEnd() // error page's own loadEnd
        assertEquals(1, callCount)
        fakeHandle.simulateLoadEnd() // still no-op (once-only guard)
        assertEquals(1, callCount)

        fakeHandle.simulateLoadError(isMainFrame = true, failedUrl = ExcalidrawJcefHost.START_URL)
        assertTrue(
            fakeHandle.loadedUrls.isNotEmpty(),
            "A retry-worthy load error must reload the start URL: ${fakeHandle.loadedUrls}"
        )
        fakeHandle.simulateLoadEnd() // the reload's own loadEnd

        assertEquals(2, callCount, "Listener must fire again after the scheme-retry re-arms it")
    }

    @Test
    fun `a non-retry-worthy load error does not reload or re-arm`() {
        val fakeHandle = FakeCefBrowserHandle()
        val host = ExcalidrawJcefHost.createForTest(fakeHandle)
        var callCount = 0
        host.addLoadEndListener { callCount++ }

        fakeHandle.simulateLoadEnd()
        assertEquals(1, callCount)

        // Not the excalidraw:// scheme — shouldRetrySchemeLoad returns false.
        fakeHandle.simulateLoadError(isMainFrame = true, failedUrl = "https://example.com/")
        assertTrue(fakeHandle.loadedUrls.isEmpty(), "A non-scheme load error must not trigger a reload")

        fakeHandle.simulateLoadEnd()
        assertEquals(1, callCount, "Without a retry, the fire-once guard must stay tripped")
    }

    // ---------------------------------------------------------------------------
    // Scenario 3 — No callback after dispose()
    // ---------------------------------------------------------------------------

    @Test
    fun `listener does NOT fire after dispose()`() {
        val fakeHandle = FakeCefBrowserHandle()
        val host = ExcalidrawJcefHost.createForTest(fakeHandle)
        var callCount = 0
        host.addLoadEndListener { callCount++ }

        host.dispose() // dispose without touching a real browser (FakeCefBrowserHandle)
        assertTrue(fakeHandle.disposed, "dispose() must delegate to the CefBrowserHandle")

        fakeHandle.simulateLoadEnd()

        assertEquals(0, callCount, "Listener must NOT fire after dispose()")
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
