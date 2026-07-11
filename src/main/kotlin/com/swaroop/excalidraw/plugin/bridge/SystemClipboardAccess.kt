package com.swaroop.excalidraw.plugin.bridge

import com.intellij.openapi.diagnostic.Logger
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * Handles reading from and writing to the OS system clipboard with retry-on-lock.
 *
 * The system clipboard is a shared OS resource; a brief retry rides out a transient lock
 * held by another app.
 */
class SystemClipboardAccess {

    /**
     * Reads the OS clipboard's plain-text contents, or "" when it holds no text
     * (e.g. an image only) or is momentarily locked.
     */
    fun readText(): String {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        repeat(CLIPBOARD_RETRIES) {
            try {
                return clipboard.getData(DataFlavor.stringFlavor) as? String ?: ""
            } catch (_: IllegalStateException) {
                sleepQuietly()          // clipboard busy — retry
            } catch (_: Exception) {
                return ""               // no string flavor / IO — treat as empty
            }
        }
        return ""
    }

    /**
     * Writes [text] to the OS clipboard, retrying briefly through a transient lock.
     */
    fun writeText(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        repeat(CLIPBOARD_RETRIES) {
            try {
                clipboard.setContents(StringSelection(text), null)
                return
            } catch (_: IllegalStateException) {
                sleepQuietly()
            }
        }
    }

    private companion object {
        private val LOG: Logger = Logger.getInstance(SystemClipboardAccess::class.java)

        private const val CLIPBOARD_RETRIES: Int = 3

        private fun sleepQuietly() {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
