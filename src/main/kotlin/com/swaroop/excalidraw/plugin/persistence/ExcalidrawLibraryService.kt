package com.swaroop.excalidraw.plugin.persistence

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level persistence for the Excalidraw library.
 *
 * Excalidraw normally persists the library in the browser's IndexedDB, but the plugin's
 * webview is served from the opaque `excalidraw://` origin where Chromium disables
 * IndexedDB — so added libraries vanish on restart. We instead persist the full library
 * items JSON here (libraries are global in Excalidraw, not per-file), restoring it into
 * the editor on open.
 *
 * Stored under the IDE config dir (`excalidraw-library.xml`).
 */
@Service(Service.Level.APP)
@State(name = "ExcalidrawLibrary", storages = [Storage("excalidraw-library.xml")])
class ExcalidrawLibraryService : PersistentStateComponent<ExcalidrawLibraryService.State> {

    class State {
        /** Full library items as a JSON array string; empty means "no saved library". */
        @JvmField
        var libraryItemsJson: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** The saved library items JSON array, or null if nothing has been saved yet. */
    var libraryItemsJson: String?
        get() = state.libraryItemsJson.ifBlank { null }
        set(value) {
            state.libraryItemsJson = value ?: ""
        }

    companion object {
        fun getInstance(): ExcalidrawLibraryService = service()
    }
}
