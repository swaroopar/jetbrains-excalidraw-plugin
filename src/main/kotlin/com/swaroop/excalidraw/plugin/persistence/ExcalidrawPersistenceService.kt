package com.swaroop.excalidraw.plugin.persistence

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.diagnostic.Logger

/**
 * Service responsible for reading and writing `.excalidraw` files through the
 * IntelliJ Virtual File System.
 *
 * Secure-coding notes (A03 / A08):
 * - Uses Gson as the established, vetted JSON parser — no eval() or dynamic code.
 * - Validates mandatory fields (type, elements, appState) before returning a scene
 *   (see [Scene.parseFile]).
 * - Write path uses IDE Document/VFS API exclusively — no java.io.File, no NIO.
 * - WriteAction ensures undo-buffer participation and IDE modified-state tracking.
 *
 * Declared as `open class` to allow subclassing in phase-07 (PNG embedding).
 */
open class ExcalidrawPersistenceService {

    companion object {
        private val LOG: Logger = Logger.getInstance(ExcalidrawPersistenceService::class.java)
    }

    /**
     * Reads the content of [file] and parses it as an Excalidraw [Scene].
     *
     * When called in a running IntelliJ application, VFS byte access is wrapped in a
     * [ReadAction] to satisfy the platform's threading model. In unit-test contexts
     * where no Application is initialised, bytes are read directly.
     *
     * @param file the VirtualFile to read; must be a UTF-8 encoded `.excalidraw` file.
     * @return a fully populated [Scene] if parsing succeeds.
     * @throws ExcalidrawParseException if the content is empty, is not valid JSON,
     *         or is missing mandatory fields (elements, appState).
     */
    fun readScene(file: VirtualFile): Scene {
        val filePath = file.path
        val content: String = readContent(file)
        return Scene.parseFile(content, filePath)
    }

    /**
     * Like [readScene], but treats an empty / blank file as a NEW blank drawing
     * ([Scene.empty]) instead of throwing.
     *
     * This is the entry point the editor uses when opening a file, so that creating a
     * fresh `.excalidraw` (which starts empty) opens a usable blank canvas rather than a
     * parse-error notification. Non-empty but malformed content still throws
     * [ExcalidrawParseException] so genuine corruption is surfaced.
     */
    fun readSceneOrNew(file: VirtualFile): Scene {
        val filePath = file.path
        val content: String = readContent(file)
        return if (content.isBlank()) Scene.empty() else Scene.parseFile(content, filePath)
    }

    /**
     * Writes [scene]'s canonical JSON ([Scene.toCanonicalJson]) to [file] exclusively
     * through the IDE Document / VFS API.
     *
     * The write is performed inside a [com.intellij.openapi.application.Application.runWriteAction]
     * block so it participates in the IDE undo-buffer and modified-state tracking
     * (AC-E3-03).  If [FileDocumentManager] returns null for [file] (e.g. in a
     * unit-test context where no Document is registered), the method logs a warning
     * and returns without crashing.
     *
     * Security (A03 / A05): no java.io.File or NIO writes — all I/O goes through
     * the platform's VFS layer.
     *
     * @param file the VirtualFile to write; must be writable.
     * @param scene the scene to persist.
     */
    open fun writeScene(file: VirtualFile, scene: Scene) {
        val app = requireApplication("writeScene", file.path) ?: return

        val document: Document? = FileDocumentManager.getInstance().getDocument(file)
        if (document == null) {
            LOG.warn("writeScene: no Document found for ${file.path} — skipping write")
            return
        }

        app.runWriteAction {
            writeSceneToDocument(document, scene.toCanonicalJson())
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }

    /**
     * Writes raw PNG bytes to [file] via [VirtualFile.setBinaryContent] inside a WriteAction.
     *
     * The [base64Png] parameter is a standard Base64-encoded string (no data-URL prefix).
     * Decoding is performed by [java.util.Base64] — no string concatenation of untrusted
     * data, no eval-equivalent (A03 compliance).
     *
     * Uses [VirtualFile.setBinaryContent] rather than a Document/FileDocumentManager
     * approach because PNG is binary content that must not be re-encoded as text.
     * No java.io.File, no NIO — all I/O goes through the IntelliJ VFS layer (A05).
     *
     * If [ApplicationManager.getApplication] returns null (headless / plain-JUnit context),
     * the method logs a warning and skips the write rather than throwing.
     *
     * @param file the target [VirtualFile]; must be writable.
     * @param base64Png the PNG content as a standard Base64-encoded string.
     */
    open fun writePngScene(file: VirtualFile, base64Png: String) {
        val app = requireApplication("writePngScene", file.path) ?: return
        // A03: Base64 decoding of the payload — standard JVM decoder, no execution of content.
        val bytes = java.util.Base64.getDecoder().decode(base64Png)
        // A05: binary write via VFS setBinaryContent inside WriteAction for undo-buffer
        // participation and thread-safety.  No java.io.File, no NIO.
        app.runWriteAction {
            file.setBinaryContent(bytes)
        }
    }

    /**
     * Internal helper: applies [json] to [document] via [Document.setText].
     *
     * Extracted for testability — tests can call this method directly with a
     * stub Document to verify the write without needing a running ApplicationManager
     * or a real FileDocumentManager (task-04-002 / task-04-003).
     *
     * Must be called from within a WriteAction when invoked in a live IDE context.
     *
     * @param document the target Document.
     * @param json the JSON string to set as document content.
     */
    open fun writeSceneToDocument(document: Document, json: String) {
        document.setText(json)
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the running [Application], or `null` (after logging a warning) when
     * called in a headless / plain-JUnit context with no Application available —
     * the signal for the caller to skip its write rather than crash.
     */
    private fun requireApplication(methodName: String, filePath: String): Application? {
        val app = ApplicationManager.getApplication()
        if (app == null) {
            LOG.warn("$methodName: no Application available for $filePath — skipping write")
        }
        return app
    }

    /**
     * Reads raw bytes from [file], using [ReadAction] when an IntelliJ Application
     * instance is available, or direct byte access in test contexts.
     */
    private fun readContent(file: VirtualFile): String {
        val bytes: ByteArray = if (ApplicationManager.getApplication() != null) {
            ReadAction.compute<ByteArray, Throwable> { file.contentsToByteArray() }
        } else {
            // Unit-test context: no running ApplicationManager; read bytes directly.
            file.contentsToByteArray()
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
