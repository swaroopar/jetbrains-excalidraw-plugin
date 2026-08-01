package com.swaroop.excalidraw.plugin.util

import com.intellij.openapi.application.ApplicationManager

/**
 * Runs [action] on the EDT via `invokeLater`, or immediately on the calling thread when
 * there is no running [com.intellij.openapi.application.Application] (unit-test context)
 * or the calling thread is already the EDT.
 *
 * Centralises the "route to the EDT, or run directly in test mode" idiom shared by the
 * bridge/editor/jcef layers when dispatching callbacks fired from JCEF-internal threads.
 */
fun runOnEdtOrNow(action: () -> Unit) {
    val application = try {
        ApplicationManager.getApplication()
    } catch (_: Exception) {
        // Some unit-test environments throw rather than return null when uninitialised.
        null
    }
    if (application == null || application.isDispatchThread) {
        action()
    } else {
        application.invokeLater(action)
    }
}
