package com.swaroop.excalidraw.plugin.export

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWrapper
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.swaroop.excalidraw.plugin.editor.ExcalidrawFileEditor

// ---------------------------------------------------------------------------
// Shared helpers (package-internal — no Reflection, no dynamic dispatch)
// ---------------------------------------------------------------------------

/**
 * Returns true iff [editor] is an [ExcalidrawFileEditor].
 *
 * Package-internal top-level function shared by [ExportSvgAction] and
 * [ExportPngAction].  Extracted so that unit tests in the same package can
 * call it directly without an [AnActionEvent] or IDE runtime.
 *
 * No JCEF / ApplicationManager access — pure type check.
 */
internal fun isEnabledForExcalidrawEditor(editor: FileEditor?): Boolean =
    editor is ExcalidrawFileEditor

/**
 * Shared implementation of [AnAction.update] for both export actions.
 *
 * Disables the action when [event.project] is null or when the selected
 * editor in [FileEditorManager] is not an [ExcalidrawFileEditor].
 */
internal fun updateExportAction(event: AnActionEvent) {
    val project: Project? = event.project
    if (project == null) {
        event.presentation.isEnabled = false
        return
    }
    val selectedEditor: FileEditor? =
        FileEditorManager.getInstance(project).selectedEditor
    event.presentation.isEnabled = isEnabledForExcalidrawEditor(selectedEditor)
}

/**
 * Shared implementation of [AnAction.actionPerformed] for both export actions.
 *
 * Opens a [FileSaverDescriptor] dialog and delegates all export I/O to
 * [ExcalidrawExporter.exportDrawing] — no file I/O in this method.
 *
 * Secure-coding (A03 / File I/O):
 * - Target path is resolved through the IDE file-chooser API — no raw path
 *   construction from user input.
 * - All JS calls are delegated to [ExcalidrawExporter] which uses
 *   [com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge.requestExport]
 *   with Gson-encoded arguments — no eval(), no string concatenation.
 */
internal fun performExportAction(
    event: AnActionEvent,
    format: String,
    fileExtension: String,
    dialogTitle: String,
    log: Logger
) {
    val project: Project? = event.project
    if (project == null) {
        log.warn("ExportAction($format): actionPerformed called with null project — aborting")
        return
    }

    val editor: ExcalidrawFileEditor =
        FileEditorManager.getInstance(project).selectedEditor as? ExcalidrawFileEditor
            ?: run {
                log.warn("ExportAction($format): no ExcalidrawFileEditor active — aborting")
                return
            }

    val descriptor = FileSaverDescriptor(dialogTitle, "", fileExtension)
    val dialog = FileChooserFactory.getInstance()
        .createSaveFileDialog(descriptor, project)

    val wrapper: VirtualFileWrapper = dialog.save(null as VirtualFile?, "export")
        ?: return   // user cancelled

    val targetFile: VirtualFile = wrapper.getVirtualFile(true)
        ?: run {
            log.warn("ExportAction($format): VirtualFileWrapper returned null VirtualFile — aborting")
            return
        }

    // Delegate all I/O to ExcalidrawExporter — no direct file writes here.
    ExcalidrawExporter.create().exportDrawing(
        format = format,
        scale = 1.0,
        bridge = editor.bridge,
        targetFile = targetFile
    )
}

// ---------------------------------------------------------------------------
// AnAction subclasses (public — required by IntelliJ plugin class-loader)
// ---------------------------------------------------------------------------

/**
 * Action that exports the current Excalidraw drawing to an SVG file.
 *
 * Registered in `plugin.xml` under id `Excalidraw.ExportSvg` in the
 * `EditorPopupMenu` group.
 *
 * update(): disabled when no [ExcalidrawFileEditor] is active (see [isEnabledForEditor]).
 * actionPerformed(): opens a save dialog, then delegates to [ExcalidrawExporter].
 */
class ExportSvgAction : AnAction() {

    private val log: Logger = Logger.getInstance(ExportSvgAction::class.java)

    /**
     * Pure logic helper — returns true iff [editor] is an [ExcalidrawFileEditor].
     *
     * Exposed for unit tests in [ExcalidrawExporterTest] so the enabled/disabled
     * rule is verifiable without an [AnActionEvent] or IDE runtime.
     * Delegates to [isEnabledForExcalidrawEditor] — no Reflection.
     */
    internal fun isEnabledForEditor(editor: FileEditor?): Boolean =
        isEnabledForExcalidrawEditor(editor)

    override fun update(event: AnActionEvent) = updateExportAction(event)

    override fun actionPerformed(event: AnActionEvent) =
        performExportAction(event, "svg", "svg", "Export as SVG", log)
}

/**
 * Action that exports the current Excalidraw drawing to a PNG file.
 *
 * Registered in `plugin.xml` under id `Excalidraw.ExportPng` in the
 * `EditorPopupMenu` group.
 *
 * update(): disabled when no [ExcalidrawFileEditor] is active (see [isEnabledForEditor]).
 * actionPerformed(): opens a save dialog, then delegates to [ExcalidrawExporter].
 */
class ExportPngAction : AnAction() {

    private val log: Logger = Logger.getInstance(ExportPngAction::class.java)

    /**
     * Pure logic helper — returns true iff [editor] is an [ExcalidrawFileEditor].
     *
     * Exposed for unit tests in [ExcalidrawExporterTest] so the enabled/disabled
     * rule is verifiable without an [AnActionEvent] or IDE runtime.
     * Delegates to [isEnabledForExcalidrawEditor] — no Reflection.
     */
    internal fun isEnabledForEditor(editor: FileEditor?): Boolean =
        isEnabledForExcalidrawEditor(editor)

    override fun update(event: AnActionEvent) = updateExportAction(event)

    override fun actionPerformed(event: AnActionEvent) =
        performExportAction(event, "png", "png", "Export as PNG", log)
}
