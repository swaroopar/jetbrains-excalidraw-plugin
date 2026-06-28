package com.swaroop.excalidraw.plugin.filetype

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory
import java.io.File

/**
 * Unit test for ExcalidrawFileType and ExcalidrawPngFileType singletons.
 *
 * Acceptance criterion (task-01-003):
 *   ExcalidrawFileType.defaultExtension == "excalidraw"
 *   ExcalidrawFileType.name is not empty
 *
 * Acceptance criterion (task-01-004):
 *   ExcalidrawPngFileType.name is not empty and contains "png" (case-insensitive)
 *   ExcalidrawPngFileType.defaultExtension == "png"
 *
 * Acceptance criterion (task-01-005):
 *   plugin.xml is well-formed XML; contains <id>, depends com.intellij.modules.platform,
 *   and two <fileType> entries with correct implementationClass attributes.
 *
 * No IDE runtime is required — pure Kotlin unit test (JUnit 5).
 */
class ExcalidrawFileTypeTest {

    @Test
    fun `defaultExtension is excalidraw`() {
        assertEquals(
            "excalidraw",
            ExcalidrawFileType.defaultExtension,
            "ExcalidrawFileType.defaultExtension must equal \"excalidraw\""
        )
    }

    @Test
    fun `name is not empty`() {
        assertTrue(
            ExcalidrawFileType.name.isNotEmpty(),
            "ExcalidrawFileType.name must be a non-empty string"
        )
    }

    // --- task-01-004: ExcalidrawPngFileType ---

    @Test
    fun `png file type name is not empty`() {
        assertTrue(
            ExcalidrawPngFileType.name.isNotEmpty(),
            "ExcalidrawPngFileType.name must be a non-empty string"
        )
    }

    @Test
    fun `png file type name contains png case insensitive`() {
        assertTrue(
            ExcalidrawPngFileType.name.lowercase().contains("png"),
            "ExcalidrawPngFileType.name must contain \"png\" (case-insensitive)"
        )
    }

    @Test
    fun `png file type defaultExtension is png`() {
        assertEquals(
            "png",
            ExcalidrawPngFileType.defaultExtension,
            "ExcalidrawPngFileType.defaultExtension must equal \"png\""
        )
    }

    @Test
    fun `png file type isBinary is true`() {
        assertTrue(
            ExcalidrawPngFileType.isBinary(),
            "ExcalidrawPngFileType.isBinary() must return true (PNG is a binary format)"
        )
    }

    // --- task-01-005: plugin.xml Grundstruktur und FileType-Registrierung ---

    /** Resolves plugin.xml relative to the project root (via user.dir system property). */
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

    @Test
    fun `plugin xml is well-formed XML`() {
        // loadPluginXml() throws if the file is missing or not well-formed
        val doc = loadPluginXml()
        assertTrue(doc.documentElement != null, "plugin.xml must have a root element")
    }

    @Test
    fun `plugin xml contains id element`() {
        val doc = loadPluginXml()
        val ids = doc.getElementsByTagName("id")
        assertTrue(ids.length > 0, "plugin.xml must contain an <id> element")
        val idText = ids.item(0).textContent.trim()
        assertTrue(idText.isNotEmpty(), "<id> element must have non-empty text content")
    }

    @Test
    fun `plugin xml depends on com intellij modules platform`() {
        val doc = loadPluginXml()
        val depends = doc.getElementsByTagName("depends")
        var found = false
        for (i in 0 until depends.length) {
            if (depends.item(i).textContent.trim() == "com.intellij.modules.platform") {
                found = true
                break
            }
        }
        assertTrue(found, "plugin.xml must contain <depends>com.intellij.modules.platform</depends>")
    }

    @Test
    fun `plugin xml contains fileType entry for ExcalidrawFileType`() {
        val doc = loadPluginXml()
        val fileTypes = doc.getElementsByTagName("fileType")
        var found = false
        for (i in 0 until fileTypes.length) {
            val node = fileTypes.item(i)
            val implClass = node.attributes?.getNamedItem("implementationClass")?.textContent ?: ""
            if (implClass.contains("ExcalidrawFileType")) {
                found = true
                break
            }
        }
        assertTrue(
            found,
            "plugin.xml must contain a <fileType> entry with implementationClass containing 'ExcalidrawFileType'"
        )
    }

    @Test
    fun `plugin xml contains fileType entry for ExcalidrawPngFileType`() {
        val doc = loadPluginXml()
        val fileTypes = doc.getElementsByTagName("fileType")
        var found = false
        for (i in 0 until fileTypes.length) {
            val node = fileTypes.item(i)
            val implClass = node.attributes?.getNamedItem("implementationClass")?.textContent ?: ""
            if (implClass.contains("ExcalidrawPngFileType")) {
                found = true
                break
            }
        }
        assertTrue(
            found,
            "plugin.xml must contain a <fileType> entry with implementationClass containing 'ExcalidrawPngFileType'"
        )
    }

    @Test
    fun `plugin xml excalidraw fileType has correct extension`() {
        val doc = loadPluginXml()
        val fileTypes = doc.getElementsByTagName("fileType")
        var extensionsAttr = ""
        for (i in 0 until fileTypes.length) {
            val node = fileTypes.item(i)
            val implClass = node.attributes?.getNamedItem("implementationClass")?.textContent ?: ""
            if (implClass.contains("ExcalidrawFileType") && !implClass.contains("Png")) {
                extensionsAttr = node.attributes?.getNamedItem("extensions")?.textContent ?: ""
                break
            }
        }
        assertTrue(
            extensionsAttr.contains("excalidraw"),
            "ExcalidrawFileType <fileType> entry must declare extensions containing 'excalidraw', got: '$extensionsAttr'"
        )
    }

    @Test
    fun `plugin xml excalidraw png fileType matches dot-excalidraw-png via a filename pattern`() {
        val doc = loadPluginXml()
        val fileTypes = doc.getElementsByTagName("fileType")
        var patternsAttr = ""
        for (i in 0 until fileTypes.length) {
            val node = fileTypes.item(i)
            val implClass = node.attributes?.getNamedItem("implementationClass")?.textContent ?: ""
            if (implClass.contains("ExcalidrawPngFileType")) {
                patternsAttr = node.attributes?.getNamedItem("patterns")?.textContent ?: ""
                break
            }
        }
        // Must be a filename pattern, NOT extensions="excalidraw.png": the platform derives
        // the extension from the last dot only ("png"), so an extensions attribute would
        // never match and the file would open as a generic PNG image.
        assertTrue(
            patternsAttr.contains("*.excalidraw.png"),
            "ExcalidrawPngFileType <fileType> entry must declare patterns containing '*.excalidraw.png', got: '$patternsAttr'"
        )
    }

    // ---------------------------------------------------------------------------
    // File-type icon
    // ---------------------------------------------------------------------------

    @Test
    fun `excalidraw icon svg is bundled on the classpath`() {
        val url = javaClass.getResource("/icons/excalidraw.svg")
        assertNotNull(url, "/icons/excalidraw.svg must be bundled so the file types can show an icon")
    }

    @Test
    fun `both excalidraw file types expose a non-null icon`() {
        assertNotNull(ExcalidrawFileType.getIcon(), "ExcalidrawFileType must provide a file icon")
        assertNotNull(ExcalidrawPngFileType.getIcon(), "ExcalidrawPngFileType must provide a file icon")
    }
}
