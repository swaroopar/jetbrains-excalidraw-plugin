package com.swaroop.excalidraw.plugin.editor.autosave

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.swaroop.excalidraw.plugin.persistence.Scene
import com.swaroop.excalidraw.plugin.persistence.document.SceneSaveResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AutosaveController] — no [com.swaroop.excalidraw.plugin.editor.ExcalidrawFileEditor],
 * no bridge, no persistence subclassing. Exercises the controller directly through its own
 * interface with a [ManualScheduler] and a fake `write` callback, using [Scene] fixtures.
 */
class AutosaveControllerTest {

    private fun sceneOf(vararg ids: String): Scene = Scene(
        type = "excalidraw",
        version = 2,
        source = null,
        elements = JsonArray().apply { ids.forEach { add(JsonObject().apply { addProperty("id", it) }) } },
        appState = JsonObject(),
        files = null
    )

    /** Same element ids as [sceneOf] but with churn fields added — must be content-equal. */
    private fun churnedSceneOf(vararg ids: String): Scene = Scene(
        type = "excalidraw",
        version = 2,
        source = null,
        elements = JsonArray().apply {
            ids.forEach {
                add(JsonObject().apply {
                    addProperty("id", it)
                    addProperty("version", 7)
                    addProperty("versionNonce", 12345)
                    addProperty("updated", 999999L)
                })
            }
        },
        appState = JsonObject(),
        files = null
    )

    private class Harness {
        val scheduler = ManualScheduler()
        val modifiedEvents = mutableListOf<Boolean>()
        var writeCallCount = 0
        var lastWrittenScene: Scene? = null
        var nextWriteResult: SceneSaveResult = SceneSaveResult.Saved

        val controller = AutosaveController(
            scheduler = scheduler,
            debounceMs = 500L,
            onModifiedChanged = { modifiedEvents.add(it) },
            write = { scene, onResult ->
                writeCallCount++
                lastWrittenScene = scene
                onResult(nextWriteResult)
            }
        )
    }

    // -------------------------------------------------------------------------
    // Unarmed: events dropped entirely
    // -------------------------------------------------------------------------

    @Test
    fun `onSceneChanged is dropped entirely before arm or awaitEcho`() {
        val h = Harness()

        h.controller.onSceneChanged(sceneOf("a"))

        assertFalse(h.controller.isModified)
        assertEquals(null, h.controller.currentScene)
        assertEquals(0, h.writeCallCount)
    }

    // -------------------------------------------------------------------------
    // awaitEcho: first onSceneChanged establishes baseline, no write
    // -------------------------------------------------------------------------

    @Test
    fun `after awaitEcho the first onSceneChanged establishes baseline without marking modified`() {
        val h = Harness()
        h.controller.awaitEcho()

        val scene = sceneOf("a")
        h.controller.onSceneChanged(scene)

        assertFalse(h.controller.isModified, "the load echo must not count as an edit")
        assertEquals(scene, h.controller.currentScene)
        assertTrue(h.modifiedEvents.isEmpty())
        assertEquals(null, h.scheduler.pending, "no write must be scheduled for the echo")
    }

    // -------------------------------------------------------------------------
    // arm: seeds baseline + currentScene immediately
    // -------------------------------------------------------------------------

    @Test
    fun `arm seeds currentScene and baseline immediately`() {
        val h = Harness()
        val scene = sceneOf("a")

        h.controller.arm(scene)

        assertEquals(scene, h.controller.currentScene)
        assertFalse(h.controller.isModified)

        // A subsequent onSceneChanged with the SAME element content is a no-op re-render.
        h.controller.onSceneChanged(sceneOf("a"))
        assertFalse(h.controller.isModified, "matching the armed baseline must not count as an edit")
        assertTrue(h.modifiedEvents.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Genuine edit: marks modified, fires PROP_MODIFIED once, schedules a write
    // -------------------------------------------------------------------------

    @Test
    fun `a genuine content change marks modified, fires the event once, and schedules a write`() {
        val h = Harness()
        h.controller.arm(sceneOf())

        h.controller.onSceneChanged(sceneOf("a"))

        assertTrue(h.controller.isModified)
        assertEquals(listOf(true), h.modifiedEvents, "exactly one false->true transition event")
        assertEquals(0, h.writeCallCount, "write must not run before the scheduler flushes")

        // A second distinct change before the flush must not fire another modified event
        // (already true) — debounce still consolidates to one scheduled write.
        val latest = sceneOf("a", "b")
        h.controller.onSceneChanged(latest)
        assertEquals(listOf(true), h.modifiedEvents, "no duplicate event while already modified")

        h.scheduler.flush()

        assertEquals(1, h.writeCallCount, "debounce must consolidate rapid changes to one write")
        assertEquals(latest, h.lastWrittenScene)
    }

    // -------------------------------------------------------------------------
    // Element-content equality ignores churn fields
    // -------------------------------------------------------------------------

    @Test
    fun `version, versionNonce and updated churn does not count as an edit`() {
        val h = Harness()
        h.controller.arm(sceneOf("a"))

        h.controller.onSceneChanged(churnedSceneOf("a"))

        assertFalse(h.controller.isModified, "churn-only fields must not register as content changes")
        assertEquals(0, h.writeCallCount)
    }

    // -------------------------------------------------------------------------
    // Write result handling: Saved / Failed / Skipped
    // -------------------------------------------------------------------------

    @Test
    fun `Saved result clears modified and fires the false transition event`() {
        val h = Harness()
        h.nextWriteResult = SceneSaveResult.Saved
        h.controller.arm(sceneOf())
        h.controller.onSceneChanged(sceneOf("a"))

        h.scheduler.flush()

        assertFalse(h.controller.isModified)
        assertEquals(listOf(true, false), h.modifiedEvents)
    }

    @Test
    fun `Failed result leaves modified true and does not fire another event`() {
        val h = Harness()
        h.nextWriteResult = SceneSaveResult.Failed("disk full")
        h.controller.arm(sceneOf())
        h.controller.onSceneChanged(sceneOf("a"))

        h.scheduler.flush()

        assertTrue(h.controller.isModified, "a failed write must not clear the dirty flag")
        assertEquals(listOf(true), h.modifiedEvents, "no additional event on failure")
    }

    @Test
    fun `Skipped result leaves modified true and does not fire another event`() {
        val h = Harness()
        h.nextWriteResult = SceneSaveResult.Skipped
        h.controller.arm(sceneOf())
        h.controller.onSceneChanged(sceneOf("a"))

        h.scheduler.flush()

        assertTrue(h.controller.isModified)
        assertEquals(listOf(true), h.modifiedEvents)
    }

    // -------------------------------------------------------------------------
    // dispose: cancels the scheduler, no write after dispose
    // -------------------------------------------------------------------------

    @Test
    fun `dispose cancels the pending scheduled write`() {
        val h = Harness()
        h.controller.arm(sceneOf())
        h.controller.onSceneChanged(sceneOf("a"))

        h.controller.dispose()
        h.scheduler.flush()

        assertEquals(0, h.writeCallCount, "dispose must cancel the scheduled write before it fires")
    }

    @Test
    fun `no scene change ever results in no scheduled write`() {
        val h = Harness()
        h.controller.arm(sceneOf())

        h.scheduler.flush()

        assertEquals(0, h.writeCallCount)
    }
}
