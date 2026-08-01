package com.swaroop.excalidraw.plugin.jcef

import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.concurrency.EdtScheduledExecutorService
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.util.concurrent.TimeUnit

/**
 * Seam for the browser operations [ExcalidrawJcefHost]'s load lifecycle depends on:
 * navigating, disposing, reacting to the main frame finishing or failing to load, and
 * scheduling the scheme-not-ready retry reload.
 *
 * [JBCefBrowserHandle] is the real JCEF-backed adapter (production); [FakeCefBrowserHandle]
 * is a scriptable fake used by unit tests. Driving [ExcalidrawJcefHost] through this
 * interface is what lets its load-lifecycle tests exercise `onLoadEnd`/`onLoadError`
 * directly instead of reaching past the class via reflection.
 */
interface CefBrowserHandle {
    /** Navigates the browser to [url]. */
    fun loadUrl(url: String)

    /** Disposes the underlying browser resources. */
    fun dispose()

    /** Registers [callback] to run whenever the browser's main frame finishes loading. */
    fun onLoadEnd(callback: () -> Unit)

    /**
     * Registers [callback] to run whenever a frame fails to load, with whether it was
     * the main frame and the URL that failed to load.
     */
    fun onLoadError(callback: (isMainFrame: Boolean, failedUrl: String?) -> Unit)

    /**
     * Runs [action] after [delayMs] — used only for the scheme-not-ready retry reload
     * (see [ExcalidrawJcefHost.shouldRetrySchemeLoad]). [JBCefBrowserHandle] schedules it
     * on the EDT via [EdtScheduledExecutorService]; [FakeCefBrowserHandle] runs it
     * immediately so tests stay synchronous and deterministic.
     */
    fun scheduleReload(delayMs: Long, action: () -> Unit)
}

/**
 * Production [CefBrowserHandle], backed by a real [JBCefBrowser].
 *
 * Registers a single [CefLoadHandlerAdapter] at construction time and forwards its
 * main-frame `onLoadEnd`/`onLoadError` events to whatever callbacks [ExcalidrawJcefHost]
 * has registered via [onLoadEnd]/[onLoadError] — mirroring the load-lifecycle wiring that
 * used to live directly on [ExcalidrawJcefHost] itself.
 */
internal class JBCefBrowserHandle(private val browser: JBCefBrowser) : CefBrowserHandle {

    private var loadEndCallback: () -> Unit = {}
    private var loadErrorCallback: (isMainFrame: Boolean, failedUrl: String?) -> Unit = { _, _ -> }

    init {
        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    // Only fire for the main frame (A03: no injection via sub-frames).
                    if (frame?.isMain == true) loadEndCallback()
                }

                override fun onLoadError(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    errorCode: org.cef.handler.CefLoadHandler.ErrorCode?,
                    errorText: String?,
                    failedUrl: String?
                ) {
                    loadErrorCallback(frame?.isMain == true, failedUrl)
                }
            },
            browser.cefBrowser
        )
    }

    override fun loadUrl(url: String) {
        browser.cefBrowser.loadURL(url)
    }

    override fun dispose() {
        browser.dispose()
    }

    override fun onLoadEnd(callback: () -> Unit) {
        loadEndCallback = callback
    }

    override fun onLoadError(callback: (isMainFrame: Boolean, failedUrl: String?) -> Unit) {
        loadErrorCallback = callback
    }

    override fun scheduleReload(delayMs: Long, action: () -> Unit) {
        EdtScheduledExecutorService.getInstance().schedule(action, delayMs, TimeUnit.MILLISECONDS)
    }
}
