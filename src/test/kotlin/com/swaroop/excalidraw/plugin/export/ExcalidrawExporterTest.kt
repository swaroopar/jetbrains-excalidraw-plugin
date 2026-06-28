package com.swaroop.excalidraw.plugin.export

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.OutputStream

/**
 * Unit tests for ExcalidrawExporter.
 *
 * Task: task-06-007
 * Acceptance criteria:
 *   - AC-E5-01: SVG export writes bytes whose UTF-8 string contains "<svg".
 *   - AC-E5-02: PNG export writes bytes whose first 8 bytes are the PNG magic number.
 *   - Negative: null targetFile must not trigger requestExport.
 *
 * Uses ExcalidrawJsBridge.createForTest and ExcalidrawExporter.createForTest.
 * The writeBytes hook captures bytes in a ByteArray variable — no VirtualFile.setBinaryContent
 * override required (which is final in the platform API).
 * No MockK, no Mockito — plain JUnit 5.
 */
class ExcalidrawExporterTest {

    // -------------------------------------------------------------------------
    // Minimal stub VirtualFile — identity only, not used as a write target
    // -------------------------------------------------------------------------

    /**
     * Minimal VirtualFile stub used purely as a non-null target file token.
     * The actual bytes are captured by the injected writeBytes lambda, so
     * this stub does not need to store content.
     */
    private class StubVirtualFile(private val name: String) : VirtualFile() {

        override fun getName(): String = name
        override fun getPath(): String = "/stub/$name"
        override fun isWritable(): Boolean = true
        override fun isDirectory(): Boolean = false
        override fun isValid(): Boolean = true
        override fun getParent(): VirtualFile? = null
        override fun getChildren(): Array<VirtualFile> = emptyArray()
        override fun contentsToByteArray(): ByteArray = ByteArray(0)
        override fun getTimeStamp(): Long = 0L
        override fun getLength(): Long = 0L
        override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) = Unit
        override fun getInputStream() = ByteArray(0).inputStream()
        override fun getFileSystem(): VirtualFileSystem = throw UnsupportedOperationException("stub")
        override fun getFileType(): FileType = throw UnsupportedOperationException("stub")
        override fun getOutputStream(
            requestor: Any?,
            newModificationStamp: Long,
            newTimeStamp: Long
        ): OutputStream = throw UnsupportedOperationException("stub")
    }

    // -------------------------------------------------------------------------
    // AC-E5-01: SVG export writes valid SVG bytes
    // -------------------------------------------------------------------------

    /**
     * AC-E5-01: After simulateExportResult("svg", "<svg .../>") the writeBytes
     * hook must receive bytes whose UTF-8 representation contains "<svg".
     */
    @Test
    fun `SVG export writes bytes whose string contains svg root element`() {
        // Simple SVG without inner double-quotes so it can be embedded directly in the JSON string
        val svgData = "<svg xmlns='http://www.w3.org/2000/svg'><rect width='10' height='10'/></svg>"
        val targetFile = StubVirtualFile("export.svg")
        val injectedJs = mutableListOf<String>()
        var writtenBytes: ByteArray = ByteArray(0)

        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })
        val exporter = ExcalidrawExporter.createForTest(
            writeBytes = { _: VirtualFile, bytes: ByteArray -> writtenBytes = bytes }
        )

        exporter.exportDrawing(
            format = "svg",
            scale = 1.0,
            project = null,
            bridge = bridge,
            targetFile = targetFile
        )

        // Simulate JS side responding with the export result.
        // Use com.google.gson.Gson to produce a properly escaped JSON string
        // so that svgData is safely embedded regardless of its content.
        val gson = com.google.gson.Gson()
        val exportResultJson = """{"type":"exportResult","format":"svg","data":${gson.toJson(svgData)}}"""
        bridge.simulateExportResult(exportResultJson)

        assertTrue(writtenBytes.isNotEmpty(), "written bytes must not be empty")
        val writtenString = writtenBytes.toString(Charsets.UTF_8)
        assertTrue(
            writtenString.contains("<svg"),
            "written SVG bytes must contain '<svg' root element: $writtenString"
        )
    }

    // -------------------------------------------------------------------------
    // AC-E5-02: PNG export writes bytes with PNG magic number
    // -------------------------------------------------------------------------

    /**
     * AC-E5-02: After simulateExportResult("png", <base64 minimal PNG>) the
     * first 8 bytes of the written content must be the PNG magic number:
     * 89 50 4E 47 0D 0A 1A 0A.
     */
    @Test
    fun `PNG export writes bytes beginning with PNG magic number`() {
        // Minimal valid 1x1 PNG encoded as Base64 (standard test fixture)
        val minimalPngBase64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

        val targetFile = StubVirtualFile("export.png")
        val injectedJs = mutableListOf<String>()
        var writtenBytes: ByteArray = ByteArray(0)

        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })
        val exporter = ExcalidrawExporter.createForTest(
            writeBytes = { _: VirtualFile, bytes: ByteArray -> writtenBytes = bytes }
        )

        exporter.exportDrawing(
            format = "png",
            scale = 1.0,
            project = null,
            bridge = bridge,
            targetFile = targetFile
        )

        val exportResultJson =
            """{"type":"exportResult","format":"png","data":"$minimalPngBase64"}"""
        bridge.simulateExportResult(exportResultJson)

        assertTrue(writtenBytes.size >= 8, "PNG bytes must be at least 8 bytes long")

        // PNG magic: 89 50 4E 47 0D 0A 1A 0A
        val pngMagic = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A.toByte(), 0x0A
        )
        for (i in pngMagic.indices) {
            assertEquals(
                pngMagic[i],
                writtenBytes[i],
                "PNG magic byte at index $i must be 0x${pngMagic[i].toInt().and(0xFF).toString(16).uppercase()}"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Negative test: null targetFile must not trigger requestExport
    // -------------------------------------------------------------------------

    /**
     * When targetFile is null, exportDrawing must abort silently without calling
     * bridge.requestExport (no JS injection must occur).
     */
    @Test
    fun `null targetFile causes silent abort with no requestExport call`() {
        val injectedJs = mutableListOf<String>()
        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })
        val exporter = ExcalidrawExporter.createForTest(
            writeBytes = { _: VirtualFile, _: ByteArray -> }
        )

        exporter.exportDrawing(
            format = "svg",
            scale = 1.0,
            project = null,
            bridge = bridge,
            targetFile = null
        )

        assertTrue(
            injectedJs.isEmpty(),
            "No JS must be injected when targetFile is null, but got: $injectedJs"
        )
    }

    // -------------------------------------------------------------------------
    // Bonus: verify requestExport IS called when targetFile is non-null
    // -------------------------------------------------------------------------

    /**
     * When targetFile is non-null, exportDrawing must call bridge.requestExport,
     * so the injectedJs list must contain a call with "__excalidrawExport__".
     * The injected JS must not contain eval() (A03).
     */
    @Test
    fun `non-null targetFile triggers requestExport with correct format and no eval`() {
        val targetFile = StubVirtualFile("export.svg")
        val injectedJs = mutableListOf<String>()

        val bridge = ExcalidrawJsBridge.createForTest(injector = { js -> injectedJs.add(js) })
        val exporter = ExcalidrawExporter.createForTest(
            writeBytes = { _: VirtualFile, _: ByteArray -> }
        )

        exporter.exportDrawing(
            format = "svg",
            scale = 2.0,
            project = null,
            bridge = bridge,
            targetFile = targetFile
        )

        assertFalse(injectedJs.isEmpty(), "requestExport must inject JS when targetFile is non-null")
        val js = injectedJs.joinToString()
        assertTrue(
            js.contains(ExcalidrawJsBridge.EXPORT_FN),
            "injected JS must reference EXPORT_FN '__excalidrawExport__': $js"
        )
        assertTrue(js.contains("svg"), "injected JS must contain format 'svg': $js")
        assertFalse(js.contains("eval("), "A03: injected JS must not contain eval(): $js")
    }

    // -------------------------------------------------------------------------
    // Task-06-008: ExportAction update() logic — disabled when no ExcalidrawFileEditor
    // -------------------------------------------------------------------------

    /**
     * AC-task-06-008: ExportSvgAction.isEnabledForEditor(editor) must return false
     * when the supplied editor is NOT an ExcalidrawFileEditor.
     *
     * This test exercises the pure logic helper exposed on each action class so
     * no JCEF / ApplicationManager / FileEditorManager is needed.
     */
    @Test
    fun `ExportSvgAction isEnabledForEditor returns false for non-ExcalidrawFileEditor`() {
        val action = ExportSvgAction()
        // null editor simulates "no active editor" (FileEditorManager.selectedEditor == null)
        assertFalse(
            action.isEnabledForEditor(null),
            "ExportSvgAction must be disabled when selectedEditor is null"
        )
    }

    /**
     * AC-task-06-008: ExportPngAction.isEnabledForEditor(editor) must return false
     * when the supplied editor is NOT an ExcalidrawFileEditor.
     */
    @Test
    fun `ExportPngAction isEnabledForEditor returns false for non-ExcalidrawFileEditor`() {
        val action = ExportPngAction()
        assertFalse(
            action.isEnabledForEditor(null),
            "ExportPngAction must be disabled when selectedEditor is null"
        )
    }

    // -------------------------------------------------------------------------
    // Task-06-008: plugin.xml must contain both action registrations
    // -------------------------------------------------------------------------

    /**
     * Verifies that plugin.xml contains registrations for ExportSvgAction and
     * ExportPngAction so the IDE discovers them at startup without reflection-based
     * programmatic registration.
     */
    @Test
    fun `plugin xml contains ExportSvgAction registration`() {
        val pluginXmlText = javaClass.classLoader
            .getResourceAsStream("META-INF/plugin.xml")
            ?.bufferedReader()
            ?.readText()
            ?: error("META-INF/plugin.xml not found on classpath")

        assertTrue(
            pluginXmlText.contains("ExportSvgAction"),
            "plugin.xml must contain ExportSvgAction class reference"
        )
    }

    /**
     * Verifies that plugin.xml contains a registration for ExportPngAction.
     */
    @Test
    fun `plugin xml contains ExportPngAction registration`() {
        val pluginXmlText = javaClass.classLoader
            .getResourceAsStream("META-INF/plugin.xml")
            ?.bufferedReader()
            ?.readText()
            ?: error("META-INF/plugin.xml not found on classpath")

        assertTrue(
            pluginXmlText.contains("ExportPngAction"),
            "plugin.xml must contain ExportPngAction class reference"
        )
    }
}
