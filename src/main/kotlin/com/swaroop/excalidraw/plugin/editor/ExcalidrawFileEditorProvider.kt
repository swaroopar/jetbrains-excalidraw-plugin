package com.swaroop.excalidraw.plugin.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.serviceContainer.NonInjectable
import com.swaroop.excalidraw.plugin.settings.ExcalidrawExtensionSettings

/**
 * Production supplier of the configured extension list, used by the no-arg constructor.
 *
 * A top-level function (rather than a lambda in the constructor delegation) so it can
 * reference [ExcalidrawFileEditorProvider.DEFAULT_SUFFIXES] without tripping Kotlin's
 * "cannot access <this> before initialization" rule in a `this(...)` delegation call.
 *
 * The [runCatching] guard keeps headless / test contexts (no Application container) from
 * crashing; an empty service list falls back to the defaults.
 */
private fun configuredExtensionsOrDefault(): List<String> =
    runCatching { ExcalidrawExtensionSettings.getInstance().getExtensions() }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?: ExcalidrawFileEditorProvider.DEFAULT_SUFFIXES

/**
 * FileEditorProvider for `.excalidraw` and `.excalidraw.png` files, with
 * configurable extension support (AC-E7-03).
 *
 * Architecture decisions enforced here:
 *   AD-4 (impl-plan): Registered declaratively via plugin.xml extension-point; the IDE
 *     class-loading mechanism discovers and invokes this provider lazily.
 *   AD-5 (impl-plan): Phase 01 scope — the provider instantiates the editor skeleton.
 *   ADR-E7-05: The accepted extension list is read from [ExcalidrawExtensionSettings]
 *     at each [accept] call. A [runCatching] guard ensures the provider falls back to
 *     [DEFAULT_SUFFIXES] when no Application context is available (e.g. unit tests that
 *     do not bootstrap the IntelliJ platform). The [extensionsProvider] lambda is
 *     injectable via [createForTest] for isolated unit testing.
 *
 * The [accept] method performs a pure file-name suffix check. No VirtualFile content is
 * read and no network request is issued (NFR1 compliance).
 *
 * [acceptsFileName] is exposed as a companion-object function so unit tests can exercise
 * the default-suffix accept logic without a live VirtualFile or IDE project instance.
 */
class ExcalidrawFileEditorProvider @NonInjectable constructor(
    /**
     * Supplier of the currently configured extension list.
     *
     * Injected via [createForTest] in unit tests. The IDE platform never calls this
     * constructor — it is marked [@NonInjectable] so the platform's constructor-injection
     * does not try to resolve the `() -> List<String>` parameter (which it cannot), and
     * instead uses the no-arg [constructor] below. Without this, plugin load fails with
     * "getComponentAdapterOfType is used to get kotlin.jvm.functions.Function0 ...".
     */
    private val extensionsProvider: () -> List<String>,
) : FileEditorProvider, DumbAware {

    /**
     * No-arg constructor invoked by the IntelliJ platform when it instantiates this
     * provider from the `<fileEditorProvider>` registration in plugin.xml.
     *
     * Reads the configured extension list from [ExcalidrawExtensionSettings] on each
     * [accept] call, with a [runCatching] guard so headless / test contexts without an
     * Application container do not crash; an empty service list falls back to
     * [DEFAULT_SUFFIXES].
     */
    constructor() : this(::configuredExtensionsOrDefault)

    companion object {
        /**
         * Stable editor-type identifier persisted by the IDE to remember which editor
         * opened a file across sessions. Must be unique within the IDE installation.
         */
        const val EDITOR_TYPE_ID: String = "excalidraw-editor"

        /**
         * The default set of file-name suffixes accepted by this provider.
         *
         * Both ".excalidraw" (plain JSON scene files) and ".excalidraw.png"
         * (scene-embedded PNG, requirement E6) are listed. Longer / more specific
         * suffixes appear first so that a naive iteration over the list resolves
         * ".excalidraw.png" before ".excalidraw", which is safer when used with
         * [String.startsWith]-based logic elsewhere.
         *
         * Note: [String.endsWith] naturally distinguishes ".excalidraw" from
         * ".excalidraw.png" — a file ending in ".excalidraw.png" does NOT end in
         * ".excalidraw", so no ordering concern arises in [accept].
         *
         * Replaces the former `ACCEPTED_SUFFIXES` constant (task-09-004 retrofit).
         * Byte-identical to the old constant — all existing tests remain valid.
         */
        val DEFAULT_SUFFIXES: List<String> = listOf(
            ".excalidraw.png",
            ".excalidraw",
        )

        /**
         * Returns `true` if [fileName] ends with one of the [DEFAULT_SUFFIXES].
         *
         * Exposed for unit testing without a live VirtualFile. Performs a case-sensitive
         * comparison — consistent with how IntelliJ Platform resolves file types on
         * case-sensitive file systems (Linux CI).
         *
         * AC-FileEditorProvider-01: ".excalidraw" suffix -> true
         * AC-FileEditorProvider-02: ".excalidraw.png" suffix -> true (activated in E6/phase-07)
         * AC-FileEditorProvider-03: ".png" only -> false
         * AC-FileEditorProvider-04: ".json" -> false
         */
        fun acceptsFileName(fileName: String): Boolean =
            DEFAULT_SUFFIXES.any { suffix -> fileName.endsWith(suffix) }

        /**
         * Creates a provider instance whose [accept] method uses [extensions] directly,
         * bypassing any [ExcalidrawExtensionSettings] / ApplicationManager lookup.
         *
         * Use this factory in unit tests that must control the accepted extension set
         * without bootstrapping the IntelliJ platform (ADR-E7-05).
         *
         * @param extensions The fixed list of file-name suffixes to accept.
         * @return An [ExcalidrawFileEditorProvider] backed by the supplied list.
         */
        fun createForTest(extensions: List<String>): ExcalidrawFileEditorProvider =
            ExcalidrawFileEditorProvider(extensionsProvider = { extensions })
    }

    /**
     * Returns `true` for files whose name ends with any suffix in the currently
     * configured extension list (AC-E7-03).
     *
     * The extension list is obtained from [extensionsProvider] on every call.
     * In production this reads [ExcalidrawExtensionSettings]; in tests it uses
     * the list injected via [createForTest].
     *
     * Matching is case-insensitive to handle both macOS (case-insensitive FS)
     * and Linux CI (case-sensitive FS) without surprises.
     *
     * No content is read; no network access (NFR1).
     *
     * @param project The current project — retained for interface compliance.
     * @param file    The VirtualFile being opened.
     */
    override fun accept(project: Project, file: VirtualFile): Boolean =
        acceptsFile(file)

    /**
     * File-only accept check, usable in unit tests without a live [Project].
     *
     * Delegated to by [accept] so that the extension-matching logic lives in one place.
     * Exposed as `internal` so that [ExcalidrawFileEditorProviderExtensionsTest] can call
     * it directly through [createForTest] instances without requiring an IDE project stub.
     */
    internal fun acceptsFile(file: VirtualFile): Boolean {
        val suffixes = extensionsProvider()
        return suffixes.any { file.name.endsWith(it, ignoreCase = true) }
    }

    /**
     * Instantiates and returns an [ExcalidrawFileEditor] for the given file.
     *
     * Called by the IDE platform only after [accept] has returned `true`.
     *
     * AD-3: The returned [ExcalidrawFileEditor] registers its [ExcalidrawJcefHost]
     * as a child-Disposable, ensuring a clean dispose chain when the editor is closed.
     *
     * @param project The current project — passed to [ExcalidrawFileEditor] for future
     *   use in Phase 02 (VFS write-actions require a project context).
     * @param file    The VirtualFile to display.
     */
    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        ExcalidrawFileEditor(project, file)

    /**
     * Returns the stable editor-type identifier for this provider.
     *
     * The IDE uses this string to persist and restore the user's editor choice per file.
     */
    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    /**
     * Returns [FileEditorPolicy.HIDE_DEFAULT_EDITOR] so that the Excalidraw editor is
     * shown exclusively for the matched file types, hiding the generic text/binary editor.
     *
     * This matches the behaviour of the VSCode Excalidraw extension, where the custom
     * editor replaces the default viewer entirely.
     */
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
