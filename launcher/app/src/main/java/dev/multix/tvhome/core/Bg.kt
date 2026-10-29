package dev.multix.tvhome.core

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.concurrent.Executors

/**
 * One background thread for every piece of disk, network and image work. A
 * single thread keeps the CPU free for the UI and render threads on a four
 * core A53, and it serialises access to the caches so they need no locking.
 */
object Bg {
    private const val TAG = "Sukoon"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "sukoon-bg")
    }

    val main = Handler(Looper.getMainLooper())

    fun run(task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (t: Throwable) {
                Log.w(TAG, "background task failed", t)
            }
        }
    }

    /** Runs [work] in the background and hands the result to [done] on the main thread. */
    fun <T> load(work: () -> T, done: (T) -> Unit) {
        run {
            val result = work()
            main.post { done(result) }
        }
    }

    fun ui(block: () -> Unit) {
        main.post(block)
    }

    fun warn(msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t)
    }
}
