package com.swaroop.excalidraw.plugin.export

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge

/**
 * ExcalidrawExporter — orchestrates the export workflow for an Excalidraw drawing.
 *
 * Workflow:
 * 1. If [targetFile] is null: abort silently (no JS call).
 * 2. Register a one-shot [ExportMessage.ExportResult] callback on [bridge].
 * 3. Call [bridge.requestExport] to trigger the JS-side export.
 * 4. When the callback fires: decode bytes (UTF-8 for SVG; Base64 for PNG) and
 *    write them to [targetFile] via [writeBytes].
 *
 * Secure-coding (A03):
 * - All JS calls are delegated to [bridge.requestExport] which uses Gson to encode
 *   the format argument as a safe JS string literal — no raw eval(), no string
 *   concatenation of user data.
 *
 * Testability:
 * - Use [createForTest] to inject a custom [writeBytes] hook; this avoids the
 *   IDE ApplicationManager / VFS WriteAction machinery in unit tests.
 */
class ExcalidrawExporter private constructor(
    /**
     * Hook for writing bytes to a [VirtualFile].
     *
     * In production this is an ApplicationManager.runWriteAction wrapper around
     * [VirtualFile.setBinaryContent]. In tests it is injected via [createForTest].
     */
    private val writeBytes: (VirtualFile, ByteArray) -> Unit
) {

    companion object {

        private val LOG: Logger = Logger.getInstance(ExcalidrawExporter::class.java)

        /**
         * Production factory: creates an exporter that writes via
         * [com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction]
         * so the write participates in the IDE undo-buffer.
         *
         * Falls back to a direct [VirtualFile.setBinaryContent] call when no
         * ApplicationManager is available (e.g. in headless test contexts that
         * accidentally call this factory — prefer [createForTest] in unit tests).
         */
        fun create(): ExcalidrawExporter = ExcalidrawExporter { file, bytes ->
            val app = com.intellij.openapi.application.ApplicationManager.getApplication()
            if (app != null) {
                app.runWriteAction {
                    file.setBinaryContent(bytes)
                }
            } else {
                // Headless / no-Application context: direct write.
                LOG.warn("ExcalidrawExporter: ApplicationManager not available, writing directly")
                file.setBinaryContent(bytes)
            }
        }

        /**
         * Test factory: creates an exporter with a custom [writeBytes] hook so
         * unit tests can capture written bytes without IDE VFS infrastructure.
         *
         * No Reflection — the injected lambda is passed through the normal
         * constructor path.
         *
         * @param writeBytes Lambda invoked with the resolved [VirtualFile] and
         *   the decoded byte content to write.
         */
        fun createForTest(writeBytes: (VirtualFile, ByteArray) -> Unit): ExcalidrawExporter =
            ExcalidrawExporter(writeBytes)
    }

    /**
     * Exports the drawing to [targetFile].
     *
     * @param format    The export format: `"svg"` or `"png"`.
     * @param scale     The device-pixel scale factor (relevant for PNG).
     * @param project   The IDE [Project] context (may be null in test scenarios).
     * @param bridge    The live [ExcalidrawJsBridge] to communicate with the web app.
     * @param targetFile The destination [VirtualFile]; if null the call is a no-op.
     */
    fun exportDrawing(
        format: String,
        scale: Double,
        project: Project?,
        bridge: ExcalidrawJsBridge,
        targetFile: VirtualFile?
    ) {
        // (1) Silent abort if no target file was chosen (user cancelled dialog).
        if (targetFile == null) return

        // (2) Register one-shot callback before requesting the export so there is
        //     no race between the callback registration and the JS-side response.
        bridge.registerExportResultCallback { result ->
            handleExportResult(result, targetFile)
        }

        // (3) Trigger the JS-side export via the bridge.
        //     A03: bridge.requestExport encodes the format argument via Gson — no eval.
        bridge.requestExport(format, scale)
    }

    // -------------------------------------------------------------------------
    // Internal helper
    // -------------------------------------------------------------------------

    /**
     * Decodes [result.data] to bytes according to [result.format] and writes
     * them to [targetFile] via [writeBytes].
     *
     * - `"svg"`: data is a raw SVG string; encode to UTF-8.
     * - `"png"`: data is a Base64-encoded PNG; decode via [java.util.Base64].
     * - Unknown format: log a warning and discard (A09: no silent swallow).
     */
    private fun handleExportResult(
        result: ExportMessage.ExportResult,
        targetFile: VirtualFile
    ) {
        val bytes: ByteArray = when (result.format) {
            "svg" -> result.data.toByteArray(Charsets.UTF_8)
            "png" -> {
                try {
                    java.util.Base64.getDecoder().decode(result.data)
                } catch (ex: IllegalArgumentException) {
                    LOG.warn("ExcalidrawExporter: invalid Base64 in PNG export result — discarding", ex)
                    return
                }
            }
            else -> {
                LOG.warn("ExcalidrawExporter: unknown export format '${result.format}' — discarding")
                return
            }
        }

        writeBytes(targetFile, bytes)
    }
}
