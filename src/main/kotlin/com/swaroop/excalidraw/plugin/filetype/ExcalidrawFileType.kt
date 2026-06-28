package com.swaroop.excalidraw.plugin.filetype

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * FileType singleton for .excalidraw files.
 *
 * Registered in plugin.xml as an extension-point (fileType) so that the
 * IntelliJ Platform maps .excalidraw files to this type at plugin load time
 * (AD-04 — declarative plugin.xml registration).
 *
 * No network access; no mutable state — pure descriptor object (NFR1).
 */
object ExcalidrawFileType : FileType {

    /** Human-readable name shown in file-type settings. */
    override fun getName(): String = "Excalidraw Drawing"

    /** Short description shown in file-type settings. */
    @NlsContexts.Label
    override fun getDescription(): String = "Excalidraw diagram file"

    /** Primary extension without the leading dot. */
    override fun getDefaultExtension(): String = "excalidraw"

    /** Excalidraw file icon shown in the Project view, editor tabs and settings. */
    override fun getIcon(): Icon = ExcalidrawIcons.FILE

    /** Not a binary format from the platform's perspective. */
    override fun isBinary(): Boolean = false
}
