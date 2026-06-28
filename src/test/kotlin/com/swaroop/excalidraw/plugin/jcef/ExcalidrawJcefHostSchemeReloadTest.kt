package com.swaroop.excalidraw.plugin.jcef

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExcalidrawJcefHost.shouldRetrySchemeLoad] — the startup-race recovery
 * that reloads the excalidraw:// start URL when a restored editor navigates before the
 * custom scheme handler is registered (ERR_UNKNOWN_URL_SCHEME).
 *
 * JCEF is unavailable in unit tests, so we exercise the decision logic directly via the
 * [ExcalidrawJcefHost.createForTest] host (no live browser).
 */
class ExcalidrawJcefHostSchemeReloadTest {

    // Mirror of the private MAX_SCHEME_RELOADS cap in ExcalidrawJcefHost.
    private val maxReloads = 20

    @Test
    fun `retries a main-frame failure on the excalidraw scheme start URL`() {
        val host = ExcalidrawJcefHost.createForTest()
        assertTrue(
            host.shouldRetrySchemeLoad(isMainFrame = true, failedUrl = ExcalidrawJcefHost.START_URL),
            "A main-frame failure on the start URL must trigger a reload"
        )
        host.disposeForTest()
    }

    @Test
    fun `retries any excalidraw-scheme url`() {
        val host = ExcalidrawJcefHost.createForTest()
        assertTrue(
            host.shouldRetrySchemeLoad(isMainFrame = true, failedUrl = "excalidraw://app/sub/page.html"),
            "Any excalidraw:// main-frame failure must trigger a reload"
        )
        host.disposeForTest()
    }

    @Test
    fun `does not retry sub-frame failures`() {
        val host = ExcalidrawJcefHost.createForTest()
        assertFalse(
            host.shouldRetrySchemeLoad(isMainFrame = false, failedUrl = ExcalidrawJcefHost.START_URL),
            "Sub-frame failures must not trigger a reload"
        )
        host.disposeForTest()
    }

    @Test
    fun `does not retry non-scheme urls`() {
        val host = ExcalidrawJcefHost.createForTest()
        assertFalse(
            host.shouldRetrySchemeLoad(isMainFrame = true, failedUrl = "https://example.com/"),
            "Non-excalidraw URLs must not trigger a reload"
        )
        assertFalse(
            host.shouldRetrySchemeLoad(isMainFrame = true, failedUrl = null),
            "A null failed URL must not trigger a reload"
        )
        host.disposeForTest()
    }

    @Test
    fun `does not retry after dispose`() {
        val host = ExcalidrawJcefHost.createForTest()
        host.disposeForTest()
        assertFalse(
            host.shouldRetrySchemeLoad(isMainFrame = true, failedUrl = ExcalidrawJcefHost.START_URL),
            "A disposed host must not trigger a reload"
        )
    }

    @Test
    fun `stops retrying after the attempt cap`() {
        val host = ExcalidrawJcefHost.createForTest()
        repeat(maxReloads) { i ->
            assertTrue(
                host.shouldRetrySchemeLoad(isMainFrame = true, failedUrl = ExcalidrawJcefHost.START_URL),
                "Attempt ${i + 1} within the cap must retry"
            )
        }
        assertFalse(
            host.shouldRetrySchemeLoad(isMainFrame = true, failedUrl = ExcalidrawJcefHost.START_URL),
            "Once the retry cap is exceeded, no further reloads are scheduled"
        )
        host.disposeForTest()
    }
}
