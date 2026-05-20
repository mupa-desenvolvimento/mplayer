package com.mupa.player.enterprise.managers

import android.os.Handler
import android.os.Looper
import com.mupa.player.enterprise.services.CommandAck
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object MupaCommandCenter {
    @Volatile
    private var handler: ((String) -> CommandAck)? = null

    fun setHandler(newHandler: ((String) -> CommandAck)?) {
        handler = newHandler
    }

    fun dispatch(json: String, timeoutMs: Long = 2500): CommandAck? {
        val h = handler ?: return null

        val latch = CountDownLatch(1)
        val ref = AtomicReference<CommandAck?>()

        Handler(Looper.getMainLooper()).post {
            ref.set(runCatching { h(json) }.getOrNull())
            latch.countDown()
        }

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return ref.get()
    }
}

