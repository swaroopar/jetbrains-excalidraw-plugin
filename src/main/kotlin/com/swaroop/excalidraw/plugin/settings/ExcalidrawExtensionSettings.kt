package com.swaroop.excalidraw.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level persistent service that holds the list of file extensions
 * treated as Excalidraw files.
 *
 * Architecture decisions:
 *   ADR-E7-01: Application-Level PersistentStateComponent — extension list is
 *     IDE-wide, not project-specific.
 *   ADR-E7-02: Normalization (leading dot, lowercase, deduplication) happens here,
 *     not in the UI layer, so programmatic access also gets normalized values.
 *
 * The state is persisted to `excalidraw.xml` via the IntelliJ platform XML
 * serializer (AC-E7-04). No direct file I/O is performed.
 */
@Service(Service.Level.APP)
@State(
    name = "ExcalidrawExtensionSettings",
    storages = [Storage("excalidraw.xml")]
)
class ExcalidrawExtensionSettings : PersistentStateComponent<ExcalidrawExtensionSettings.State> {

    /**
     * Serializable state holder.
     *
     * A plain class (not data class) with a no-arg constructor is required so
     * the IntelliJ XML deserializer can instantiate it reflectively. The default
     * value of [extensions] satisfies AC-E7-02.
     */
    class State {
        var extensions: MutableList<String> = mutableListOf(".excalidraw", ".excalidraw.png")
    }

    private var state: State = State()

    // -----------------------------------------------------------------------
    // PersistentStateComponent contract
    // -----------------------------------------------------------------------

    override fun getState(): State = state

    override fun loadState(incoming: State) {
        val normalized = incoming.extensions
            .mapNotNull { normalize(it).ifEmpty { null } }
            .distinct()
        state = State().also { it.extensions = normalized.toMutableList() }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns an immutable snapshot of the current extension list (AC-E7-02).
     */
    fun getExtensions(): List<String> = state.extensions.toList()

    /**
     * Adds [ext] to the list after normalization, ignoring duplicates.
     *
     * Empty strings (or strings that become empty after trimming) are silently
     * skipped — not an error, just a no-op.
     */
    fun addExtension(ext: String) {
        val normalized = normalize(ext)
        if (normalized.isEmpty()) return
        if (!state.extensions.contains(normalized)) {
            state.extensions.add(normalized)
        }
    }

    /**
     * Removes the normalized form of [ext] from the list.
     *
     * If the extension is not present the call is a no-op.
     */
    fun removeExtension(ext: String) {
        val normalized = normalize(ext)
        state.extensions.remove(normalized)
    }

    /**
     * Replaces the entire extension list with [list] (after normalizing each entry
     * and removing duplicates).
     */
    fun setExtensions(list: List<String>) {
        val normalized = list
            .mapNotNull { normalize(it).ifEmpty { null } }
            .distinct()
        state.extensions = normalized.toMutableList()
    }

    // -----------------------------------------------------------------------
    // Companion
    // -----------------------------------------------------------------------

    companion object {
        /**
         * Returns the application-level service instance.
         *
         * Returns `null` when no Application context is available (e.g. unit tests
         * that do not bootstrap the IDE platform). Callers must handle the nullable
         * result.
         */
        fun getInstance(): ExcalidrawExtensionSettings =
            ApplicationManager.getApplication().getService(ExcalidrawExtensionSettings::class.java)
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Normalizes a file extension:
     * 1. Trim surrounding whitespace.
     * 2. Return empty string for blank input (caller skips it).
     * 3. Ensure a leading dot (e.g. `"png"` → `".png"`).
     * 4. Convert to lowercase.
     */
    private fun normalize(ext: String): String {
        val trimmed = ext.trim()
        if (trimmed.isEmpty()) return ""
        val dotted = if (trimmed.startsWith(".")) trimmed else ".$trimmed"
        return dotted.lowercase()
    }
}
