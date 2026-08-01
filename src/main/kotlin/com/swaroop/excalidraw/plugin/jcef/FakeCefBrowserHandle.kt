package com.swaroop.excalidraw.plugin.jcef

/**
 * Test [CefBrowserHandle]: records [loadedUrls] instead of navigating a real browser, runs
 * [scheduleReload] immediately instead of waiting, and lets tests trigger [simulateLoadEnd] /
 * [simulateLoadError] directly.
 *
 * Passed to [ExcalidrawJcefHost.createForTest] so load-lifecycle tests (fire-once guard,
 * scheme-reload retry) drive the host through the same interface production uses — no
 * reflection into its private state.
 */
class FakeCefBrowserHandle : CefBrowserHandle {

    /** URLs passed to [loadUrl], in call order. */
    val loadedUrls: MutableList<String> = mutableListOf()

    /** True once [dispose] has been called. */
    var disposed: Boolean = false
        private set

    private var loadEndCallback: () -> Unit = {}
    private var loadErrorCallback: (isMainFrame: Boolean, failedUrl: String?) -> Unit = { _, _ -> }

    override fun loadUrl(url: String) {
        loadedUrls.add(url)
    }

    override fun dispose() {
        disposed = true
    }

    override fun onLoadEnd(callback: () -> Unit) {
        loadEndCallback = callback
    }

    override fun onLoadError(callback: (isMainFrame: Boolean, failedUrl: String?) -> Unit) {
        loadErrorCallback = callback
    }

    override fun scheduleReload(delayMs: Long, action: () -> Unit) {
        // Tests need determinism, not real timing — run immediately.
        action()
    }

    /** Simulates the browser's main frame finishing loading. */
    fun simulateLoadEnd() {
        loadEndCallback()
    }

    /** Simulates a frame failing to load. */
    fun simulateLoadError(isMainFrame: Boolean, failedUrl: String?) {
        loadErrorCallback(isMainFrame, failedUrl)
    }
}
