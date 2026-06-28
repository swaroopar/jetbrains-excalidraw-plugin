package com.swaroop.excalidraw.plugin.persistence

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for ExcalidrawPersistenceService.readScene(VirtualFile).
 *
 * Uses a minimal StubVirtualFile that directly exposes byte content without
 * invoking IntelliJ VFS infrastructure (ApplicationManager / FileSizeLimit),
 * so these tests run as plain JUnit 5 tests without a running IDE.
 */
class ExcalidrawPersistenceServiceTest {

    private val service = ExcalidrawPersistenceService()

    // ---------------------------------------------------------------------------
    // Stub VirtualFile for unit tests
    // ---------------------------------------------------------------------------

    /**
     * Minimal VirtualFile stub that serves byte content directly.
     * Overrides [contentsToByteArray] to bypass IntelliJ VFS platform code.
     */
    private class StubVirtualFile(
        private val name: String,
        private val content: ByteArray
    ) : VirtualFile() {

        override fun getName(): String = name
        override fun getPath(): String = "/stub/$name"
        override fun isWritable(): Boolean = false
        override fun isDirectory(): Boolean = false
        override fun isValid(): Boolean = true
        override fun getParent(): VirtualFile? = null
        override fun getChildren(): Array<VirtualFile> = emptyArray()
        override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long) =
            throw UnsupportedOperationException("StubVirtualFile is read-only")
        override fun contentsToByteArray(): ByteArray = content
        override fun getTimeStamp(): Long = 0L
        override fun getLength(): Long = content.size.toLong()
        override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) = Unit
        override fun getInputStream() = content.inputStream()
        override fun getFileSystem(): VirtualFileSystem = throw UnsupportedOperationException()
        override fun getFileType(): FileType = throw UnsupportedOperationException()
    }

    // ---------------------------------------------------------------------------
    // Fixture helpers
    // ---------------------------------------------------------------------------

    private fun stubFromResource(resourcePath: String): StubVirtualFile {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Test resource not found: $resourcePath")
        val bytes = stream.readBytes()
        return StubVirtualFile(resourcePath, bytes)
    }

    private fun stubWithContent(name: String, content: String): StubVirtualFile =
        StubVirtualFile(name, content.toByteArray(Charsets.UTF_8))

    // ---------------------------------------------------------------------------
    // TC-01: valid JSON returns ExcalidrawScene with correct fields
    // ---------------------------------------------------------------------------

    @Test
    fun `valid JSON fixture returns ExcalidrawScene with correct fields`() {
        val vf = stubFromResource("fixtures/valid-scene.excalidraw")

        val scene = service.readScene(vf)

        assertNotNull(scene)
        assertEquals("excalidraw", scene.type)
        assertEquals(2, scene.version)
        assertEquals("https://excalidraw.com", scene.source)
        assertEquals(1, scene.elements.size)
        assertEquals("el1", scene.elements[0]["id"] as? String)
        assertNotNull(scene.appState)
    }

    // ---------------------------------------------------------------------------
    // TC-02: missing elements field throws ExcalidrawParseException
    // ---------------------------------------------------------------------------

    @Test
    fun `JSON without elements field throws ExcalidrawParseException`() {
        val content = """
            {
              "type": "excalidraw",
              "version": 2,
              "source": null,
              "appState": { "viewBackgroundColor": "#ffffff" }
            }
        """.trimIndent()
        val vf = stubWithContent("no-elements.excalidraw", content)

        assertThrows<ExcalidrawParseException> {
            service.readScene(vf)
        }
    }

    // ---------------------------------------------------------------------------
    // TC-03: invalid (corrupt) JSON throws ExcalidrawParseException
    // ---------------------------------------------------------------------------

    @Test
    fun `corrupt JSON fixture throws ExcalidrawParseException`() {
        val vf = stubFromResource("fixtures/corrupt-scene.excalidraw")

        assertThrows<ExcalidrawParseException> {
            service.readScene(vf)
        }
    }

    // ---------------------------------------------------------------------------
    // TC-04: empty content throws ExcalidrawParseException
    // ---------------------------------------------------------------------------

    @Test
    fun `empty file content throws ExcalidrawParseException`() {
        val vf = stubWithContent("empty.excalidraw", "")

        assertThrows<ExcalidrawParseException> {
            service.readScene(vf)
        }
    }

    // ---------------------------------------------------------------------------
    // TC-05: wrong type field throws ExcalidrawParseException (spec/security gap fix)
    // ---------------------------------------------------------------------------

    @Test
    fun `JSON with type other than excalidraw throws ExcalidrawParseException`() {
        val content = """
            {
              "type": "other",
              "version": 2,
              "source": null,
              "elements": [],
              "appState": { "viewBackgroundColor": "#ffffff" }
            }
        """.trimIndent()
        val vf = stubWithContent("wrong-type.excalidraw", content)

        assertThrows<ExcalidrawParseException> {
            service.readScene(vf)
        }
    }

    // ---------------------------------------------------------------------------
    // readSceneOrNew: empty/new file opens a blank drawing instead of throwing
    // ---------------------------------------------------------------------------

    @Test
    fun `readSceneOrNew on empty file returns a new blank scene`() {
        val vf = stubWithContent("new.excalidraw", "")

        val scene = service.readSceneOrNew(vf)

        assertEquals("excalidraw", scene.type)
        assertEquals(0, scene.elements.size)
        assertNotNull(scene.appState)
    }

    @Test
    fun `readSceneOrNew on blank (whitespace) file returns a new blank scene`() {
        val vf = stubWithContent("blank.excalidraw", "   \n\t ")

        val scene = service.readSceneOrNew(vf)

        assertEquals("excalidraw", scene.type)
        assertEquals(0, scene.elements.size)
    }

    @Test
    fun `readSceneOrNew on valid content parses normally`() {
        val vf = stubFromResource("fixtures/valid-scene.excalidraw")

        val scene = service.readSceneOrNew(vf)

        assertEquals("excalidraw", scene.type)
        assertEquals(1, scene.elements.size)
    }

    @Test
    fun `readSceneOrNew on malformed non-empty JSON still throws`() {
        val vf = stubWithContent("broken.excalidraw", "{ not valid json")

        assertThrows<ExcalidrawParseException> {
            service.readSceneOrNew(vf)
        }
    }
}
