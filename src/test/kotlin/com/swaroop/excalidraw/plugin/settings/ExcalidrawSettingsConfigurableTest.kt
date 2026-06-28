package com.swaroop.excalidraw.plugin.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExcalidrawSettingsConfigurable].
 *
 * All tests use [ExcalidrawSettingsConfigurable.createForTest] to inject a direct
 * [ExcalidrawExtensionSettings] instance, bypassing ApplicationManager.
 * No MockK, no Mockito, no SwingUI instantiation required.
 *
 * AC-E7-01: The configurable correctly tracks isModified, apply, and reset
 * against the injected settings service.
 */
class ExcalidrawSettingsConfigurableTest {

    /**
     * Helper: creates a fresh configurable with a fresh in-process settings
     * instance and invokes reset() to prime the UI model from the service.
     */
    private fun makeConfigurable(): ExcalidrawSettingsConfigurable {
        val settings = ExcalidrawExtensionSettings()
        val configurable = ExcalidrawSettingsConfigurable.createForTest(settings)
        configurable.reset()
        return configurable
    }

    /**
     * (1) testIsModifiedFalseWhenUnchanged — after reset() the UI model matches
     * the service so isModified() must return false (AC-E7-01).
     */
    @Test
    fun testIsModifiedFalseWhenUnchanged() {
        val configurable = makeConfigurable()
        assertFalse(configurable.isModified(),
            "isModified() must be false when the UI model equals the service state")
    }

    /**
     * (2) testIsModifiedTrueAfterAdd — adding an entry to the UI model makes
     * isModified() return true.
     */
    @Test
    fun testIsModifiedTrueAfterAdd() {
        val configurable = makeConfigurable()
        configurable.addToModel(".myext")
        assertTrue(configurable.isModified(),
            "isModified() must be true after adding an entry to the UI model")
    }

    /**
     * (3) testApplyWritesListToService — after changing the UI model and calling
     * apply(), the injected service reflects the new list (AC-E7-01).
     */
    @Test
    fun testApplyWritesListToService() {
        val settings = ExcalidrawExtensionSettings()
        val configurable = ExcalidrawSettingsConfigurable.createForTest(settings)
        configurable.reset()

        configurable.addToModel(".applied")
        configurable.apply()

        assertTrue(settings.getExtensions().contains(".applied"),
            "Service must contain the extension added via the UI model after apply()")
    }

    /**
     * (4) testResetRestoresServiceState — after apply() isModified() returns
     * false; changing the model again and then calling reset() must restore the
     * model to the service state so isModified() becomes false again.
     */
    @Test
    fun testResetRestoresServiceState() {
        val settings = ExcalidrawExtensionSettings()
        val configurable = ExcalidrawSettingsConfigurable.createForTest(settings)
        configurable.reset()

        // Mutate model and apply so the service now holds a custom extension.
        configurable.addToModel(".saved")
        configurable.apply()
        assertFalse(configurable.isModified(), "isModified() must be false right after apply()")

        // Dirty the model again without applying.
        configurable.addToModel(".unsaved")
        assertTrue(configurable.isModified(), "isModified() must be true after adding unsaved entry")

        // reset() must discard the unsaved change and restore service state.
        configurable.reset()
        assertFalse(configurable.isModified(),
            "isModified() must be false after reset() discards unsaved UI changes")
    }
}
