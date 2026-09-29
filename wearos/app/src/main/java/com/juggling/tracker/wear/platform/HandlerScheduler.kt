package com.juggling.tracker.wear.platform

import android.os.Handler
import android.os.Looper
import com.juggling.tracker.wear.logic.Cancellable
import com.juggling.tracker.wear.logic.Scheduler

/** Runs the sessions' timers on the main thread. */
class HandlerScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : Scheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}
