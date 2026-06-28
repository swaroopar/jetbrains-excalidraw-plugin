package com.swaroop.excalidraw.plugin.theme

import com.intellij.ide.ui.LafManager

/**
 * ThemeMapper — pure mapping from IDE LookAndFeel name to Excalidraw theme string.
 *
 * AC-E4-01: Maps the active IDE theme (light/dark) to the Excalidraw "light"/"dark"
 * theme value, enabling the editor to open in the correct theme without user input.
 *
 * Design decisions:
 * - [lafToExcalidrawTheme] takes a nullable String so it can be unit-tested without
 *   any IDE runtime (no ApplicationManager dependency).
 * - [currentExcalidrawTheme] reads from [LafManager] at runtime and delegates to
 *   [lafToExcalidrawTheme] so the mapping logic is always exercised through a single
 *   tested code path.
 * - Heuristic: "dark" and "darcula" (case-insensitive) indicate a dark theme.
 *   "contrast" (case-insensitive) also indicates a dark theme because JetBrains
 *   High Contrast themes are dark-background variants.
 * - Fallback: null or unrecognised → "light" (safe default, no crash).
 *
 * A03: no user-controlled string is executed as code; this object performs only
 * string containment checks (no SQL, no Shell, no eval).
 */
object ThemeMapper {

    /**
     * Maps a LookAndFeel display name to the Excalidraw theme string.
     *
     * Returns "dark" when [lafName] (case-insensitive) contains:
     * - "dark"     — e.g. "Darcula", "Dark+", "One Dark"
     * - "darcula"  — covered by the "dark" check above
     * - "contrast" — e.g. "High Contrast" (JetBrains HC themes are dark-background)
     *
     * Returns "light" for null input or any name not matching the dark heuristics.
     *
     * No IDE runtime is required; safe to call from unit tests.
     */
    fun lafToExcalidrawTheme(lafName: String?): String {
        if (lafName == null) return "light"
        val lower = lafName.lowercase()
        return if (lower.contains("dark") || lower.contains("darcula") || lower.contains("contrast")) {
            "dark"
        } else {
            "light"
        }
    }

    /**
     * Returns the Excalidraw theme string matching the currently active IDE LookAndFeel.
     *
     * Reads [LafManager.getInstance().currentLookAndFeel?.name] and delegates to
     * [lafToExcalidrawTheme]. Falls back to "light" when [LafManager] is not yet
     * initialised (e.g. in headless test mode).
     *
     * Must only be called from a context where the IDE Application is initialised
     * (e.g. from the EDT or a platform-aware test fixture).
     */
    fun currentExcalidrawTheme(): String {
        val lafName = runCatching {
            LafManager.getInstance()?.currentLookAndFeel?.name
        }.getOrNull()
        return lafToExcalidrawTheme(lafName)
    }
}
