package com.swaroop.excalidraw.plugin.jcef

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.swing.JComponent

/**
 * In-IDE browser for the Excalidraw library site (libraries.excalidraw.com).
 *
 * Hosts a [JBCefBrowser] pointed at the library URL Excalidraw produced. When the user
 * clicks "Add to Excalidraw" on the site, it navigates to a URL carrying
 * `addLibrary=<.excalidrawlib url>` (in the query or hash). We intercept that navigation,
 * hand the `.excalidrawlib` URL to [onLibraryUrl], and close — completing the round-trip
 * without leaving the IDE.
 *
 * Note: the editor webview's origin is opaque ("null"), so Excalidraw can't set a usable
 * `libraryReturnUrl` — the site would redirect to a dead `/null/...` page. We don't rely on
 * the referrer at all; we just watch for the `addLibrary=` parameter on any navigation.
 */
internal class LibraryBrowserDialog(
    project: Project,
    private val libraryUrl: String,
    private val onLibraryUrl: (String) -> Unit,
) : DialogWrapper(project, true) {

    private val browser: JBCefBrowser = JBCefBrowser.createBuilder()
        .setUrl(libraryUrl)
        .build()

    @Volatile
    private var handled = false

    init {
        title = "Excalidraw — Browse Libraries"
        isModal = false
        isResizable = true
        registerInterceptors()
        init()
    }

    override fun createCenterPanel(): JComponent = browser.component

    override fun getPreferredFocusedComponent(): JComponent = browser.component

    override fun getDimensionServiceKey(): String = "excalidraw.library.browser"

    private fun registerInterceptors() {
        val client = browser.jbCefClient
        // The library site is opened with target=_blank, so "Add to Excalidraw" calls
        // window.open(<referrer>#addLibrary=…) — a pop-up. Catch it here (the pop-up URL
        // keeps the #addLibrary= fragment), extract it, and cancel so no stray 404 window
        // opens. Any other pop-up from the dialog is also cancelled.
        client.addLifeSpanHandler(
            object : CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    targetUrl: String?,
                    targetFrameName: String?,
                ): Boolean {
                    if (targetUrl != null && targetUrl.contains("addLibrary=")) {
                        completeWith(targetUrl)
                    }
                    return true
                }
            },
            browser.cefBrowser,
        )
        // Primary: onAddressChange carries the full URL incl. the #hash, which is where
        // the library site puts addLibrary= (useHash=true).
        client.addDisplayHandler(
            object : CefDisplayHandlerAdapter() {
                override fun onAddressChange(cefBrowser: CefBrowser?, frame: CefFrame?, url: String?) {
                    if (url != null && url.contains("addLibrary=")) completeWith(url)
                }
            },
            browser.cefBrowser,
        )
        // Secondary: if the return arrives as a full navigation whose request URL already
        // contains addLibrary=, cancel it (avoids briefly showing the dead referrer page).
        client.addRequestHandler(
            object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    userGesture: Boolean,
                    isRedirect: Boolean,
                ): Boolean {
                    val url = request?.url ?: return false
                    if (url.contains("addLibrary=")) {
                        completeWith(url)
                        return true
                    }
                    return false
                }
            },
            browser.cefBrowser,
        )
    }

    private fun completeWith(url: String) {
        if (handled) return
        LOG.info("Excalidraw: library return intercepted: $url")
        val libUrl = extractAddLibraryUrl(url)
        handled = true
        ApplicationManager.getApplication().invokeLater {
            if (libUrl != null) {
                onLibraryUrl(libUrl)
            } else {
                LOG.warn("Excalidraw: library return had no usable addLibrary URL: $url")
            }
            close(OK_EXIT_CODE)
        }
    }

    override fun dispose() {
        browser.dispose()
        super.dispose()
    }

    companion object {
        private val LOG = logger<LibraryBrowserDialog>()

        /**
         * Extracts the `.excalidrawlib` URL from a navigation that carries
         * `addLibrary=<encoded-url>` in either the query or the fragment. Returns the
         * decoded http(s) URL, or null if absent / not http(s). Pure + unit-testable.
         */
        fun extractAddLibraryUrl(url: String): String? {
            val idx = url.indexOf("addLibrary=")
            if (idx < 0) return null
            val rest = url.substring(idx + "addLibrary=".length)
            val raw = rest.substringBefore('&')
            if (raw.isEmpty()) return null
            val decoded = try {
                URLDecoder.decode(raw, StandardCharsets.UTF_8)
            } catch (_: Exception) {
                return null
            }
            return if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else null
        }
    }
}
