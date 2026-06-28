package com.swaroop.excalidraw.plugin.filetype

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Shared icons for the Excalidraw plugin.
 *
 * [FILE] is the 16×16 icon shown in the Project view, editor tabs and file-type
 * settings for `.excalidraw` and `.excalidraw.png` files. Loaded from the bundled
 * `/icons/excalidraw.svg` via [IconLoader] so it scales for HiDPI displays.
 */
object ExcalidrawIcons {
    val FILE: Icon = IconLoader.getIcon("/icons/excalidraw.svg", ExcalidrawIcons::class.java)
}
