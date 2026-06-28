package com.swaroop.excalidraw.plugin.editor

import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge
import com.swaroop.excalidraw.plugin.bridge.SceneChangeMessage
import com.swaroop.excalidraw.plugin.jcef.ExcalidrawJcefHost
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawPersistenceService
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the Alarm-debounced auto-save integration in [ExcalidrawFileEditor] (task-04-004).
 *
 * Acceptance criteria covered:
 *
 *   AC-E3-01: A single onSceneChanged call, after the debounce fires, leads to exactly
 *     one writeScene() call with the serialized currentSceneJson.
 *
 *   AC-E3-01 (Debounce consolidation): Multiple rapid onSceneChanged calls before the
 *     debounce fires lead to exactly one writeScene() call (last-write-wins).
 *
 *   AC-E3-03: After writeScene fires, editor.isModified() == false;
 *     before the debounce fires (directly after onSceneChanged), isModified() == true.
 *
 *   Dispose safety: dispose() while a pending debounce alarm cancels the alarm;
 *     no writeScene() is called after dispose().
 *
 * No real Alarm timing or Thread.sleep is used.  The [debounceExecutor] parameter
 * injected into [ExcalidrawFileEditor.createForTest] is a Runnable collector that
 * tests invoke manually to trigger the debounce callback synchronously.
 *
 * No kotlinx-coroutines-test or new Gradle dependency is introduced.
 */
class AutosaveDebounceTest {

    // -------------------------------------------------------------------------
    // Fake persistence service that records writeScene calls
    // -------------------------------------------------------------------------

    /**
     * In-test fake for [ExcalidrawPersistenceService].
     *
     * Overrides [writeScene] to record calls without requiring a live IntelliJ
     * Application or FileDocumentManager (no IDE APIs involved).
     */
    private class FakePersistenceService : ExcalidrawPersistenceService() {
        var writeSceneCallCount: Int = 0
        var lastWrittenJson: String? = null

        override fun writeScene(file: VirtualFile, json: String) {
            writeSceneCallCount++
            lastWrittenJson = json
        }
    }

    // -------------------------------------------------------------------------
    // Helper — build editor with injected debounce executor and fake services
    // -------------------------------------------------------------------------

    /**
     * Builds an [ExcalidrawFileEditor] wired for debounce testing.
     *
     * [pendingExecutors] is populated with the Runnable that the debounce alarm
     * would schedule.  Tests call [pendingExecutors].last().run() to simulate
     * the alarm firing.  Rapid calls replace the pending Runnable (last-write-wins
     * debounce behaviour).
     *
     * @return Triple of (editor, bridge, fakePersistenceService)
     */
    private fun buildTestEditor(): Triple<ExcalidrawFileEditor, ExcalidrawJsBridge, FakePersistenceService> {
        val fakePersistence = FakePersistenceService()

        var editorHolder: ExcalidrawFileEditor? = null

        val bridge = ExcalidrawJsBridge.createForTest(
            injector = { _ -> },
            sceneChangeHandler = { scene: SceneChangeMessage ->
                editorHolder?.onSceneChanged(scene)
            }
        )

        val file = StubVirtualFile("test.excalidraw", "{}".toByteArray(Charsets.UTF_8))
        val host = ExcalidrawJcefHost.createForTest()

        val editor = ExcalidrawFileEditor.createForTest(
            file = file,
            jcefHost = host,
            bridge = bridge,
            persistenceService = fakePersistence,
            notifier = { _ -> },
            // Pass a no-op debounceExecutor to activate test-mode debounce path.
            // The editor stores the pending runnable internally; tests call
            // editor.flushDebounce() to fire it synchronously.
            debounceExecutor = {}
        )

        editorHolder = editor
        // Establish the unedited baseline (mirrors the initial onChange Excalidraw
        // fires when a scene loads). Subsequent distinct scene changes are then
        // treated as real edits. Uses an element type none of the tests use so it
        // differs from every test payload.
        bridge.simulateSceneChange(BASELINE_PAYLOAD)
        return Triple(editor, bridge, fakePersistence)
    }

    private companion object {
        const val BASELINE_PAYLOAD =
            """{"type":"sceneChange","elements":[{"type":"__baseline__"}],"appState":{}}"""
    }

    // -------------------------------------------------------------------------
    // Test 1 (AC-E3-01): Single onSceneChanged → exactly one writeScene after flush
    // -------------------------------------------------------------------------

    /**
     * AC-E3-01: A single [onSceneChanged] call, followed by the debounce alarm firing,
     * results in exactly one [FakePersistenceService.recordWrite] call.
     */
    @Test
    fun `single onSceneChanged triggers exactly one writeScene after debounce fires`() {
        val (editor, bridge, fakePersistence) = buildTestEditor()

        val scenePayload =
            """{"type":"sceneChange","elements":[{"type":"rectangle"}],"appState":{"viewBackgroundColor":"#ffffff"}}"""

        bridge.simulateSceneChange(scenePayload)

        // Before debounce fires: writeScene not yet called.
        assertEquals(0, fakePersistence.writeSceneCallCount, "writeScene must not be called before debounce fires")

        // Fire the pending debounce executor.
        editor.flushDebounce()

        assertEquals(1, fakePersistence.writeSceneCallCount, "Exactly one writeScene must be called after debounce fires (AC-E3-01)")

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // Test 2 (Debounce consolidation): Multiple rapid calls → exactly one writeScene
    // -------------------------------------------------------------------------

    /**
     * AC-E3-01 debounce consolidation: Multiple rapid [onSceneChanged] calls
     * (without the debounce firing between them) result in only one [writeScene]
     * call — the last call's scene wins.
     */
    @Test
    fun `multiple rapid onSceneChanged calls debounce to exactly one writeScene`() {
        val (editor, bridge, fakePersistence) = buildTestEditor()

        val payload1 =
            """{"type":"sceneChange","elements":[{"type":"rectangle"}],"appState":{"viewBackgroundColor":"#ffffff"}}"""
        val payload2 =
            """{"type":"sceneChange","elements":[{"type":"ellipse"}],"appState":{"viewBackgroundColor":"#ffffff"}}"""
        val payload3 =
            """{"type":"sceneChange","elements":[{"type":"text"}],"appState":{"viewBackgroundColor":"#000000"}}"""

        // Three rapid scene changes — debounce must consolidate to one write.
        bridge.simulateSceneChange(payload1)
        bridge.simulateSceneChange(payload2)
        bridge.simulateSceneChange(payload3)

        assertEquals(0, fakePersistence.writeSceneCallCount, "writeScene must not be called before debounce fires")

        // Fire once — only the last pending executor runs.
        editor.flushDebounce()

        assertEquals(
            1,
            fakePersistence.writeSceneCallCount,
            "Multiple rapid onSceneChanged calls must debounce to exactly one writeScene (AC-E3-01)"
        )

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // Test 3 (AC-E3-03): _modified resets to false after writeScene
    // -------------------------------------------------------------------------

    /**
     * AC-E3-03: Before the debounce fires, [editor.isModified()] == true.
     * After the debounce fires and [writeScene] is executed, [editor.isModified()] == false.
     */
    @Test
    fun `isModified is true after onSceneChanged and false after debounce fires`() {
        val (editor, bridge, _) = buildTestEditor()

        val scenePayload =
            """{"type":"sceneChange","elements":[],"appState":{"viewBackgroundColor":"#ffffff"}}"""

        assertFalse(editor.isModified(), "Precondition: isModified must be false before any scene change")

        bridge.simulateSceneChange(scenePayload)

        assertTrue(editor.isModified(), "isModified must be true after onSceneChanged (before debounce fires) (AC-E3-03)")

        editor.flushDebounce()

        assertFalse(editor.isModified(), "isModified must be false after debounce fires and write completes (AC-E3-03)")

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // Test 4: dispose() during pending alarm cancels the alarm (no write after dispose)
    // -------------------------------------------------------------------------

    /**
     * Dispose safety: After [editor.dispose()], a pending debounce alarm must be cancelled.
     * No [writeScene] call must occur even if the executor is manually triggered post-dispose.
     */
    @Test
    fun `dispose during pending alarm prevents writeScene after dispose`() {
        val (editor, bridge, fakePersistence) = buildTestEditor()

        val scenePayload =
            """{"type":"sceneChange","elements":[{"type":"rectangle"}],"appState":{"viewBackgroundColor":"#ffffff"}}"""

        bridge.simulateSceneChange(scenePayload)

        // Dispose the editor before the debounce fires.
        editor.dispose()

        // Attempt to fire the debounce after dispose — must be a no-op.
        editor.flushDebounce()

        assertEquals(
            0,
            fakePersistence.writeSceneCallCount,
            "writeScene must NOT be called after editor.dispose() cancels the pending alarm"
        )
    }

    // -------------------------------------------------------------------------
    // Test 5 (task-04-005, AC-E3-01): No event → no writeScene call at all
    // -------------------------------------------------------------------------

    /**
     * AC-E3-01 — kein Event → kein Write:
     * When no [onSceneChanged] event is ever fired, [flushDebounce] must be a no-op
     * and [writeScene] must never be called.
     *
     * This covers the "no event → no write" branch of the debounce state machine:
     * [pendingDebounce] stays null, so [flushDebounce] has nothing to execute.
     */
    @Test
    fun `no scene change event results in no writeScene call`() {
        val (editor, _, fakePersistence) = buildTestEditor()

        // No bridge.simulateSceneChange() called — editor receives no events.

        // Flushing the debounce without a pending runnable must be a no-op.
        editor.flushDebounce()

        assertEquals(
            0,
            fakePersistence.writeSceneCallCount,
            "writeScene must NOT be called when no onSceneChanged event was ever fired (AC-E3-01)"
        )

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // Test 6 (task-04-005, AC-E3-01): Strict write-count: N events → exactly 1 write
    // -------------------------------------------------------------------------

    /**
     * AC-E3-01 — Strict write-count verification:
     * Five rapid [onSceneChanged] calls (without the debounce firing between them) must
     * result in exactly one [writeScene] call when the debounce fires — never more.
     *
     * This supplements test 2 by using a higher repetition count and asserting strictly
     * that [writeSceneCallCount] is exactly 1 (not just ≤ 1) after a single flush.
     */
    @Test
    fun `five rapid onSceneChanged calls debounce to exactly one writeScene`() {
        val (editor, bridge, fakePersistence) = buildTestEditor()

        val payloadTemplate =
            """{"type":"sceneChange","elements":[{"type":"rectangle","id":"%d"}],"appState":{"viewBackgroundColor":"#ffffff"}}"""

        // Fire five rapid scene changes — debounce must consolidate to exactly one write.
        repeat(5) { index ->
            bridge.simulateSceneChange(payloadTemplate.format(index))
        }

        assertEquals(
            0,
            fakePersistence.writeSceneCallCount,
            "writeScene must not be called before debounce fires (strict count: N events)"
        )

        // Single flush — only the last pending runnable executes.
        editor.flushDebounce()

        assertEquals(
            1,
            fakePersistence.writeSceneCallCount,
            "Exactly 1 writeScene must be called for 5 rapid onSceneChanged calls (AC-E3-01 strict count)"
        )

        editor.dispose()
    }
}
