package com.swaroop.excalidraw.plugin.filetype

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.NlsContexts
import javax.swing.Icon

/**
 * FileType singleton for .excalidraw.png files.
 *
 * An .excalidraw.png file is a PNG image that also carries the full Excalidraw
 * scene in its metadata, making it both a valid image and a re-openable drawing.
 *
 * Registered in plugin.xml as an extension-point (fileType) so that the
 * IntelliJ Platform maps .excalidraw.png files to this type at plugin load time
 * (AD-04 — declarative plugin.xml registration).
 *
 * isBinary() returns true because PNG is a binary format.
 * No network access; no mutable state — pure descriptor object (NFR1).
 */
object ExcalidrawPngFileType : FileType {

    /** Human-readable name shown in file-type settings. */
    override fun getName(): String = "Excalidraw PNG Drawing"

    /** Short description shown in file-type settings. */
    @NlsContexts.Label
    override fun getDescription(): String = "Excalidraw diagram embedded in a PNG file"

    /**
     * Primary extension without the leading dot.
     *
     * The full compound extension (.excalidraw.png) is handled by the plugin.xml
     * fileType registration using the patterns attribute; the defaultExtension here
     * is "png" as required by the acceptance criterion (task-01-004).
     */
    override fun getDefaultExtension(): String = "png"

    /** Excalidraw file icon shown in the Project view, editor tabs and settings. */
    override fun getIcon(): Icon = ExcalidrawIcons.FILE

    /** PNG is a binary format. */
    override fun isBinary(): Boolean = true
}
