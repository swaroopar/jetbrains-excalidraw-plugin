package com.swaroop.excalidraw.plugin.jcef

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for ExcalidrawJcefHost (non-JCEF aspects).
 *
 * JCEF runtime is not available in unit tests (no IDE instance), so this class tests
 * only the aspects of ExcalidrawJcefHost that do not require a live JCEF browser:
 *   - The start URL constant is correct (excalidraw://app/index.html).
 *   - The scheme name constant is correct ("excalidraw").
 *   - The class implements com.intellij.openapi.Disposable.
 *
 * TODO (Phase 02 integration test): Add HeavyPlatformTestCase / UI test that
 *   instantiates ExcalidrawJcefHost with a real IDE environment, verifies that the
 *   JBCefBrowser is created, loads excalidraw://app/index.html, and disposes cleanly.
 *   This requires a running IDE instance and cannot run in plain JUnit 5.
 */
class ExcalidrawJcefHostIntegrationPlaceholderTest {

    @Test
    fun `start URL is the bundled app entry point`() {
        // The host must load only the local bundled app — no remote URLs (NFR1).
        assertEquals(
            "excalidraw://app/index.html",
            ExcalidrawJcefHost.START_URL,
            "ExcalidrawJcefHost.START_URL must be the local bundled app URL"
        )
    }

    @Test
    fun `scheme name is excalidraw`() {
        // Scheme name used when registering the CefSchemeHandlerFactory.
        assertEquals(
            "excalidraw",
            ExcalidrawJcefHost.SCHEME,
            "ExcalidrawJcefHost.SCHEME must be 'excalidraw'"
        )
    }

    @Test
    fun `ExcalidrawJcefHost implements Disposable`() {
        // Verify via reflection that the class implements com.intellij.openapi.Disposable.
        val disposableInterface = try {
            Class.forName("com.intellij.openapi.Disposable")
        } catch (e: ClassNotFoundException) {
            // IntelliJ platform classes are available on the test classpath via Gradle.
            throw AssertionError(
                "com.intellij.openapi.Disposable not found on classpath — check intellijPlatform dependency",
                e
            )
        }
        assertTrue(
            disposableInterface.isAssignableFrom(ExcalidrawJcefHost::class.java),
            "ExcalidrawJcefHost must implement com.intellij.openapi.Disposable (AD-3)"
        )
    }
}
