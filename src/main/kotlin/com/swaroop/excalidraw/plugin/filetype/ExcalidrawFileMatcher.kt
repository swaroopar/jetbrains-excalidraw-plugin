package com.swaroop.excalidraw.plugin.filetype

/**
 * Single owner of "what is an Excalidraw file": the default accepted-extension
 * set and the one match rule (case-insensitive suffix match) used everywhere a
 * filename is checked against it.
 *
 * Before this existed, the extension list and match rule were copied across
 * [com.swaroop.excalidraw.plugin.settings.ExcalidrawExtensionSettings],
 * [com.swaroop.excalidraw.plugin.editor.ExcalidrawFileEditorProvider], and
 * [com.swaroop.excalidraw.plugin.editor.ExcalidrawFileEditor] — and two of those
 * copies disagreed on casing ([String.endsWith] case-sensitive vs
 * case-insensitive), so the same filename could be accepted by one code path and
 * rejected by another. Every call site here delegates to [matches] /
 * [isExcalidrawPng], so there is exactly one casing policy.
 */
object ExcalidrawFileMatcher {

    /**
     * The default set of file-name suffixes recognised as Excalidraw files.
     *
     * Order is not significant to [matches] — [String.endsWith] unambiguously
     * distinguishes ".excalidraw" from ".excalidraw.png" regardless of which is
     * checked first. This order is the one surfaced to users (e.g. in the
     * settings list), so it stays stable.
     */
    val DEFAULT_EXTENSIONS: List<String> = listOf(
        ".excalidraw",
        ".excalidraw.png",
    )

    /**
     * Returns `true` if [fileName] ends with one of [extensions] (default:
     * [DEFAULT_EXTENSIONS]), matched case-insensitively.
     *
     * Case-insensitive matching handles both macOS (case-insensitive FS) and
     * Linux CI (case-sensitive FS) without surprises, and is the one policy
     * every call site — provider `accept()`, settings, PNG detection — shares.
     */
    fun matches(fileName: String, extensions: List<String> = DEFAULT_EXTENSIONS): Boolean =
        extensions.any { suffix -> fileName.endsWith(suffix, ignoreCase = true) }

    /**
     * Returns `true` when [fileName] identifies a scene-embedded PNG file
     * (case-insensitive `.excalidraw.png` suffix).
     */
    fun isExcalidrawPng(fileName: String): Boolean =
        fileName.endsWith(".excalidraw.png", ignoreCase = true)
}
