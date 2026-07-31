package com.swaroop.excalidraw.plugin.editor

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.swaroop.excalidraw.plugin.bridge.ExcalidrawJsBridge
import com.swaroop.excalidraw.plugin.bridge.SceneChangeMessage
import com.intellij.util.io.HttpRequests
import com.swaroop.excalidraw.plugin.editor.autosave.AlarmScheduler
import com.swaroop.excalidraw.plugin.editor.autosave.AutosaveController
import com.swaroop.excalidraw.plugin.editor.autosave.ManualScheduler
import com.swaroop.excalidraw.plugin.editor.autosave.Scheduler
import com.swaroop.excalidraw.plugin.jcef.ExcalidrawJcefHost
import com.swaroop.excalidraw.plugin.jcef.LibraryBrowserDialog
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawLibraryService
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawParseException
import com.swaroop.excalidraw.plugin.persistence.ExcalidrawPersistenceService
import com.swaroop.excalidraw.plugin.persistence.document.JsonSceneDocument
import com.swaroop.excalidraw.plugin.persistence.document.PngSceneDocument
import com.swaroop.excalidraw.plugin.persistence.document.SceneDocument
import com.swaroop.excalidraw.plugin.persistence.document.SceneLoadResult
import com.swaroop.excalidraw.plugin.persistence.document.SceneSaveResult
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
    /**
     * Owns load/save for this file's on-disk scene format (plain JSON vs
     * scene-embedded PNG) — see [SceneDocument]. Chosen once, in the factory
     * methods below, based on [isExcalidrawPng]; the editor never branches on
     * file format again after that.
     */
    private val document: SceneDocument,
    private val notifier: (String) -> Unit,
    /**
     * Debounce seam for [autosave]'s scheduled writes. Null (production) resolves to
     * an [AlarmScheduler] bound to this editor's lifetime; tests inject a
     * [ManualScheduler] (fired synchronously and deterministically via
     * [ManualScheduler.flush]).
     */
    private val scheduler: Scheduler?,
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
         * Retained for backwards compatibility — real tracking is delegated to [autosave].
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

            val persistenceService = ExcalidrawPersistenceService()
            val document: SceneDocument = if (isExcalidrawPng(file.name)) {
                PngSceneDocument(persistenceService)
            } else {
                JsonSceneDocument(persistenceService)
            }

            return ExcalidrawFileEditor(
                project = project,
                file = file,
                jcefHost = host,
                bridge = bridge,
                persistenceService = persistenceService,
                document = document,
                notifier = notifier,
                scheduler = null,
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
         * The [scheduler] parameter, when non-null, replaces the real [Alarm]-based
         * debounce with a test-mode [Scheduler] (typically [ManualScheduler], fired
         * synchronously and deterministically via [ManualScheduler.flush]).
         * When null (default), the production [AlarmScheduler] is used.
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
            scheduler: Scheduler? = null,
            themeController: ExcalidrawThemeController? = null
        ): ExcalidrawFileEditor {
            val document: SceneDocument = if (isExcalidrawPng(file.name)) {
                PngSceneDocument(persistenceService)
            } else {
                JsonSceneDocument(persistenceService)
            }
            return ExcalidrawFileEditor(
                project = null,
                file = file,
                jcefHost = jcefHost,
                bridge = bridge,
                persistenceService = persistenceService,
                document = document,
                notifier = notifier,
                scheduler = scheduler,
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
    // Auto-save & scene-change tracking (AD-05)
    // -------------------------------------------------------------------------

    /**
     * Tracks whether the editor has been disposed — checked by [openLibraryBrowser]'s
     * async callback to avoid touching a disposed bridge. [autosave] tracks its own,
     * separate disposed state for its own callbacks (write results, scheduled runs).
     */
    @Volatile
    private var isDisposed: Boolean = false

    /** Propagates PROP_MODIFIED change events to registered [PropertyChangeListener]s. */
    private val propertyChangeSupport: PropertyChangeSupport = PropertyChangeSupport(this)

    /**
     * Owns the autosave + change-detection state machine (see [AutosaveController]):
     * [currentSceneJson], [isModified], baseline tracking, and the debounced write
     * trigger. [scheduler] is the debounce seam ([AlarmScheduler] bound to this
     * editor's lifetime in production, an injected [ManualScheduler] in tests).
     * [onModifiedChanged] re-fires the exact PROP_MODIFIED event [onSceneChanged] /
     * the old `scheduleAutosave` used to fire separately. [write] delegates the
     * actual persistence to [document] — the controller itself is format-agnostic.
     */
    private val autosave: AutosaveController = AutosaveController(
        scheduler = scheduler ?: AlarmScheduler(this),
        debounceMs = AUTOSAVE_DEBOUNCE_MS,
        onModifiedChanged = { isModifiedNow ->
            propertyChangeSupport.firePropertyChange(FileEditor.getPropModified(), !isModifiedNow, isModifiedNow)
        },
        write = { json, onResult -> document.save(file, json, bridge, onResult) }
    )

    /**
     * The most-recently received scene state. Null until the first [onSceneChanged] call
     * (or, for a format whose load protocol reconciles the canvas up front, until load
     * completes — see [SceneDocument]/[AutosaveController.arm]). Delegates to [autosave].
     */
    val currentSceneJson: String?
        get() = autosave.currentSceneJson

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
     * onLoadEnd (AD-04). It delegates the format-specific work (plain JSON vs
     * scene-embedded PNG) entirely to [document] (see [SceneDocument]) and only
     * interprets the sealed [SceneLoadResult] it reports back:
     *  - [SceneLoadResult.LoadedAndBaselined]: [AutosaveController.arm] seeds
     *    [currentSceneJson] and the change-detection baseline immediately (the
     *    format's own round-trip already reconciled the canvas against the file).
     *  - [SceneLoadResult.LoadedAwaitingEcho]: [AutosaveController.awaitEcho] arms
     *    event processing; the canvas's own first `onChange` establishes the
     *    baseline instead (see [onSceneChanged]).
     *  - [SceneLoadResult.Failed]: [notifier] is called (AD-03); no VirtualFile write,
     *    [autosave] is left unarmed. [ExcalidrawThemeController.pushCurrentTheme]
     * and [restorePersistedLibrary] run right after triggering the load, skipped only on
     * a (synchronous) load failure.
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
                // AD-04/AD-05: [document] owns the format-specific load protocol (plain
                // JSON vs scene-embedded PNG, see SceneDocument); this call may complete
                // synchronously (JSON) or after a bridge round-trip (PNG extraction).
                var failed = false
                document.load(file, bridge) { result ->
                    when (result) {
                        is SceneLoadResult.LoadedAndBaselined -> autosave.arm(result.sceneJson)
                        SceneLoadResult.LoadedAwaitingEcho -> {
                            // The canvas will fire its own render/onChange echo once mounted;
                            // that first onSceneChanged call establishes the baseline instead
                            // (see the `baseline == null` branch there).
                            autosave.awaitEcho()
                        }
                        is SceneLoadResult.Failed -> {
                            // AD-03: invoke the notifier hook, do NOT write VirtualFile.
                            // A09: only the human-readable message is surfaced — no stack trace.
                            LOG.warn("ExcalidrawFileEditor: load error for '${file.path}': ${result.message}")
                            notifier(result.message)
                            failed = true
                        }
                    }
                }
                // AC-E4-01 timing: push the initial theme (and restore the library) right
                // after triggering the load — for a synchronous format this runs after the
                // callback above already fired; for an async format (PNG) it runs before the
                // round-trip completes, matching __excalidrawSetTheme__'s availability once
                // the page has rendered either way. Skipped entirely on a (synchronous) load
                // failure, since nothing was pushed to the canvas to theme.
                if (!failed) {
                    themeController?.pushCurrentTheme()
                    restorePersistedLibrary()
                }
                // Any other (unexpected) exception propagates to the EDT exception handler
                // and surfaces as an IDE error dialog — intentional (A09: don't swallow unknowns).
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
     * Runs on the EDT (the bridge dispatches via `invokeLater`, AD-05). As a defensive
     * measure, this method re-routes to the EDT if called from an unexpected thread.
     *
     * A thin adapter: serialises [scene] to a JSON string via [Gson.toJson] and hands
     * it, along with the raw `elements` array, to [AutosaveController.onSceneChanged] —
     * all dirty-tracking, baseline comparison, and debounced-write scheduling lives
     * there now (including PROP_MODIFIED firing, wired once at [autosave]'s construction).
     *
     * A03: [scene] originates from the Excalidraw web app and is already deserialised
     * via Gson at the bridge layer — no raw string concatenation or code execution here.
     */
    fun onSceneChanged(scene: SceneChangeMessage) {
        val work: () -> Unit = {
            autosave.onSceneChanged(GSON.toJson(scene), scene.elements)
        }

        val application = ApplicationManager.getApplication()
        if (application != null && !application.isDispatchThread) {
            application.invokeLater(work)
        } else {
            work()
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

    /**
     * Re-pushes the current IDE theme whenever this tab becomes the selected editor.
     *
     * Background rationale: [ExcalidrawThemeController] pushes theme updates to every open
     * editor's browser via the live [com.intellij.ide.ui.LafManagerListener] callback (fired
     * on the Application message bus), so in principle all open tabs should update together
     * when the user switches the IDE theme. In practice, background (non-selected) JCEF
     * browser components are not always fully realized/attached to a native peer while
     * hidden, so a theme update delivered while a tab is in the background can be missed or
     * left un-rendered by that tab's Chromium instance. Re-pushing on [selectNotify] (called
     * by the IDE every time this tab becomes visible/active) is a cheap, idempotent
     * belt-and-suspenders fix: it guarantees the visible tab always reflects the current IDE
     * theme the moment the user switches to it, regardless of whether the live push while it
     * was in the background actually landed.
     *
     * Guarded by [ExcalidrawThemeController.isReady]: before the loadEnd callback has
     * fired its own initial [ExcalidrawThemeController.pushCurrentTheme] call, the page
     * has not navigated/rendered yet and `window.__excalidrawSetTheme__` is not defined.
     * Re-pushing at that point is unreliable and was observed to leave freshly-opened
     * tabs stuck showing the wrong (light) theme on first open — regardless of the
     * IDE's actual theme — because `selectNotify` fires as soon as a newly-opened tab
     * becomes active, which is typically well before JCEF's async page load completes.
     * Once [ExcalidrawThemeController.isReady] is true, re-pushing is safe and this is a
     * no-op after disposal (guarded internally by [ExcalidrawThemeController.pushCurrentTheme]).
     *
     * Also calls [ExcalidrawJsBridge.triggerCanvasRefresh]: re-pushing the theme alone is
     * not enough to fix a background tab that already had the correct theme value applied
     * in its JS state (e.g. via the live [com.intellij.ide.ui.LafManagerListener] push while
     * it was hidden) but never got a chance to actually repaint its canvas — React bails
     * out of re-rendering when [ExcalidrawJsBridge.sendThemeUpdate] is called again with an
     * unchanged value, so no new redraw would otherwise be scheduled. Dispatching a
     * synthetic `resize` event forces Excalidraw to redraw its canvas unconditionally.
     */
    override fun selectNotify() {
        themeController?.let { controller ->
            if (controller.isReady) {
                controller.pushCurrentTheme()
                bridge.triggerCanvasRefresh()
            }
        }
    }

    // -------------------------------------------------------------------------
    // FileEditor contract — modification and validity stubs
    // -------------------------------------------------------------------------

    /**
     * Returns true when a scene-change event has been received via [onSceneChanged]
     * since the last save. Returns false initially and after a save clears the flag.
     * Delegates to [AutosaveController.isModified].
     *
     * [IS_MODIFIED_STUB] is retained as a companion constant for backward compatibility.
     */
    override fun isModified(): Boolean = autosave.isModified

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
     * Auto-save cleanup:
     * - Sets [isDisposed] to guard this editor's own async callbacks (e.g. the library
     *   browser's).
     * - [AutosaveController.dispose] cancels its scheduler (no write after dispose) and
     *   its own callbacks become no-ops.
     */
    override fun dispose() {
        isDisposed = true
        autosave.dispose()
        // Child-Disposable cleanup is handled automatically by the Disposer framework.
    }
}
