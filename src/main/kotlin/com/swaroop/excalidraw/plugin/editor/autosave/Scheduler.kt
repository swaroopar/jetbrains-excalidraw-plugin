package com.swaroop.excalidraw.plugin.editor.autosave

import com.intellij.openapi.Disposable
import com.intellij.util.Alarm

/**
 * A debounce scheduler: [schedule] replaces any previously scheduled action (last-write-wins),
 * [cancel] drops a pending action without running it.
 */
interface Scheduler {
    fun schedule(delayMs: Long, action: () -> Unit)
    fun cancel()
}

/**
 * Production [Scheduler] backed by a real [Alarm] bound to [disposable]'s lifetime —
 * cancelled automatically when [disposable] is disposed.
 */
class AlarmScheduler(disposable: Disposable) : Scheduler {

    private val alarm: Alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

    override fun schedule(delayMs: Long, action: () -> Unit) {
        alarm.cancelAllRequests()
        alarm.addRequest(action, delayMs.toInt())
    }

    override fun cancel() {
        alarm.cancelAllRequests()
    }
}
