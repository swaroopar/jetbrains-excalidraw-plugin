package com.swaroop.excalidraw.plugin.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * In this plain-JUnit context there is no running [com.intellij.openapi.application.Application],
 * so [runOnEdtOrNow] always takes its "run directly" branch — the production
 * (has-an-Application, off-EDT) branch is exercised implicitly by the callers'
 * own tests (e.g. [com.swaroop.excalidraw.plugin.editor.ExcalidrawFileEditor]).
 */
class EdtUtilTest {

    @Test
    fun `runs the action synchronously when no Application is available`() {
        var invocations = 0
        runOnEdtOrNow { invocations++ }
        assertEquals(1, invocations)
    }
}
