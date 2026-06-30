package com.swaroop.excalidraw.plugin.editor

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge
import com.swaroop.excalidraw.plugin.bridge.SceneChangeMessage
import com.intellij.util.io.HttpRequests
import com.swaroop.excalidraw.plugin.jcef.ExcalidrawJcefHost
import com.swaroop.excalidraw.plugin.jcef.LibraryBrowserDialog
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawLibraryService
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawParseException
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawPersistenceService
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawSerializer
import com.swaroop.excalidraw.plugin.theme.ExcalidrawThemeController
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * ExcalidrawFileEditor: FileEditor implementation for .excalidraw/.excalidraw.png files.
 *
 * Architecture Decisions enforced here:
 *
 *   AD-01 (Persistence-Service): All VirtualFile access goes through
 *   [ExcalidrawPersistenceService.readScene] — never direct byte reads in this class.
 *
 *   AD-02 (No eval): Bridge communication is performed exclusively via
 *   [ExcalidrawJsBridge.loadScene] — no direct JS eval calls.
 *
 *   AD-03 (No source mutation on error): When [ExcalidrawParseException] is caught,
 *   an IDE notification banner is shown via the [notifier] hook. The [VirtualFile] is
 *   never written or deleted. No exception stack-trace is exposed to the UI.
 *
 *   AD-04 (loadEnd-first scene push): The scene is only pushed to the web app after
 *   JCEF fires its [ExcalidrawJcefHost.addLoadEndListener] callback. No premature
 *   scene injection before the DOM is ready.
 *
 *   AD-05 (EDT routing): JCEF callbacks arrive on an internal JCEF thread.
 *   [ApplicationManager.getApplication().invokeLater] re-routes them to the
 *   Event Dispatch Thread before any VFS or UI operation.
 *
 *   AD-3 (Dispose chain): [jcefHost], [bridge], and [ExcalidrawThemeController] are
 *   registered as child-Disposables of this editor via [Disposer.register]. When the
 *   IDE closes the editor, the disposal chain calls their dispose methods automatically,
 *   preventing JCEF resource leaks and LafManagerListener leaks (AC-E4-02, task-05-007).
 *
 * @param project The current IDE [Project] — used for the notification group context.
 *   Null in test mode.
 * @param file The [VirtualFile] being edited. Stored for [getFile] and [readScene].
 * @param jcefHost JCEF browser wrapper. Injected to allow test-mode operation without
 *   a real JBCefBrowser runtime.
 * @param bridge Typed JS-Kotlin bridge. Injected for the same testability reason.
 * @param persistenceService Service for reading and parsing the VirtualFile content.
 * @param notifier Hook called with a human-readable error message when
 *   [ExcalidrawParseException] is caught. In production this fires a real IDE notification
 *   balloon; in test mode it is a lambda the test can inspect (AC-E1-02).
 */
class ExcalidrawFileEditor private constructor(
    private val project: Project?,
    private val file: VirtualFile,
    private val jcefHost: ExcalidrawJcefHost,
    /**
     * The JS-Kotlin bridge for this editor.
     *
     * Exposed as an [internal] property so that [ExportSvgAction] /
     * [ExportPngAction] (same module) can obtain a reference to the bridge
     * without Reflection — required for [ExcalidrawExporter.exportDrawing].
     *
     * Not part of the public [FileEditor] API; do not reference from plugin
     * consumers outside the `com.swaroop.excalidraw.plugin` module.
     */
    internal val bridge: ExcalidrawJsBridge,
    private val persistenceService: ExcalidrawPersistenceService,
    private val notifier: (String) -> Unit,
    /**
     * Optional test-mode override for the debounce scheduling mechanism.
     *
     * When non-null (injected via [createForTest]), the editor uses this executor
     * instead of the real [Alarm] — the autosave runnable is stored in [pendingDebounce]
     * and can be fired synchronously by calling [flushDebounce] in tests.
     *
     * When null (production path), the real [Alarm] schedules the autosave runnable
     * after [AUTOSAVE_DEBOUNCE_MS] milliseconds on the EDT.
     */
    private val debounceExecutor: (() -> Unit)?,
    /**
     * Optional [ExcalidrawThemeController] bound to this editor's lifecycle.
     *
     * When non-null, [wireLoadEndCallback] calls [ExcalidrawThemeController.pushCurrentTheme]
     * after [ExcalidrawJsBridge.loadScene] — guaranteeing that `window.__excalidrawSetTheme__`
     * is defined before the first theme push (AC-E4-01, task-05-007 timing contract).
     *
     * Registered as a child-Disposable of this editor in the factory methods so the
     * IDE's Disposer chain calls [ExcalidrawThemeController.dispose] when the editor
     * closes (listener-leak-free, AC-E4-02).
     *
     * Null in existing tests that do not exercise theme integration, preserving their
     * [capturedJs] assertions without unexpected `__excalidrawSetTheme__` injections.
     */
    private val themeController: ExcalidrawThemeController?
) : UserDataHolderBase(), FileEditor {

    companion object {
        private val LOG: Logger = Logger.getInstance(ExcalidrawFileEditor::class.java)

        /** Shared Gson instance for serialising [SceneChangeMessage] to JSON string. */
        private val GSON: Gson = Gson()

        /**
         * Human-readable editor name shown in the IDE tab and "Open with" menu.
         * Stable constant — tests can assert against it without instantiating the class.
         */
        const val EDITOR_NAME: String = "Excalidraw"

        /**
         * Phase 01 stub value for [isModified].
         * Retained for backwards compatibility — real tracking uses [_modified].
         * Full edit-tracking wired through the JS bridge comes in a future phase.
         */
        const val IS_MODIFIED_STUB: Boolean = false

        /**
         * Phase 01 stub value for [isValid].
         * Full VFS-lifecycle validation (listen for file deletion/move) comes in a future phase.
         */
        const val IS_VALID_STUB: Boolean = true

        /**
         * Notification group ID used as the group string for [Notifications.Bus.notify].
         * Matches the plugin name for IDE notification categorisation.
         */
        private const val NOTIFICATION_GROUP_ID = "Excalidraw"

        /**
         * Debounce delay in milliseconds for the auto-save alarm (task-04-004, AC-E3-01).
         * After the last [onSceneChanged] call, the editor waits this long before writing
         * the scene to the VFS via [ExcalidrawPersistenceService.writeScene].
         */
        const val AUTOSAVE_DEBOUNCE_MS: Long = 500L

        /**
         * Canonical empty Excalidraw scene used as [currentSceneJson] when a `.excalidraw.png`
         * is opened with no embedded scene (it becomes a fresh, savable blank drawing). Mirrors
         * the empty canvas the JS side renders in that case, so the seeded baseline matches.
         */
        private const val EMPTY_SCENE_JSON: String =
            """{"type":"excalidraw","version":2,"elements":[],"appState":{},"files":{}}"""

        /**
         * Returns true when [name] identifies a scene-embedded PNG file.
         *
         * The `.excalidraw.png` extension is a strict suffix check — `String.endsWith`
         * does not match a plain `.excalidraw` file (no prefix ambiguity).
         *
         * Private to the Companion to keep the PNG-detection logic encapsulated and
         * independently testable without constructing a full editor instance.
         *
         * @param name The file name (not a full path) to test.
         */
        private fun isExcalidrawPng(name: String): Boolean = name.endsWith(".excalidraw.png")

        /**
         * Normalises the contents of a `.excalidrawlib` file into a JSON array of
         * Excalidraw library items (the shape excalidrawAPI.updateLibrary expects),
         * or null if it can't be parsed.
         *
         * Handles both formats:
         *  - v2: `{ "type":"excalidrawlib", "libraryItems":[ {id,status,elements,created}, … ] }`
         *  - v1: `{ "type":"excalidrawlib", "library":[ [elements], … ] }` (each entry wrapped).
         *
         * Pure + unit-testable; no IDE/JCEF dependency.
         */
        internal fun parseLibraryItems(fileText: String): String? {
            val root = try {
                JsonParser.parseString(fileText)?.takeIf { it.isJsonObject }?.asJsonObject
            } catch (_: Exception) {
                null
            } ?: return null

            if (root.has("libraryItems") && root.get("libraryItems").isJsonArray) {
                val arr = root.getAsJsonArray("libraryItems")
                if (arr.size() > 0) return arr.toString()
            }
            if (root.has("library") && root.get("library").isJsonArray) {
                val lib = root.getAsJsonArray("library")
                val items = JsonArray()
                for ((i, entry) in lib.withIndex()) {
                    if (!entry.isJsonArray) continue
                    val item = JsonObject()
                    item.addProperty("id", "imported-$i")
                    item.addProperty("status", "unpublished")
                    item.addProperty("created", 1L)
                    item.add("elements", entry)
                    items.add(item)
                }
                if (items.size() > 0) return items.toString()
            }
            return null
        }

        /**
         * Production constructor — creates real [ExcalidrawJcefHost] and
         * [ExcalidrawJsBridge] instances backed by the live JCEF runtime.
         *
         * [ExcalidrawJcefHost.browserForBridge] provides the [JBCefBrowser] needed to
         * create the bridge without reflection (scope-extension: ExcalidrawJcefHost.kt
         * gains the internal [browserForBridge] accessor in task-02-007).
         *
         * @param project The IDE project context.
         * @param file The VirtualFile to display.
         */
        operator fun invoke(project: Project, file: VirtualFile): ExcalidrawFileEditor {
            val host = ExcalidrawJcefHost()
            val browser = host.browserForBridge()

            check(browser != null) {
                "ExcalidrawJcefHost produced a null browser in production mode — " +
                    "check JCEF availability (JBCefApp.isSupported) before opening the editor"
            }

            // Use a mutable holder so the sceneChangeHandler lambda can reference
            // the editor before the editor instance exists (AD-05: forward reference).
            var editorHolder: ExcalidrawFileEditor? = null
            val bridge = ExcalidrawJsBridge.create(
                browser = browser,
                sceneChangeHandler = { scene ->
                    // editor is set immediately after construction in the .also block.
                    editorHolder?.onSceneChanged(scene)
                        ?: LOG.warn("ExcalidrawFileEditor: sceneChangeHandler fired before editor was initialised")
                }
            )

            val notifier: (String) -> Unit = { message ->
                val notification = Notification(
                    NOTIFICATION_GROUP_ID,
                    "Excalidraw: Cannot open file",
                    message,
                    NotificationType.ERROR
                )
                Notifications.Bus.notify(notification, project)
            }

            // Create the ThemeController before the editor so it can be passed
            // as a constructor field (needed by wireLoadEndCallback for the
            // loadEnd-timed pushCurrentTheme call — task-05-007 timing contract).
            val themeController = ExcalidrawThemeController(bridge)

            return ExcalidrawFileEditor(
                project = project,
                file = file,
                jcefHost = host,
                bridge = bridge,
                persistenceService = ExcalidrawPersistenceService(),
                notifier = notifier,
                debounceExecutor = null,
                themeController = themeController
            ).also { editor ->
                editorHolder = editor
                Disposer.register(editor, host)
                Disposer.register(editor, bridge)
                // Register the ThemeController as a child-Disposable of this editor
                // so the IDE's Disposer chain calls dispose() when the editor closes
                // (listener-leak-free, AC-E4-02, task-05-007).
                Disposer.register(editor, themeController)
                editor.wireLoadEndCallback()
                // "Browse libraries" → open the in-IDE library browser and round-trip the
                // chosen library back into this editor.
                host.onBrowseLibraries = { url -> editor.openLibraryBrowser(url) }
                // Persist the library on every change (IndexedDB is unavailable on the
                // opaque origin), so added libraries survive IDE restarts.
                bridge.registerLibraryChangeCallback { itemsJson ->
                    ExcalidrawLibraryService.getInstance().libraryItemsJson = itemsJson
                }
            }
        }

        /**
         * Test factory — creates an editor with pre-built [jcefHost] and [bridge] stubs
         * so that unit tests can run without a live JCEF runtime.
         *
         * The [notifier] parameter lets tests assert that an error notification was
         * triggered (AC-E1-02) without requiring a live IDE notification subsystem.
         *
         * The [debounceExecutor] parameter, when non-null, replaces the real [Alarm]-based
         * debounce with a test-mode path: the autosave runnable is stored in the editor's
         * [pendingDebounce] field and tests can fire it synchronously via [flushDebounce].
         * When null (default), the production Alarm path is used.
         *
         * The [themeController] parameter, when non-null, is passed as a constructor field
         * so that [wireLoadEndCallback] can call [ExcalidrawThemeController.pushCurrentTheme]
         * at the correct moment (after loadEnd, when `window.__excalidrawSetTheme__` is
         * defined). The controller is also registered as a child-Disposable of the editor
         * via [Disposer.register] so the lifecycle chain disposes it on editor close
         * (task-05-007, AC-E4-01 timing + AC-E4-02 leak-free).
         *
         * When null (default), no ThemeController is wired — existing tests that assert on
         * [capturedJs] contents are unaffected by unexpected `__excalidrawSetTheme__`
         * injections.
         *
         * A09: not exposed via the IDE plugin.xml extension point — internal test hook only.
         */
        fun createForTest(
            file: VirtualFile,
            jcefHost: ExcalidrawJcefHost,
            bridge: ExcalidrawJsBridge,
            persistenceService: ExcalidrawPersistenceService = ExcalidrawPersistenceService(),
            notifier: (String) -> Unit = { message ->
                LOG.warn("ExcalidrawFileEditor [test-mode] parse error: $message")
            },
            debounceExecutor: (() -> Unit)? = null,
            themeController: ExcalidrawThemeController? = null
        ): ExcalidrawFileEditor {
            return ExcalidrawFileEditor(
                project = null,
                file = file,
                jcefHost = jcefHost,
                bridge = bridge,
                persistenceService = persistenceService,
                notifier = notifier,
                debounceExecutor = debounceExecutor,
                themeController = themeController
            ).also { editor ->
                Disposer.register(editor, jcefHost)
                Disposer.register(editor, bridge)
                // Register the ThemeController if injected, so the Disposer chain
                // disposes it when the editor closes (lifecycle binding, task-05-007).
                if (themeController != null) {
                    Disposer.register(editor, themeController)
                }
                editor.wireLoadEndCallback()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Auto-save debounce (task-04-004, AC-E3-01/E3-03)
    // -------------------------------------------------------------------------

    /**
     * Canonical serializer for `.excalidraw` JSON output.
     * Normalizes the raw [currentSceneJson] before writing to VFS.
     */
    private val serializer: ExcalidrawSerializer = ExcalidrawSerializer()

    /**
     * Alarm bound to this editor's [Disposable] lifetime.
     *
     * Used in production (when [debounceExecutor] is null) to schedule the
     * autosave work after [AUTOSAVE_DEBOUNCE_MS] milliseconds. Cancelled
     * automatically when this editor is disposed (because it is Disposable-bound).
     *
     * Null when running in test mode ([debounceExecutor] non-null).
     */
    private val alarm: Alarm? = if (debounceExecutor == null) Alarm(Alarm.ThreadToUse.SWING_THREAD, this) else null

    /**
     * Tracks whether the editor has been disposed.
     *
     * Written by [dispose] and checked by [flushDebounce] / the alarm callback
     * to prevent writes after disposal (no-write-after-dispose guarantee).
     */
    @Volatile
    private var isDisposed: Boolean = false

    /**
     * In test mode, holds the most-recently scheduled autosave [Runnable].
     *
     * Set by [onSceneChanged] when [debounceExecutor] is non-null (test path).
     * Each new [onSceneChanged] call replaces the previous runnable, implementing
     * the debounce last-write-wins semantics without a real Alarm.
     * [flushDebounce] executes this runnable synchronously (if the editor is not disposed).
     */
    @Volatile
    internal var pendingDebounce: Runnable? = null

    /**
     * Test helper: fires the most-recently scheduled debounce runnable synchronously.
     *
     * No-op if the editor is disposed or no runnable is pending.
     * Only meaningful when [debounceExecutor] was injected (test mode).
     */
    fun flushDebounce() {
        if (!isDisposed) {
            pendingDebounce?.run()
        }
    }

    // -------------------------------------------------------------------------
    // Scene-change tracking (task-03-004, AD-05)
    // -------------------------------------------------------------------------

    /**
     * Tracks whether the scene has been modified since the last save.
     *
     * Written exclusively on the EDT (guaranteed by the bridge's invokeLater
     * dispatch, AD-05). Volatile ensures visibility from any thread that calls
     * [isModified].
     */
    @Volatile
    private var _modified: Boolean = false

    /**
     * Canonical element content of the loaded (or last-saved) scene, used to tell a
     * real user edit apart from a no-op [onSceneChanged].
     *
     * Excalidraw fires `onChange` not only on edits but also on the initial scene
     * load and on theme/font re-renders, selection, scroll, etc. Persisting those
     * would rewrite the file on mere open — and because `.excalidraw.png` files are
     * re-encoded on export, that shows up as a spurious git change for every file
     * opened. We therefore only mark the editor modified / schedule an autosave when
     * the element content differs from this baseline (volatile per-element fields are
     * stripped — see [canonicalElements]).
     *
     * Null until the first [onSceneChanged] establishes the baseline from the loaded
     * scene. Written exclusively on the EDT (AD-05).
     */
    @Volatile
    private var baselineElements: String? = null

    /**
     * Whether autosave is permitted to overwrite this `.excalidraw.png` file.
     *
     * `.excalidraw.png` files are persisted by re-rasterising the editor canvas and
     * replacing the file's bytes. Before this flag is set the canvas content has not
     * been reconciled with the file (the extraction round-trip is still in flight, and
     * an early `onChange` may carry stale/empty mount state), so a write then could
     * silently destroy the user's image. While it is false, [onSceneChanged] drops
     * events and [scheduleAutosave] refuses to write.
     *
     * It is set to true once the open path has settled the canvas against the file, in
     * BOTH branches of the PNG callback in [wireLoadEndCallback]:
     *  - extraction success: baseline seeded from the extracted scene; edits round-trip;
     *  - extraction failure (no embedded scene): the canvas is a blank drawing and the
     *    baseline is empty, so opening writes nothing but the user's first edit saves
     *    (creating a proper `.excalidraw.png` with an embedded scene).
     *
     * Irrelevant for plain `.excalidraw` (JSON) files, which round-trip losslessly and
     * are never gated by this flag. Written exclusively on the EDT (AD-05); volatile for
     * cross-thread visibility.
     */
    @Volatile
    private var pngSceneExtracted: Boolean = false

    /**
     * The most-recently received scene state, serialised as a JSON string via
     * [Gson.toJson]. Null until the first [onSceneChanged] call.
     *
     * Handover field for phase-04 auto-save: the auto-save writer reads this
     * value to determine what to persist.
     *
     * Written exclusively on the EDT (AD-05). Declared as `@Volatile` to ensure
     * cross-thread visibility without requiring synchronised reads.
     */
    @Volatile
    var currentSceneJson: String? = null
        private set

    /** Propagates PROP_MODIFIED change events to registered [PropertyChangeListener]s. */
    private val propertyChangeSupport: PropertyChangeSupport = PropertyChangeSupport(this)

    // -------------------------------------------------------------------------
    // Private panel used in test mode (no real JCEF component available)
    // -------------------------------------------------------------------------

    /**
     * Fallback component returned by [getComponent] and [getPreferredFocusedComponent]
     * when the host has no real browser (test mode).
     */
    private val fallbackPanel: JPanel by lazy { JPanel() }

    // -------------------------------------------------------------------------
    // LoadEnd wiring (AD-04, AD-05)
    // -------------------------------------------------------------------------

    /**
     * Registers the scene-push callback on [jcefHost].
     *
     * The callback is invoked on the EDT (AD-05) exactly once after JCEF fires
     * onLoadEnd (AD-04). Inside the callback two paths are taken:
     *
     * **PNG path** (`.excalidraw.png` files, task-07-007, AC-E6-01/E6-03):
     * 1. File bytes are read via [ReadAction] (if an Application is available) or
     *    directly (test mode) — no [ExcalidrawPersistenceService.readScene] call.
     * 2. Bytes are Base64-encoded to produce a `data:image/png;base64,...` Data URL.
     * 3. [ExcalidrawJsBridge.installReturnChannel] installs the JS→Kotlin channel.
     * 4. [ExcalidrawJsBridge.registerPngExtractedCallback] stores a one-shot callback:
     *    - On success ([BridgeMessage.PngExtracted.error] is null): [currentSceneJson] is set
     *      to the extracted scene (the canvas already shows the scene via `__excalidrawLoadPng__`).
     *    - On failure ([BridgeMessage.PngExtracted.error] is non-null): [notifier] is called
     *      with the error message; [currentSceneJson] is not changed (AC-E6-03).
     * 5. [ExcalidrawJsBridge.requestPngExtract] injects the JS call.
     * 6. [ExcalidrawThemeController.pushCurrentTheme] is called if present.
     *
     * **JSON path** (plain `.excalidraw` files — unchanged from prior tasks):
     * 1. [ExcalidrawPersistenceService.readScene] reads and parses the file.
     * 2. On success, [ExcalidrawJsBridge.installReturnChannel] installs the JS→Kotlin channel.
     * 3. [ExcalidrawJsBridge.loadScene] pushes the scene to the web app.
     * 4. [ExcalidrawThemeController.pushCurrentTheme] is called (AC-E4-01, task-05-007).
     * 5. On [ExcalidrawParseException], [notifier] is called (AD-03); no VirtualFile write.
     *
     * In test mode (no [ApplicationManager] available), the callback executes
     * synchronously on the caller's thread to keep test assertions deterministic.
     * In production, it is re-routed to the EDT via [ApplicationManager.invokeLater].
     */
    private fun wireLoadEndCallback() {
        jcefHost.addLoadEndListener {
            // AD-05: JCEF fires onLoadEnd on a JCEF-internal thread.
            // Route to EDT via invokeLater before touching VFS or UI.
            val application = ApplicationManager.getApplication()

            val work: () -> Unit = {
                if (isExcalidrawPng(file.name)) {
                    // PNG async path (task-07-007, AC-E6-01/AC-E6-03):
                    // Read bytes using ReadAction when an Application context is available
                    // (EDT-safe, no blocking reads inside a write lock). Fall back to
                    // direct call in test mode where no Application exists.
                    val bytes: ByteArray = if (application != null) {
                        ReadAction.compute<ByteArray, Throwable> { file.contentsToByteArray() }
                    } else {
                        file.contentsToByteArray()
                    }
                    // A03: Base64 encoding of raw bytes — no string concatenation of
                    // untrusted data; java.util.Base64 is the standard JVM encoder.
                    val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
                    val dataUrl = "data:image/png;base64,$base64"

                    // Install the JS→Kotlin return channel BEFORE registering the callback
                    // and requesting PNG extraction, so __excalidrawPostToKotlin__ is
                    // available when the JS side responds.
                    bridge.installReturnChannel()

                    bridge.registerPngExtractedCallback { msg ->
                        // Callback arrives on the JCEF/bridge thread — route to EDT.
                        val deliver: () -> Unit = {
                            if (msg.error != null) {
                                // The PNG carries no embedded Excalidraw scene (a plain raster),
                                // or it could not be decoded as one. Open it as a blank, editable
                                // canvas: arm autosave with an EMPTY baseline so the user can draw
                                // and the first edit is persisted — that save writes a proper
                                // .excalidraw.png with an embedded scene, after which every later
                                // edit round-trips. The empty baseline (matched by the JS side,
                                // which clears the canvas to empty here) guarantees that merely
                                // opening the file never rewrites it — only a real edit does.
                                LOG.info(
                                    "ExcalidrawFileEditor: '${file.path}' has no embedded scene " +
                                        "(${msg.error}) — opening as a new blank drawing"
                                )
                                currentSceneJson = EMPTY_SCENE_JSON
                                baselineElements = canonicalElements(JsonArray())
                                pngSceneExtracted = true
                            } else {
                                // AC-E6-01: scene extracted successfully. The canvas already
                                // shows the scene (driven by __excalidrawLoadPng__ on the JS
                                // side). Record the scene JSON for future auto-save use.
                                currentSceneJson = msg.sceneJson
                                // Seed the change-detection baseline from the extracted scene
                                // so the post-extraction onChange echo is not mistaken for a
                                // user edit (which would otherwise schedule a spurious rewrite).
                                baselineElements = canonicalElements(elementsOf(msg.sceneJson))
                                // Arm autosave: the canvas content now provably originates from
                                // a successful load of THIS file, so persisting it is safe.
                                pngSceneExtracted = true
                            }
                        }
                        // Re-route to EDT if a real Application is present; otherwise
                        // execute synchronously (test-mode determinism).
                        if (application != null) {
                            application.invokeLater(deliver)
                        } else {
                            deliver()
                        }
                    }

                    // Inject the JS call that triggers extraction on the web app side.
                    // A03: dataUrl is passed through Gson.toJson in requestPngExtract —
                    // no raw string concatenation of file data into JS code.
                    bridge.requestPngExtract(dataUrl)

                    // AC-E4-01 timing: push initial theme after the JS call so that
                    // __excalidrawSetTheme__ is guaranteed to be defined at this point.
                    themeController?.pushCurrentTheme()
                    restorePersistedLibrary()
                } else {
                    // JSON path (plain .excalidraw files). readSceneOrNew opens an empty
                    // or newly-created file as a blank canvas instead of erroring.
                    try {
                        val scene = persistenceService.readSceneOrNew(file)
                        // Install the JS→Kotlin return channel BEFORE loading the scene
                        // so that window.__excalidrawPostToKotlin__ is available as soon
                        // as Excalidraw's onChange fires (AD-04, task-03-005).
                        bridge.installReturnChannel()
                        bridge.loadScene(scene)
                        // AC-E4-01 timing: push initial theme AFTER loadScene so that
                        // window.__excalidrawSetTheme__ (registered in index.jsx after
                        // render) is guaranteed to exist before the call (task-05-007).
                        themeController?.pushCurrentTheme()
                        restorePersistedLibrary()
                    } catch (ex: ExcalidrawParseException) {
                        // AD-03: invoke the notifier hook, do NOT write VirtualFile.
                        // A09: only the human-readable message is surfaced — no stack trace in UI.
                        val message = ex.message ?: "Cannot parse Excalidraw file '${ex.filePath}'"
                        LOG.warn("ExcalidrawFileEditor: parse error for '${ex.filePath}'", ex)
                        notifier(message)
                    }
                    // Any other (unexpected) exception propagates to the EDT exception handler
                    // and surfaces as an IDE error dialog — intentional (A09: don't swallow unknowns).
                }
            }

            if (application != null) {
                application.invokeLater(work)
            } else {
                // Test-mode fallback: invoke synchronously so assertions can observe results.
                work()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Library browsing (round-trip: site -> .excalidrawlib -> editor library)
    // -------------------------------------------------------------------------

    /**
     * Opens the in-IDE [LibraryBrowserDialog] for [libraryUrl]. When the user adds a
     * library, the dialog hands back the `.excalidrawlib` URL; we fetch + normalise it
     * off the EDT and inject the items via [ExcalidrawJsBridge.addLibrary], which merges
     * them with excalidrawAPI.updateLibrary. Wired from the production factory only.
     */
    /**
     * Restores the persisted library into the freshly-loaded editor (called once per
     * load, after the scene + theme are pushed). No-op when nothing has been saved.
     */
    private fun restorePersistedLibrary() {
        // No Application (unit tests) → nothing to restore.
        if (ApplicationManager.getApplication() == null) return
        val saved = try {
            ExcalidrawLibraryService.getInstance().libraryItemsJson
        } catch (_: Throwable) {
            // Service container not available (e.g. lightweight test fixtures) — skip.
            null
        } ?: return
        if (saved == "[]") return
        bridge.loadLibrary(saved)
    }

    private fun openLibraryBrowser(libraryUrl: String) {
        val proj = project ?: return
        LibraryBrowserDialog(proj, libraryUrl) { libUrl ->
            ApplicationManager.getApplication().executeOnPooledThread {
                val itemsJson: String? = try {
                    val fileText = HttpRequests.request(libUrl)
                        .accept("application/json, application/octet-stream, */*")
                        .readString()
                    parseLibraryItems(fileText)
                } catch (e: Exception) {
                    LOG.warn("ExcalidrawFileEditor: failed to load library from '$libUrl'", e)
                    null
                }
                if (itemsJson != null) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!isDisposed) bridge.addLibrary(itemsJson)
                    }
                }
            }
        }.show()
    }

    // -------------------------------------------------------------------------
    // Scene-change handler (task-03-004, AC-E2-01/E2-02)
    // -------------------------------------------------------------------------

    /**
     * Called when the Excalidraw web app posts a scene-change event over the bridge.
     *
     * Runs on the EDT (the bridge dispatches via `invokeLater`, AD-05).
     * As a defensive measure, this method re-routes to the EDT if called from
     * an unexpected thread — ensuring [_modified] and [currentSceneJson] are
     * always written on the correct thread.
     *
     * Actions performed:
     * 1. Serialises [scene] to a JSON string via [Gson.toJson] and stores it in
     *    [currentSceneJson] (handover field for phase-04 auto-save).
     * 2. Sets [_modified] to `true`.
     * 3. Fires a PROP_MODIFIED property-change event so that registered
     *    [PropertyChangeListener]s (e.g. the IDE's document-modified tracking)
     *    are notified.
     *
     * A03: [scene] originates from the Excalidraw web app and is already
     * deserialised via Gson at the bridge layer — no raw string concatenation
     * or code execution occurs here.
     */
    fun onSceneChanged(scene: SceneChangeMessage) {
        val work: () -> Unit = {
            if (isExcalidrawPng(file.name) && !pngSceneExtracted) {
                // The scene was never successfully extracted from this .excalidraw.png
                // (e.g. the file carries no embedded Excalidraw scene). The canvas holds
                // stale/empty state unrelated to the file, so the event is dropped
                // entirely: autosave must never overwrite the user's image with content
                // that did not come from the file. currentSceneJson is deliberately left
                // untouched so no later export can pick up this state.
                LOG.debug("onSceneChanged ignored: no scene extracted yet for PNG '${file.path}'")
            } else {
                currentSceneJson = GSON.toJson(scene)
                val newCanonical = canonicalElements(scene.elements)
                val baseline = baselineElements
                when {
                    baseline == null -> {
                        // First change after load: this reflects the unedited scene
                        // (Excalidraw fires onChange once the loaded scene renders).
                        // Record it as the baseline; do NOT mark modified or write —
                        // opening a file must not change it on disk.
                        baselineElements = newCanonical
                    }
                    newCanonical == baseline -> {
                        // No element-content change (theme/font re-render, selection,
                        // scroll, …). Keep the latest scene JSON but do not persist.
                    }
                    else -> {
                        // Genuine user edit: advance the baseline and schedule a save.
                        baselineElements = newCanonical
                        val wasModified = _modified
                        _modified = true
                        if (!wasModified) {
                            // FileEditor.getPropModified() returns "modified" — use the method
                            // instead of a non-existent PROP_MODIFIED field (IntelliJ API).
                            propertyChangeSupport.firePropertyChange(FileEditor.getPropModified(), false, true)
                        }
                        // Schedule (or reschedule) the debounced auto-save (task-04-004, AC-E3-01).
                        scheduleAutosave()
                    }
                }
            }
        }

        val application = ApplicationManager.getApplication()
        if (application != null && !application.isDispatchThread) {
            application.invokeLater(work)
        } else {
            work()
        }
    }

    /**
     * Returns a stable string representation of [elements] for change detection,
     * with per-element fields that churn without a meaningful content change
     * (`version`, `versionNonce`, `updated`) removed. Operates on a deep copy so the
     * incoming [elements] are not mutated.
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
     * Extracts the `elements` array from a serialised Excalidraw scene JSON string,
     * returning an empty array when the input is null/blank/malformed or has no
     * `elements` field. Used to seed the change-detection baseline from the scene
     * extracted from a `.excalidraw.png` at open time.
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

    /**
     * Schedules a debounced autosave of [currentSceneJson].
     *
     * In production (Alarm path): cancels any pending alarm request and schedules a new one
     * after [AUTOSAVE_DEBOUNCE_MS] milliseconds. This ensures only the final scene in a rapid
     * sequence of changes is written (last-write-wins, AC-E3-01).
     *
     * In test mode ([debounceExecutor] non-null): stores the autosave work in [pendingDebounce]
     * (replacing any previously scheduled work) without real timer involvement.
     * Tests call [flushDebounce] to execute the stored work synchronously.
     *
     * Two paths depending on the file extension:
     *
     * **PNG path** (`.excalidraw.png`, task-07-008, AC-E6-02):
     * 1. Registers a one-shot [bridge.registerPngExportedCallback] callback that:
     *    - On success ([BridgeMessage.PngExported.error] is null): decodes the Base64 PNG,
     *      calls [ExcalidrawPersistenceService.writePngScene], clears [_modified].
     *    - On failure ([BridgeMessage.PngExported.error] is non-null): logs a warning;
     *      does NOT write the file (AC-E6-02: no write on error).
     * 2. Calls [bridge.requestPngExport] with [currentSceneJson] to trigger the JS round-trip.
     * 3. [_modified] is cleared only in the callback (after the async write completes).
     *
     * **JSON path** (plain `.excalidraw`, unchanged from prior tasks):
     * 1. Reads [currentSceneJson] (snapshot at the time of scheduling).
     * 2. Normalises it via [ExcalidrawSerializer.serialize].
     * 3. Persists via [ExcalidrawPersistenceService.writeScene].
     * 4. Resets [_modified] to false and fires PROP_MODIFIED (true → false) event (AC-E3-03).
     */
    private fun scheduleAutosave() {
        val autosaveWork = Runnable {
            if (isDisposed) return@Runnable

            if (isExcalidrawPng(file.name)) {
                // Defense-in-depth: never re-export over a .excalidraw.png whose scene
                // was not successfully extracted from the file itself. [onSceneChanged]
                // already refuses to schedule autosave in that case, but guard here too
                // so no future caller can route an unextracted PNG into a destructive write.
                if (!pngSceneExtracted) return@Runnable
                // PNG async path (task-07-008, AC-E6-02):
                // The JS side will re-export the scene as a PNG and post the base64 result
                // back via the bridge.  We must capture currentSceneJson NOW (at schedule
                // time) to pass to requestPngExport — the snapshot is safe from TOCTOU.
                val json = currentSceneJson ?: return@Runnable

                val application = ApplicationManager.getApplication()

                bridge.registerPngExportedCallback { msg ->
                    // Callback arrives on the JCEF/bridge thread — route to EDT (AD-05).
                    val deliver: () -> Unit = deliver@{
                        if (isDisposed) return@deliver
                        if (msg.error != null) {
                            // AC-E6-02: no write on error; log at WARN level (A09).
                            // Do NOT call writePngScene; _modified stays true.
                            LOG.warn(
                                "ExcalidrawFileEditor: PNG export error for " +
                                    "'${file.path}': ${msg.error}"
                            )
                        } else if (msg.base64Png != null) {
                            // AC-E6-02: write the re-embedded PNG via VFS setBinaryContent.
                            persistenceService.writePngScene(file, msg.base64Png)
                            val wasModified = _modified
                            _modified = false
                            if (wasModified) {
                                propertyChangeSupport.firePropertyChange(
                                    FileEditor.getPropModified(), true, false
                                )
                            }
                        }
                        // base64Png == null AND error == null: unexpected — no write,
                        // but also no crash (defensive; not expected in normal operation).
                    }
                    // Re-route to EDT if a real Application is present; otherwise
                    // execute synchronously (test-mode determinism).
                    if (application != null) {
                        application.invokeLater(deliver)
                    } else {
                        deliver()
                    }
                }

                // A03: sceneJson is passed through Gson.toJson inside requestPngExport —
                // no raw string concatenation of file data into JS code.
                bridge.requestPngExport(json)
            } else {
                // JSON path (plain .excalidraw — unchanged from prior tasks):
                val json = currentSceneJson ?: return@Runnable
                val normalized = serializer.serialize(json)
                persistenceService.writeScene(file, normalized)
                val wasModified = _modified
                _modified = false
                if (wasModified) {
                    propertyChangeSupport.firePropertyChange(FileEditor.getPropModified(), true, false)
                }
            }
        }

        if (debounceExecutor != null) {
            // Test mode: store the runnable for manual firing via flushDebounce().
            pendingDebounce = autosaveWork
            debounceExecutor.invoke()
        } else {
            // Production mode: use real Alarm debounce.
            alarm?.cancelAllRequests()
            alarm?.addRequest(autosaveWork, AUTOSAVE_DEBOUNCE_MS.toInt())
        }
    }

    // -------------------------------------------------------------------------
    // FileEditor contract — core methods
    // -------------------------------------------------------------------------

    /**
     * Returns the Swing component that embeds the JCEF browser.
     * Falls back to [fallbackPanel] in test mode (no real browser component).
     */
    override fun getComponent(): JComponent = try {
        jcefHost.component
    } catch (_: IllegalStateException) {
        fallbackPanel
    }

    /**
     * Returns the component that should receive keyboard focus when the editor is activated.
     */
    override fun getPreferredFocusedComponent(): JComponent = try {
        jcefHost.component
    } catch (_: IllegalStateException) {
        fallbackPanel
    }

    /** Human-readable name for this editor type; displayed in the IDE's "Open with" selector. */
    override fun getName(): String = EDITOR_NAME

    // -------------------------------------------------------------------------
    // FileEditor contract — modification and validity stubs
    // -------------------------------------------------------------------------

    /**
     * Returns true when a scene-change event has been received via [onSceneChanged]
     * since the last save. Returns false initially and after a save clears the flag
     * (auto-save phase-04 will reset [_modified]).
     *
     * [IS_MODIFIED_STUB] is retained as a companion constant for backward compatibility.
     */
    override fun isModified(): Boolean = _modified

    /** Returns true — full VFS-lifecycle checks (file deleted/moved) out of scope here. */
    override fun isValid(): Boolean = IS_VALID_STUB

    // -------------------------------------------------------------------------
    // FileEditor contract — state persistence stubs
    // -------------------------------------------------------------------------

    /** Returns the default [FileEditorState.INSTANCE] (no persistent state). */
    override fun getState(level: FileEditorStateLevel): FileEditorState =
        FileEditorState.INSTANCE

    /** No-op — state persistence out of scope. */
    override fun setState(state: FileEditorState) {
        // no-op
    }

    // -------------------------------------------------------------------------
    // FileEditor contract — property-change listeners (no-ops)
    // -------------------------------------------------------------------------

    /**
     * Registers [listener] for PROP_MODIFIED (and future PROP_VALID) events.
     * Backed by [propertyChangeSupport] — events are fired by [onSceneChanged].
     */
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(listener)
    }

    /** Removes a previously registered [PropertyChangeListener]. */
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(listener)
    }

    // -------------------------------------------------------------------------
    // FileEditor contract — file accessor
    // -------------------------------------------------------------------------

    /** Returns the [VirtualFile] this editor is displaying. */
    override fun getFile(): VirtualFile = file

    // -------------------------------------------------------------------------
    // Disposable — dispose chain (AD-3)
    // -------------------------------------------------------------------------

    /**
     * Disposes this editor.
     *
     * [jcefHost] and [bridge] are registered as child-Disposables via [Disposer.register],
     * so the IDE's disposal chain calls their [dispose] methods automatically.
     * AD-3: clean dispose chain — no JCEF browser leaks.
     *
     * Auto-save cleanup (task-04-004):
     * - Sets [isDisposed] to prevent any pending autosave from writing after disposal.
     * - Cancels all pending [alarm] requests (production path) so no EDT callback
     *   fires after the editor is gone.
     * - In test mode, clears [pendingDebounce] so [flushDebounce] becomes a no-op.
     */
    override fun dispose() {
        isDisposed = true
        // Cancel the real Alarm (production path) — no write after dispose (AC-E3, no-leak).
        alarm?.cancelAllRequests()
        // Clear the test-mode pending runnable — flushDebounce() must be a no-op after dispose.
        pendingDebounce = null
        // Child-Disposable cleanup is handled automatically by the Disposer framework.
    }
}
