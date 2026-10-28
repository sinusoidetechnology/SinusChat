package pl.sinusoide.sinuschat.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object AppExecutor {
    private val pool = Executors.newFixedThreadPool(4)
    private val localPool = Executors.newSingleThreadExecutor()
    private val outbox = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun io(block: () -> Unit) = run(pool, block)

    fun local(block: () -> Unit) = run(localPool, block)

    fun send(block: () -> Unit) = run(outbox, block)

    private fun run(executor: Executor, block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (e: Throwable) {
                if (e.message == "DATABASE_LOCKED") {
                    Log.w("AppExecutor", "Zadanie zignorowane: Baza danych jest zablokowana.")
                } else {
                    Log.e("AppExecutor", "Background task failed", e)
                }
            }
        }
    }

    fun main(block: () -> Unit) {
        mainHandler.post(block)
    }
}
