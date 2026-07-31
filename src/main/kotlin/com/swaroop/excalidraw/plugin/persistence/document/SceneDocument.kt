package com.swaroop.excalidraw.plugin.persistence.document

import com.intellij.openapi.vfs.VirtualFile
import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge
import com.swaroop.excalidraw.plugin.persistence.Scene

/**
 * Owns "how do I load and save this file's scene" for one on-disk scene format.
 *
 * Each on-disk representation of an Excalidraw scene (plain `.excalidraw` JSON,
 * scene-embedded `.excalidraw.png`) implements this once, concentrating the
 * format-specific I/O and bridge choreography that previously forked on
 * `isExcalidrawPng(...)` in three separate places inside `ExcalidrawFileEditor`.
 *
 * The editor drives an instance of this polymorphically and never branches on
 * file format itself; it only interprets the sealed result types.
 */
interface SceneDocument {

    /**
     * Loads [file]'s scene into [bridge] (installing the return channel and
     * clipboard bridge as part of the load), then reports the outcome via [onResult].
     *
     * May complete synchronously (plain JSON) or asynchronously after a bridge
     * round-trip (PNG extraction) — callers must not assume [onResult] has already
     * fired when this method returns.
     */
    fun load(file: VirtualFile, bridge: ExcalidrawJsBridge, onResult: (SceneLoadResult) -> Unit)

    /**
     * Persists [scene] — the latest scene received from the bridge — to [file],
     * then reports the outcome via [onResult].
     *
     * May complete synchronously (plain JSON write) or asynchronously after a
     * bridge round-trip (PNG re-export).
     */
    fun save(file: VirtualFile, scene: Scene, bridge: ExcalidrawJsBridge, onResult: (SceneSaveResult) -> Unit)
}

/** Outcome of a [SceneDocument.load] call. */
sealed class SceneLoadResult {

    /**
     * The scene was pushed to the canvas as [scene], and this format's own load
     * protocol has already reconciled the canvas against the file — the caller may
     * seed its change-detection baseline from [scene] immediately without
     * waiting for a render echo.
     */
    data class LoadedAndBaselined(val scene: Scene) : SceneLoadResult()

    /**
     * The scene was pushed to the canvas; the canvas will fire its own render/onChange
     * echo once mounted, and the caller should treat *that* as the baseline rather than
     * this call — this format has no separate round-trip to consult up front.
     */
    data object LoadedAwaitingEcho : SceneLoadResult()

    /** Loading failed; [message] is a human-readable, UI-safe description. */
    data class Failed(val message: String) : SceneLoadResult()
}

/** Outcome of a [SceneDocument.save] call. */
sealed class SceneSaveResult {

    /** The scene was written to disk. */
    data object Saved : SceneSaveResult()

    /** The write failed; [message] is a human-readable description. No write occurred. */
    data class Failed(val message: String) : SceneSaveResult()

    /**
     * The save request was a no-op (e.g. a PNG save requested before its load round-trip
     * armed itself) — the caller must not touch modified-state or log a warning; this is
     * an expected, silent skip, not an error.
     */
    data object Skipped : SceneSaveResult()
}
