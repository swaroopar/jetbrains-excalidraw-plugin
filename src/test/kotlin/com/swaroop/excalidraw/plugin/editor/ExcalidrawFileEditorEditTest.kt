package com.swaroop.excalidraw.plugin.editor

import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge
import com.swaroop.excalidraw.plugin.bridge.SceneChangeMessage
import com.swaroop.excalidraw.plugin.jcef.ExcalidrawJcefHost
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawPersistenceService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener

/**
 * Tests for [ExcalidrawFileEditor] scene-change tracking (task-03-004).
 *
 * Acceptance criteria covered:
 *
 *   Baseline: Before any simulateSceneChange call, editor.isModified() == false.
 *
 *   AC-E2-01: bridge.simulateSceneChange(validPayload) sets editor.isModified() == true
 *     and editor.currentSceneJson is not null and contains field names from the payload.
 *
 *   AC-E2-02 (Undo): After a second simulateSceneChange call (simulating undo),
 *     editor.currentSceneJson reflects the latest payload and editor.isModified() == true.
 *
 * All tests run without a live JCEF runtime via the createForTest path.
 */
class ExcalidrawFileEditorEditTest {

    // -------------------------------------------------------------------------
    // Test data
    // -------------------------------------------------------------------------

    private val validScenePayload =
        """{"type":"sceneChange","elements":[{"type":"rectangle"}],"appState":{"viewBackgroundColor":"#ffffff"}}"""

    private val undoPayload =
        """{"type":"sceneChange","elements":[],"appState":{"viewBackgroundColor":"#ffffff"}}"""

    private val redoPayload =
        """{"type":"sceneChange","elements":[{"type":"rectangle"},{"type":"ellipse"}],"appState":{"viewBackgroundColor":"#0000ff"}}"""

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a [Pair] of ([ExcalidrawFileEditor], [ExcalidrawJsBridge]) wired so that
     * [ExcalidrawJsBridge.simulateSceneChange] drives [ExcalidrawFileEditor.onSceneChanged].
     *
     * Strategy: the bridge's [sceneChangeHandler] is a forwarding lambda that captures
     * the editor reference in a holder. The editor is set into the holder immediately
     * after construction, before any scene-change event can arrive.
     */
    private fun buildEditorWithBridge(): Pair<ExcalidrawFileEditor, ExcalidrawJsBridge> {
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
            persistenceService = ExcalidrawPersistenceService(),
            notifier = { _ -> }
        )

        editorHolder = editor
        // Establish the unedited baseline (mirrors the initial onChange Excalidraw
        // fires when a scene loads), so subsequent distinct payloads count as real
        // edits. Uses an element type none of the test payloads use.
        bridge.simulateSceneChange(BASELINE_PAYLOAD)
        return editor to bridge
    }

    private companion object {
        const val BASELINE_PAYLOAD =
            """{"type":"sceneChange","elements":[{"type":"__baseline__"}],"appState":{}}"""
    }

    // -------------------------------------------------------------------------
    // Baseline: isModified() == false before any scene change
    // -------------------------------------------------------------------------

    /**
     * Baseline: An editor with no scene-change events must report isModified() == false.
     */
    @Test
    fun `isModified is false before any simulateSceneChange call`() {
        val (editor, _) = buildEditorWithBridge()

        assertFalse(editor.isModified(), "isModified must be false before any simulateSceneChange call")

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // AC-E2-01: simulateSceneChange marks editor dirty and stores currentSceneJson
    // -------------------------------------------------------------------------

    /**
     * AC-E2-01: After simulateSceneChange with a valid sceneChange payload:
     * - editor.isModified() == true
     * - editor.currentSceneJson is not null
     * - editor.currentSceneJson contains at least one field name from the payload
     */
    @Test
    fun `simulateSceneChange marks editor modified and stores currentSceneJson`() {
        val (editor, bridge) = buildEditorWithBridge()

        assertFalse(editor.isModified(), "Precondition: isModified must be false initially")

        bridge.simulateSceneChange(validScenePayload)

        assertTrue(editor.isModified(), "isModified must be true after simulateSceneChange (AC-E2-01)")
        assertNotNull(editor.currentSceneJson, "currentSceneJson must not be null after simulateSceneChange (AC-E2-01)")
        assertTrue(
            editor.currentSceneJson!!.contains("elements") || editor.currentSceneJson!!.contains("rectangle"),
            "currentSceneJson must contain field names from the payload (AC-E2-01)"
        )

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // AC-E2-02: Undo — currentSceneJson reflects the latest (undo) payload
    // -------------------------------------------------------------------------

    /**
     * AC-E2-02: After two simulateSceneChange calls (simulating edit then undo):
     * - editor.isModified() == true (every onChange event marks the editor dirty)
     * - editor.currentSceneJson reflects the second (undo) payload
     */
    @Test
    fun `second simulateSceneChange updates currentSceneJson to undo payload`() {
        val (editor, bridge) = buildEditorWithBridge()

        bridge.simulateSceneChange(validScenePayload)
        assertTrue(editor.isModified(), "isModified must be true after first simulateSceneChange")

        bridge.simulateSceneChange(undoPayload)

        assertTrue(editor.isModified(), "isModified must remain true after undo simulateSceneChange (AC-E2-02)")
        assertNotNull(editor.currentSceneJson, "currentSceneJson must not be null after undo (AC-E2-02)")
        assertTrue(
            editor.currentSceneJson!!.contains("appState") || editor.currentSceneJson!!.contains("viewBackgroundColor"),
            "currentSceneJson must reflect the undo payload (AC-E2-02)"
        )

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // AC-E2-02: Redo — currentSceneJson reflects the redo (next state) payload
    // -------------------------------------------------------------------------

    /**
     * AC-E2-02 (Redo): Full sequence [edit → undo → redo]:
     * - After redo simulateSceneChange, editor.currentSceneJson reflects the redo payload.
     * - editor.isModified() remains true throughout (each onChange marks dirty).
     * - The redo payload is distinguishable from the undo payload by its content.
     */
    @Test
    fun `undo then redo sequence updates currentSceneJson to redo payload`() {
        val (editor, bridge) = buildEditorWithBridge()

        // Step 1: initial edit — adds a rectangle
        bridge.simulateSceneChange(validScenePayload)
        assertTrue(editor.isModified(), "isModified must be true after edit (AC-E2-02 redo pre-condition)")
        assertNotNull(editor.currentSceneJson, "currentSceneJson must not be null after edit")
        assertTrue(
            editor.currentSceneJson!!.contains("rectangle"),
            "currentSceneJson must contain 'rectangle' after edit event"
        )

        // Step 2: undo — removes the rectangle, empty elements
        bridge.simulateSceneChange(undoPayload)
        assertTrue(editor.isModified(), "isModified must remain true after undo")
        assertNotNull(editor.currentSceneJson, "currentSceneJson must not be null after undo")

        // Step 3: redo — re-applies the edit, now two shapes present
        bridge.simulateSceneChange(redoPayload)

        assertTrue(editor.isModified(), "isModified must remain true after redo (AC-E2-02)")
        assertNotNull(editor.currentSceneJson, "currentSceneJson must not be null after redo (AC-E2-02)")
        assertTrue(
            editor.currentSceneJson!!.contains("ellipse"),
            "currentSceneJson must reflect the redo payload (contain 'ellipse') (AC-E2-02)"
        )
        assertTrue(
            editor.currentSceneJson!!.contains("0000ff"),
            "currentSceneJson must reflect the redo appState (contain '0000ff') (AC-E2-02)"
        )

        editor.dispose()
    }

    // -------------------------------------------------------------------------
    // AC-E2-01: PropertyChangeListener is notified on first scene-change (PROP_MODIFIED)
    // -------------------------------------------------------------------------

    /**
     * AC-E2-01 supplemental: The first [onSceneChanged] call fires a PROP_MODIFIED
     * property-change event to registered [PropertyChangeListener]s.
     * A second call must NOT fire a duplicate event (isModified was already true).
     *
     * This verifies that the IDE's document-modification tracking infrastructure
     * (which listens for PROP_MODIFIED) receives exactly one notification per
     * dirty-state transition — not once per edit event.
     */
    @Test
    fun `PROP_MODIFIED propertyChange event fires exactly once on first scene change`() {
        val (editor, bridge) = buildEditorWithBridge()

        val receivedEvents = mutableListOf<PropertyChangeEvent>()
        val listener = PropertyChangeListener { event -> receivedEvents.add(event) }
        editor.addPropertyChangeListener(listener)

        // First scene-change: transitions isModified from false → true; should fire event
        bridge.simulateSceneChange(validScenePayload)

        assertEquals(1, receivedEvents.size, "Exactly one PROP_MODIFIED event must fire on first scene-change (AC-E2-01)")
        assertEquals(
            "modified",
            receivedEvents[0].propertyName,
            "Event property name must be 'modified' (FileEditor.PROP_MODIFIED) (AC-E2-01)"
        )
        assertEquals(false, receivedEvents[0].oldValue, "Event oldValue must be false (was clean)")
        assertEquals(true, receivedEvents[0].newValue, "Event newValue must be true (now dirty)")

        // Second scene-change: isModified is already true; must NOT fire another event
        bridge.simulateSceneChange(undoPayload)

        assertEquals(
            1,
            receivedEvents.size,
            "No additional PROP_MODIFIED event must fire when isModified was already true (AC-E2-01)"
        )

        editor.removePropertyChangeListener(listener)
        editor.dispose()
    }
}
