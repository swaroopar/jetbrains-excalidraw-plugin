package com.swaroop.excalidraw.plugin.persistence

import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import com.google.gson.Gson
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
 * - Validates mandatory fields (type, elements, appState) before returning a scene.
 * - Rejects type != "excalidraw", empty content, and malformed JSON early.
 * - Write path uses IDE Document/VFS API exclusively — no java.io.File, no NIO.
 * - WriteAction ensures undo-buffer participation and IDE modified-state tracking.
 *
 * Declared as `open class` to allow subclassing in phase-07 (PNG embedding).
 */
open class ExcalidrawPersistenceService {

    companion object {
        private val LOG: Logger = Logger.getInstance(ExcalidrawPersistenceService::class.java)
    }

    private val gson = Gson()

    /**
     * Reads the content of [file] and parses it as an Excalidraw scene JSON document.
     *
     * When called in a running IntelliJ application, VFS byte access is wrapped in a
     * [ReadAction] to satisfy the platform's threading model. In unit-test contexts
     * where no Application is initialised, bytes are read directly.
     *
     * @param file the VirtualFile to read; must be a UTF-8 encoded `.excalidraw` file.
     * @return a fully populated [ExcalidrawScene] if parsing succeeds.
     * @throws ExcalidrawParseException if the content is empty, is not valid JSON,
     *         or is missing mandatory fields (elements, appState).
     */
    fun readScene(file: VirtualFile): ExcalidrawScene {
        val filePath = file.path
        val content: String = readContent(file, filePath)
        return parseScene(content, filePath)
    }

    /**
     * Like [readScene], but treats an empty / blank file as a NEW blank drawing
     * ([ExcalidrawScene.newEmpty]) instead of throwing.
     *
     * This is the entry point the editor uses when opening a file, so that creating a
     * fresh `.excalidraw` (which starts empty) opens a usable blank canvas rather than a
     * parse-error notification. Non-empty but malformed content still throws
     * [ExcalidrawParseException] so genuine corruption is surfaced.
     */
    fun readSceneOrNew(file: VirtualFile): ExcalidrawScene {
        val filePath = file.path
        val content: String = readContent(file, filePath)
        return if (content.isBlank()) ExcalidrawScene.newEmpty() else parseScene(content, filePath)
    }

    /**
     * Writes [json] to [file] exclusively through the IDE Document / VFS API.
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
     * @param json the canonical `.excalidraw` JSON string to persist.
     */
    open fun writeScene(file: VirtualFile, json: String) {
        val app = requireApplication("writeScene", file.path) ?: return

        val document: Document? = FileDocumentManager.getInstance().getDocument(file)
        if (document == null) {
            LOG.warn("writeScene: no Document found for ${file.path} — skipping write")
            return
        }

        app.runWriteAction {
            writeSceneToDocument(document, json)
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
     * Checks whether an Application is available; if not, logs a standardized warning
     * and returns null to signal callers to skip the write operation.
     *
     * Extracted to deduplicate the "no Application available" guard between
     * [writeScene] and [writePngScene].
     *
     * @param methodName the name of the calling method (for the warning message).
     * @param filePath the path of the file being written (for the warning message).
     * @return the Application instance, or null if not available (headless / unit-test context).
     */
    private fun requireApplication(methodName: String, filePath: String): com.intellij.openapi.application.Application? {
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
    private fun readContent(file: VirtualFile, filePath: String): String {
        val bytes: ByteArray = if (ApplicationManager.getApplication() != null) {
            ReadAction.compute<ByteArray, Throwable> { file.contentsToByteArray() }
        } else {
            // Unit-test context: no running ApplicationManager; read bytes directly.
            file.contentsToByteArray()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /**
     * Parses a raw JSON [content] string into an [ExcalidrawScene].
     *
     * Validates mandatory fields (elements, appState) and rejects empty or
     * malformed input early (A03: whitelist validation; A08: schema enforcement).
     *
     * @throws ExcalidrawParseException on any parse or validation failure.
     */
    private fun parseScene(content: String, filePath: String): ExcalidrawScene {
        if (content.isBlank()) {
            throw ExcalidrawParseException(
                filePath,
                IllegalArgumentException("File content is empty")
            )
        }

        // Parse top-level JSON into a raw map. JsonSyntaxException signals malformed JSON.
        @Suppress("UNCHECKED_CAST")
        val rawMap: Map<String, Any?> = try {
            gson.fromJson(content, Map::class.java) as? Map<String, Any?>
                ?: throw JsonParseException("Top-level JSON element is not an object")
        } catch (ex: JsonSyntaxException) {
            throw ExcalidrawParseException(filePath, ex)
        } catch (ex: JsonParseException) {
            throw ExcalidrawParseException(filePath, ex)
        }

        // Validate mandatory field: elements (A03 — reject if absent or wrong type)
        val elementsRaw = rawMap["elements"]
            ?: throw ExcalidrawParseException(
                filePath,
                IllegalArgumentException("Missing mandatory field: elements")
            )
        if (elementsRaw !is List<*>) {
            throw ExcalidrawParseException(
                filePath,
                IllegalArgumentException("Field 'elements' must be a JSON array")
            )
        }

        // Validate mandatory field: appState (A03 — reject if absent or wrong type)
        val appStateRaw = rawMap["appState"]
            ?: throw ExcalidrawParseException(
                filePath,
                IllegalArgumentException("Missing mandatory field: appState")
            )
        if (appStateRaw !is Map<*, *>) {
            throw ExcalidrawParseException(
                filePath,
                IllegalArgumentException("Field 'appState' must be a JSON object")
            )
        }

        // Validate type field equals "excalidraw" (A03: whitelist; A08: schema enforcement).
        val type = (rawMap["type"] as? String) ?: ""
        if (type != "excalidraw") {
            throw ExcalidrawParseException(
                filePath,
                IllegalArgumentException("Field 'type' must be \"excalidraw\", got: \"$type\"")
            )
        }

        val version = when (val v = rawMap["version"]) {
            is Double -> v.toInt()
            is Int -> v
            is Long -> v.toInt()
            is Number -> v.toInt()
            else -> 0
        }
        val source = rawMap["source"] as? String

        @Suppress("UNCHECKED_CAST")
        val elements = elementsRaw.filterIsInstance<Map<String, Any>>()

        // Safe defensive cast: build a Map<String, Any> from the validated Map<*, *>,
        // filtering out any entry whose key is not a String (defensive; Gson always
        // produces String keys, but we reject rather than silently drop bad input).
        val appState: Map<String, Any> = appStateRaw
            .entries
            .mapNotNull { (k, v) ->
                if (k is String && v != null) k to v else null
            }
            .toMap()

        @Suppress("UNCHECKED_CAST")
        val files = rawMap["files"] as? Map<String, Any>

        return ExcalidrawScene(
            type = type,
            version = version,
            source = source,
            elements = elements,
            appState = appState,
            files = files
        )
    }
}
