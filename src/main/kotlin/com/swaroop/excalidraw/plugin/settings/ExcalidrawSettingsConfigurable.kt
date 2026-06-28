package com.swaroop.excalidraw.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton

/**
 * IDE settings panel for the Excalidraw plugin, shown under
 * Settings > Tools > Excalidraw.
 *
 * Implements [Configurable] (not SearchableConfigurable — ADR-E7-03: base
 * interface is sufficient for the first iteration).
 *
 * Architecture decisions:
 *   ADR-E7-03: Configurable only, not SearchableConfigurable.
 *   ADR-E7-04: JList + DefaultListModel with Add/Remove buttons (simpler than
 *     TableModel, sufficient for a pure list management UI).
 *   ADR-E7-05: settings dependency is injected via [settingsProvider] so that
 *     unit tests can bypass ApplicationManager entirely (createForTest pattern).
 *
 * Testability: [createForTest] injects an [ExcalidrawExtensionSettings] instance
 * directly. Tests drive isModified/apply/reset via [addToModel] without
 * instantiating any real Swing UI components.
 *
 * No Reflection is used. No hardcoded secrets. No raw catches that silently
 * swallow exceptions.
 */
class ExcalidrawSettingsConfigurable : Configurable {

    /**
     * Settings provider lambda.
     *
     * In production (default no-arg constructor used by the IntelliJ platform),
     * this delegates to [ExcalidrawExtensionSettings.getInstance].
     *
     * In tests ([createForTest]), a lambda returning a directly-constructed
     * [ExcalidrawExtensionSettings] is injected to avoid ApplicationManager.
     */
    private val settingsProvider: () -> ExcalidrawExtensionSettings?

    /**
     * No-arg constructor used by the IntelliJ platform when loading the
     * `<applicationConfigurable instance="...">` declaration from plugin.xml.
     */
    constructor() : this({ ExcalidrawExtensionSettings.getInstance() })

    /**
     * Internal constructor used by [createForTest] to inject a settings provider.
     */
    private constructor(settingsProvider: () -> ExcalidrawExtensionSettings?) {
        this.settingsProvider = settingsProvider
    }

    /**
     * In-memory model holding the current UI state.
     * Populated by [reset] and mutated by Add/Remove actions and [addToModel].
     * Not bound to an actual [JList] in test mode — the model itself is sufficient
     * for isModified/apply/reset logic (ADR-E7-05).
     */
    private val listModel: DefaultListModel<String> = DefaultListModel()

    /**
     * Swing panel reference — created lazily in [createComponent], nulled in
     * [disposeUIResources]. May be null in test mode where [createComponent] is
     * never called.
     */
    private var panel: JPanel? = null

    // -------------------------------------------------------------------------
    // Configurable contract
    // -------------------------------------------------------------------------

    /**
     * Display name shown in the Settings tree under Tools.
     * AC-E7-01 requires the panel be reachable as "Excalidraw".
     */
    override fun getDisplayName(): String = "Excalidraw"

    /**
     * Builds the Swing panel for the Settings dialog.
     *
     * Layout: BorderLayout with:
     *   - CENTER: JScrollPane wrapping a JList bound to [listModel]
     *   - EAST: vertical Box with Add and Remove JButtons
     *
     * Button actions:
     *   - Add: shows JOptionPane input dialog; normalizes the entered extension
     *     (leading dot, lowercase) and adds it to [listModel] if non-empty and
     *     not already present.
     *   - Remove: removes all selected indices from [listModel] in reverse order
     *     to keep index positions stable during iteration.
     *
     * [listModel] is pre-populated from the settings service before returning.
     */
    override fun createComponent(): JComponent {
        val list = JList(listModel)
        val scrollPane = JScrollPane(list)

        val addButton = JButton("Add")
        addButton.addActionListener {
            val input = Messages.showInputDialog(
                "Enter file extension (e.g. .myext):",
                "Add Extension",
                null
            )
            if (!input.isNullOrBlank()) {
                val normalized = normalizeExtension(input)
                if (normalized.isNotEmpty() && !listModel.contains(normalized)) {
                    listModel.addElement(normalized)
                }
            }
        }

        val removeButton = JButton("Remove")
        removeButton.addActionListener {
            val selected = list.selectedIndices
            // Iterate in reverse so removal does not shift unprocessed indices.
            for (i in selected.reversed()) {
                listModel.remove(i)
            }
        }

        val buttonBox = Box(BoxLayout.Y_AXIS).apply {
            add(addButton)
            add(Box.createVerticalStrut(4))
            add(removeButton)
        }

        val built = JPanel(BorderLayout(8, 0)).apply {
            add(scrollPane, BorderLayout.CENTER)
            add(buttonBox, BorderLayout.EAST)
        }

        panel = built
        reset()
        return built
    }

    /**
     * Returns true when the UI model differs from the current service state.
     *
     * Comparison is order-sensitive (the service preserves insertion order).
     */
    override fun isModified(): Boolean {
        val serviceList = settingsProvider()?.getExtensions() ?: emptyList()
        val uiList = listModel.elements().toList()
        return uiList != serviceList
    }

    /**
     * Writes the current UI model into the settings service, replacing the
     * entire extension list (AC-E7-01: apply writes UI state to service).
     *
     * Uses [ExcalidrawExtensionSettings.loadState] with a fresh [State] so
     * that normalization and deduplication in the service are applied once.
     */
    override fun apply() {
        val svc = settingsProvider() ?: return
        val newList = listModel.elements().toList()
        val newState = ExcalidrawExtensionSettings.State()
        newState.extensions = newList.toMutableList()
        svc.loadState(newState)
    }

    /**
     * Loads the current service extension list into [listModel], discarding
     * any unsaved UI changes (AC-E7-01: reset restores service state).
     */
    override fun reset() {
        listModel.clear()
        settingsProvider()?.getExtensions()?.forEach { listModel.addElement(it) }
    }

    /** Nulls the panel reference to allow Swing resources to be collected. */
    override fun disposeUIResources() {
        panel = null
    }

    // -------------------------------------------------------------------------
    // Test-support API
    // -------------------------------------------------------------------------

    /**
     * Adds [ext] (after normalization) directly to [listModel].
     *
     * Exposed as an `internal` method so that [ExcalidrawSettingsConfigurableTest]
     * (same module) can mutate the UI model without building a real Swing panel.
     * Not part of the public [Configurable] surface.
     */
    internal fun addToModel(ext: String) {
        val normalized = normalizeExtension(ext)
        if (normalized.isNotEmpty() && !listModel.contains(normalized)) {
            listModel.addElement(normalized)
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Normalizes [ext]: trims whitespace, ensures a leading dot, converts to
     * lowercase. Returns an empty string for blank input so callers can skip it.
     */
    private fun normalizeExtension(ext: String): String {
        val trimmed = ext.trim()
        if (trimmed.isEmpty()) return ""
        val dotted = if (trimmed.startsWith(".")) trimmed else ".$trimmed"
        return dotted.lowercase()
    }

    // -------------------------------------------------------------------------
    // Companion — production constructor + createForTest factory
    // -------------------------------------------------------------------------

    companion object {

        /**
         * Test factory — injects [settings] directly, bypassing ApplicationManager.
         *
         * Follows the `createForTest` convention established in
         * [com.swaroop.excalidraw.plugin.editor.ExcalidrawFileEditor.createForTest]
         * and [com.swaroop.excalidraw.plugin.editor.ExcalidrawFileEditorProvider.createForTest].
         *
         * The returned instance's [isModified], [apply], and [reset] operate solely on
         * the in-memory [listModel] and [settings], making them fully testable in headless
         * JUnit5 without any Application or Swing context.
         *
         * @param settings A directly-constructed [ExcalidrawExtensionSettings] instance.
         */
        fun createForTest(settings: ExcalidrawExtensionSettings): ExcalidrawSettingsConfigurable =
            ExcalidrawSettingsConfigurable { settings }
    }
}
