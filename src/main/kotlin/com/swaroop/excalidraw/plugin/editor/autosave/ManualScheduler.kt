package com.swaroop.excalidraw.plugin.editor.autosave

/**
 * Test [Scheduler]: stores the scheduled action instead of using a real timer.
 * Tests call [flush] to run it synchronously and deterministically — replacing the
 * old hand-rolled `debounceExecutor`/`pendingDebounce`/`flushDebounce` trio that
 * lived directly on `ExcalidrawFileEditor`.
 */
class ManualScheduler : Scheduler {

    var pending: (() -> Unit)? = null
        private set

    override fun schedule(delayMs: Long, action: () -> Unit) {
        pending = action
    }

    override fun cancel() {
        pending = null
    }

    /** Runs and clears the pending action, if any (no-op otherwise). */
    fun flush() {
        val action = pending
        pending = null
        action?.invoke()
    }
}
