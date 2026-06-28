package com.swaroop.excalidraw.plugin.jcef

import com.intellij.ide.ApplicationInitializedListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.jcef.JBCefApp

/**
 * Registers the bundled `excalidraw://` custom scheme handler factory with JCEF exactly
 * once, at application startup.
 *
 * Why this exists: [JBCefApp.addCefCustomSchemeHandlerFactory] must be called BEFORE
 * JBCefApp is initialized (i.e. before the first [com.intellij.ui.jcef.JBCefBrowser] is
 * created anywhere in the IDE). Calling it later — e.g. from the per-editor
 * [ExcalidrawJcefHost] constructor — throws `IllegalStateException: JBCefApp has already
 * been initialized!`, which prevented the editor from opening. An
 * [ApplicationInitializedListener] runs early in startup, before any browser is created,
 * which is the registration window JCEF requires.
 *
 * Registered via the `com.intellij.applicationInitializedListener` extension point in
 * plugin.xml. Guarded by [JBCefApp.isSupported] so it is a no-op when JCEF is unavailable,
 * and the registration is wrapped so a late call cannot crash IDE startup.
 */
internal class ExcalidrawSchemeHandlerRegistrar : ApplicationInitializedListener {

    override suspend fun execute() {
        if (!JBCefApp.isSupported()) {
            LOG.info("JCEF is not supported in this runtime — Excalidraw scheme handler not registered")
            return
        }
        try {
            JBCefApp.addCefCustomSchemeHandlerFactory(ExcalidrawJcefHost.SchemeHandlerFactory())
            LOG.info("Registered excalidraw:// custom scheme handler factory")
        } catch (e: IllegalStateException) {
            // JBCefApp was already initialized before this listener ran (unexpected ordering).
            // Log rather than crash startup; the editor would then fail to load its assets.
            LOG.warn("Could not register the excalidraw:// scheme handler — JBCefApp was already initialized", e)
        }
    }

    private companion object {
        private val LOG = logger<ExcalidrawSchemeHandlerRegistrar>()
    }
}
