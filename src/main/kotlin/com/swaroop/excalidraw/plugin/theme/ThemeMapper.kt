package com.swaroop.excalidraw.plugin.theme

import com.intellij.ide.ui.LafManager

/**
 * ThemeMapper — pure mapping from IDE theme state to Excalidraw theme string.
 *
 * AC-E4-01: Maps the active IDE theme (light/dark) to the Excalidraw "light"/"dark"
 * theme value, enabling the editor to open in the correct theme without user input.
 *
 * Design decisions:
 * - [lafToExcalidrawTheme] takes a nullable String so it can be unit-tested without
 *   any IDE runtime (no ApplicationManager dependency). It remains as a fallback
 *   heuristic for platform builds/tests where the richer theme API is unavailable.
 * - [currentExcalidrawTheme] prefers [LafManager.getInstance().currentUIThemeLookAndFeel]
 *   `.isDark` — the platform's own authoritative dark/light flag (used internally by
 *   IntelliJ for icon/UI dark-mode decisions) — because under the modern "New UI"
 *   theme system, [LafManager.getCurrentLookAndFeel]'s `name` (legacy `UIManager
 *   .LookAndFeelInfo`) does NOT reliably reflect the active color theme: many themes
 *   (custom marketplace themes, "Islands" variants, etc.) install under a generic LaF
 *   name that never contains "dark"/"darcula"/"contrast", which was observed to leave
 *   the canvas permanently stuck in light mode regardless of the IDE's actual theme.
 *   `isDark` has no such naming ambiguity — it is a direct boolean on the currently
 *   installed theme descriptor.
 * - [lafToExcalidrawTheme]'s name-substring heuristic ("dark", "darcula", "contrast")
 *   is retained only as a fallback for the (rare/older-platform) case where
 *   `currentUIThemeLookAndFeel` is unavailable or null.
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
     * - "dark"     — e.g. "Dark+", "One Dark"
     * - "darcula"  — checked independently of "dark", since "darcula" does not
     *   contain the substring "dark" (no "k" after "dar")
     * - "contrast" — e.g. "High Contrast" (JetBrains HC themes are dark-background)
     *
     * Returns "light" for null input or any name not matching the dark heuristics.
     *
     * No IDE runtime is required; safe to call from unit tests. This is only a
     * fallback — see [currentExcalidrawTheme] for the primary, name-independent path.
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
     * Returns the Excalidraw theme string matching the currently active IDE theme.
     *
     * Primary path: [LafManager.getInstance().currentUIThemeLookAndFeel.isDark] — the
     * platform's own authoritative, name-independent dark/light flag. This is what
     * IntelliJ itself uses internally to decide dark-mode rendering, so it is correct
     * regardless of the active theme's display name (unlike the legacy
     * `currentLookAndFeel.name` substring heuristic below).
     *
     * Fallback path (only used if `currentUIThemeLookAndFeel` is null, e.g. an older
     * platform build or before the IDE's theme subsystem is fully initialised): reads
     * [LafManager.getInstance().currentLookAndFeel?.name] and delegates to
     * [lafToExcalidrawTheme].
     *
     * Falls back to "light" when [LafManager] itself is not yet initialised (e.g. in
     * headless test mode).
     *
     * Must only be called from a context where the IDE Application is initialised
     * (e.g. from the EDT or a platform-aware test fixture).
     */
    fun currentExcalidrawTheme(): String {
        val isDarkResult = runCatching {
            LafManager.getInstance()?.currentUIThemeLookAndFeel?.isDark
        }
        val isDark = isDarkResult.getOrNull()
        if (isDark != null) {
            return if (isDark) "dark" else "light"
        }

        val lafName = runCatching {
            LafManager.getInstance()?.currentLookAndFeel?.name
        }.getOrNull()
        return lafToExcalidrawTheme(lafName)
    }
}
