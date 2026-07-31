package com.swaroop.excalidraw.plugin.editor.autosave

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.swaroop.excalidraw.plugin.persistence.document.SceneSaveResult

/**
 * Owns the autosave + change-detection state machine for one open scene: the
 * current scene JSON, the dirty/[isModified] flag, the debounced write trigger,
 * and telling a real user edit apart from a no-op `onChange` (theme re-render,
 * selection, scroll, the initial load echo).
 *
 * Format-agnostic by construction — it knows nothing about `.excalidraw` vs
 * `.excalidraw.png`. The caller (`ExcalidrawFileEditor`) tells it when a load has
 * settled via [arm]/[awaitEcho], feeds it scene-change events via [onSceneChanged],
 * and supplies the actual persistence mechanism via the [write] callback (typically
 * `SceneDocument.save`).
 *
 * @param scheduler debounce seam — [AlarmScheduler] in production, [ManualScheduler] in tests.
 * @param debounceMs delay between the last scene change and the write it triggers.
 * @param onModifiedChanged invoked exactly on each false↔true transition of [isModified].
 * @param write persists [sceneJson][String]; reports [SceneSaveResult] back via its callback.
 */
class AutosaveController(
    private val scheduler: Scheduler,
    private val debounceMs: Long,
    private val onModifiedChanged: (Boolean) -> Unit,
    private val write: (sceneJson: String, onResult: (SceneSaveResult) -> Unit) -> Unit
) {

    companion object {
        private val LOG: Logger = Logger.getInstance(AutosaveController::class.java)
    }

    /** The most-recently received scene state. Null until [arm] or the first [onSceneChanged]. */
    @Volatile
    var currentSceneJson: String? = null
        private set

    @Volatile
    private var baselineElements: String? = null

    /**
     * Whether the load that produced the current [currentSceneJson] has settled — until this
     * is true, [onSceneChanged] drops events entirely (the canvas may hold stale/unrelated
     * state; see [arm]/[awaitEcho]).
     */
    @Volatile
    private var ready: Boolean = false

    @Volatile
    private var modified: Boolean = false

    @Volatile
    private var disposed: Boolean = false

    val isModified: Boolean
        get() = modified

    /**
     * The format's load protocol already reconciled the canvas against the file (e.g. a PNG
     * extraction round-trip) — seed [currentSceneJson] and the change-detection baseline from
     * [sceneJson] immediately and arm event processing.
     */
    fun arm(sceneJson: String) {
        currentSceneJson = sceneJson
        baselineElements = canonicalElements(elementsOf(sceneJson))
        ready = true
    }

    /**
     * The format's load has no separate round-trip to consult (e.g. plain JSON) — arm event
     * processing so the canvas's own first `onChange` establishes the baseline instead (see
     * the `baseline == null` branch in [onSceneChanged]).
     */
    fun awaitEcho() {
        ready = true
    }

    /**
     * Feeds a scene-change event. Dropped entirely if not yet [arm]ed/[awaitEcho]d. Otherwise:
     * the first call after arming establishes the baseline (an echo of the load, not an edit);
     * a call whose element content matches the baseline is a no-op re-render; anything else is
     * a genuine edit, which advances the baseline, flips [isModified] to true (firing
     * [onModifiedChanged] on the false→true transition), and (re)schedules a debounced write.
     */
    fun onSceneChanged(sceneJson: String, elements: JsonArray) {
        if (!ready) {
            LOG.debug("onSceneChanged ignored: controller not yet armed")
            return
        }
        currentSceneJson = sceneJson
        val newCanonical = canonicalElements(elements)
        val baseline = baselineElements
        when {
            baseline == null -> baselineElements = newCanonical
            newCanonical == baseline -> { /* no meaningful content change */ }
            else -> {
                baselineElements = newCanonical
                setModified(true)
                scheduleWrite()
            }
        }
    }

    /** Cancels any pending debounced write; further [onSceneChanged]/writes become no-ops. */
    fun dispose() {
        disposed = true
        scheduler.cancel()
    }

    private fun scheduleWrite() {
        scheduler.schedule(debounceMs) {
            if (disposed) return@schedule
            val json = currentSceneJson ?: return@schedule
            write(json) { result ->
                if (disposed) return@write
                when (result) {
                    SceneSaveResult.Saved -> setModified(false)
                    is SceneSaveResult.Failed ->
                        LOG.warn("AutosaveController: save failed: ${result.message}")
                    SceneSaveResult.Skipped -> { /* expected, silent no-op */ }
                }
            }
        }
    }

    private fun setModified(value: Boolean) {
        if (modified != value) {
            modified = value
            onModifiedChanged(value)
        }
    }

    /**
     * Returns a stable string representation of [elements] for change detection, with
     * per-element fields that churn without a meaningful content change (`version`,
     * `versionNonce`, `updated`) removed. Operates on a deep copy so [elements] is not mutated.
     */
    private fun canonicalElements(elements: JsonArray): String {
        val copy = elements.deepCopy()
        for (element in copy) {
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                obj.remove("version")
                obj.remove("versionNonce")
                obj.remove("updated")
            }
        }
        return copy.toString()
    }

    /**
     * Extracts the `elements` array from a serialised scene JSON string, returning an empty
     * array when the input is null/blank/malformed or has no `elements` field.
     */
    private fun elementsOf(sceneJson: String?): JsonArray {
        if (sceneJson.isNullOrBlank()) return JsonArray()
        return try {
            JsonParser.parseString(sceneJson)
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.getAsJsonArray("elements")
                ?: JsonArray()
        } catch (_: Exception) {
            JsonArray()
        }
    }
}
