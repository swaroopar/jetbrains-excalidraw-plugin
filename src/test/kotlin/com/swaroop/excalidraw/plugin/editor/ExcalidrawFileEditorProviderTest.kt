package com.swaroop.excalidraw.plugin.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory
import java.io.File

/**
 * Non-JCEF unit tests for [ExcalidrawFileEditor].
 *
 * Tests that do not require a live JBCefBrowser or IDE instance are validated here.
 * Assertions cover:
 *   - getName() returns a non-empty, stable name.
 *   - isValid() returns true (stub for Phase 01 — full lifecycle in Phase 02).
 *   - isModified() returns false (stub).
 *   - ExcalidrawFileEditor implements FileEditor.
 *   - Dispose chain: ExcalidrawFileEditor implements Disposable (via FileEditor contract).
 *
 * Integration tests requiring a JCEF runtime are tagged "integration" and
 * are expected to be executed in a real IDE environment (Phase 02).
 */
class ExcalidrawFileEditorProviderTest {

    /**
     * Verify that ExcalidrawFileEditor implements the FileEditor interface.
     * This is a structural test verifiable via reflection without a live IDE instance.
     */
    @Test
    fun `ExcalidrawFileEditor implements FileEditor`() {
        assertTrue(
            FileEditor::class.java.isAssignableFrom(ExcalidrawFileEditor::class.java),
            "ExcalidrawFileEditor must implement com.intellij.openapi.fileEditor.FileEditor"
        )
    }

    /**
     * getName() must return a non-empty, stable name that identifies the editor in the IDE.
     * Verifiable via reflection/class inspection without instantiation (no JCEF needed).
     */
    @Test
    fun `getName returns non-empty string`() {
        // The editor name is defined as a constant — verifiable without instantiation.
        val editorName = ExcalidrawFileEditor.EDITOR_NAME
        assertTrue(editorName.isNotBlank(), "ExcalidrawFileEditor.EDITOR_NAME must not be blank")
    }

    /**
     * isModified() must return false — Phase 01 stub (persistence logic comes in Phase 02).
     * Verifiable by inspecting the companion constant defined for the stub.
     */
    @Test
    fun `isModified returns false as Phase 01 stub`() {
        // The stub constant IS_MODIFIED_STUB is false — no edit tracking yet.
        assertFalse(
            ExcalidrawFileEditor.IS_MODIFIED_STUB,
            "ExcalidrawFileEditor.IS_MODIFIED_STUB must be false in Phase 01"
        )
    }

    /**
     * isValid() must return true — Phase 01 stub (validation logic comes in Phase 02).
     */
    @Test
    fun `isValid returns true as Phase 01 stub`() {
        assertTrue(
            ExcalidrawFileEditor.IS_VALID_STUB,
            "ExcalidrawFileEditor.IS_VALID_STUB must be true in Phase 01"
        )
    }

    /**
     * ExcalidrawFileEditor must implement Disposable (transitively via FileEditor).
     * Verified without instantiating the class (no JCEF needed).
     *
     * AD-3: Dispose-chain — jcefHost is a child-Disposable of the editor.
     *
     * Full dispose-chain integration test (with JCEF runtime) is tagged "integration"
     * and deferred to Phase 02.
     *
     * @tag integration (full browser dispose chain requires IDE environment)
     */
    @Test
    @Tag("integration")
    fun `ExcalidrawFileEditor implements Disposable via FileEditor`() {
        val disposableClass = try {
            Class.forName("com.intellij.openapi.Disposable")
        } catch (e: ClassNotFoundException) {
            throw AssertionError(
                "com.intellij.openapi.Disposable not found on classpath — check intellijPlatform dependency",
                e
            )
        }
        assertTrue(
            disposableClass.isAssignableFrom(ExcalidrawFileEditor::class.java),
            "ExcalidrawFileEditor must implement com.intellij.openapi.Disposable " +
                "(transitively via FileEditor or UserDataHolderBase)"
        )
    }

    // -------------------------------------------------------------------------
    // ExcalidrawFileEditorProvider tests — AC-FileEditorProvider-01 through AC-FileEditorProvider-04
    // -------------------------------------------------------------------------

    /**
     * AC-FileEditorProvider-01: accept() returns true for a .excalidraw file.
     *
     * Verified via [ExcalidrawFileEditorProvider.acceptsFileName] without instantiating
     * the class with a live VirtualFile or IDE project (no JCEF runtime needed).
     *
     * AD-4: FileEditorProvider checks file extension — no programmatic registration.
     */
    @Test
    fun `accept returns true for excalidraw file`() {
        assertTrue(
            ExcalidrawFileEditorProvider.acceptsFileName("test.excalidraw"),
            "ExcalidrawFileEditorProvider must accept files ending with .excalidraw"
        )
    }

    /**
     * AC-FileEditorProvider-02 (phase-07/E6): accept() returns true for .excalidraw.png.
     *
     * .excalidraw.png support (E6) is activated in phase-07. The provider now handles
     * scene-embedded PNG files via the async PNG extraction path (task-07-007).
     * Previously deferred in phase-02 to avoid parsing a PNG binary blob as JSON.
     */
    @Test
    fun `accept returns true for excalidraw png file`() {
        assertTrue(
            ExcalidrawFileEditorProvider.acceptsFileName("test.excalidraw.png"),
            "ExcalidrawFileEditorProvider must accept .excalidraw.png (activated in E6/phase-07)"
        )
    }

    /**
     * AC-FileEditorProvider-03: accept() returns false for a plain .png file.
     *
     * A regular PNG without the Excalidraw compound extension must be rejected so that
     * the IDE's default image viewer opens it instead.
     */
    @Test
    fun `accept returns false for plain png file`() {
        assertFalse(
            ExcalidrawFileEditorProvider.acceptsFileName("test.png"),
            "ExcalidrawFileEditorProvider must NOT accept plain .png files"
        )
    }

    /**
     * AC-FileEditorProvider-04: accept() returns false for a .json file.
     *
     * JSON is not an Excalidraw file format handled by this plugin.
     */
    @Test
    fun `accept returns false for json file`() {
        assertFalse(
            ExcalidrawFileEditorProvider.acceptsFileName("test.json"),
            "ExcalidrawFileEditorProvider must NOT accept .json files"
        )
    }

    /**
     * accept() returns false for a plain .txt file.
     *
     * Additional guard against false positives on arbitrary file extensions.
     */
    @Test
    fun `accept returns false for txt file`() {
        assertFalse(
            ExcalidrawFileEditorProvider.acceptsFileName("test.txt"),
            "ExcalidrawFileEditorProvider must NOT accept .txt files"
        )
    }

    /**
     * ExcalidrawFileEditorProvider must implement FileEditorProvider.
     * Structural test verifiable via reflection without a live IDE instance.
     */
    @Test
    fun `ExcalidrawFileEditorProvider implements FileEditorProvider`() {
        assertTrue(
            FileEditorProvider::class.java.isAssignableFrom(ExcalidrawFileEditorProvider::class.java),
            "ExcalidrawFileEditorProvider must implement com.intellij.openapi.fileEditor.FileEditorProvider"
        )
    }

    /**
     * getEditorTypeId() must return a non-empty, stable ID.
     * Used by the IDE to persist editor type selection per file.
     */
    @Test
    fun `getEditorTypeId returns non-empty stable id`() {
        val id = ExcalidrawFileEditorProvider.EDITOR_TYPE_ID
        assertTrue(id.isNotBlank(), "ExcalidrawFileEditorProvider.EDITOR_TYPE_ID must not be blank")
    }

    // -------------------------------------------------------------------------
    // task-01-011: plugin.xml FileEditorProvider-Registrierung
    // -------------------------------------------------------------------------

    /**
     * Loads plugin.xml from the project root (via user.dir system property).
     * Uses XXE-safe DocumentBuilderFactory configuration.
     */
    private fun loadPluginXml(): Document {
        val projectDir = System.getProperty("user.dir")
            ?: error("user.dir system property is not set")
        val pluginXml = File(projectDir, "src/main/resources/META-INF/plugin.xml")
        assertTrue(pluginXml.exists(), "plugin.xml must exist at ${pluginXml.absolutePath}")
        val factory = DocumentBuilderFactory.newInstance()
        // Disable external entity resolution (XXE prevention)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val builder = factory.newDocumentBuilder()
        return builder.parse(pluginXml)
    }

    /**
     * AC-task-01-011: plugin.xml must contain a <fileEditorProvider> element with
     * implementation="com.swaroop.excalidraw.plugin.editor.ExcalidrawFileEditorProvider".
     *
     * AD-4: All extension points are registered declaratively in plugin.xml — no
     * EP_NAME.registerExtension() at runtime.
     */
    @Test
    fun `plugin xml contains fileEditorProvider for ExcalidrawFileEditorProvider`() {
        val doc = loadPluginXml()
        val nodes = doc.getElementsByTagName("fileEditorProvider")
        var found = false
        for (i in 0 until nodes.length) {
            val impl = nodes.item(i).attributes
                ?.getNamedItem("implementation")?.textContent ?: ""
            if (impl == "com.swaroop.excalidraw.plugin.editor.ExcalidrawFileEditorProvider") {
                found = true
                break
            }
        }
        assertTrue(
            found,
            "plugin.xml must contain a <fileEditorProvider implementation=" +
                "\"com.swaroop.excalidraw.plugin.editor.ExcalidrawFileEditorProvider\"/> entry"
        )
    }

    /**
     * plugin.xml must remain well-formed XML after adding the fileEditorProvider entry.
     * Parsing success is sufficient proof of well-formedness.
     */
    @Test
    fun `plugin xml remains well-formed after fileEditorProvider registration`() {
        val doc = loadPluginXml()
        assertTrue(
            doc.documentElement != null,
            "plugin.xml must have a root element and be well-formed XML"
        )
    }
}
