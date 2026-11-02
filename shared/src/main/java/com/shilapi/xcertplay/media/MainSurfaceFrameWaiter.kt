package com.shilapi.xcertplay.media

import android.view.Surface

/** One-shot evidence that a frame was submitted to the host's current output, not a mirror. */
internal class MainSurfaceFrameWaiter {
    private data class Pending(val surface: Surface, val callback: (Long) -> Unit)
    private val lock = Any()
    private var pending: Pending? = null
    private var closed = false

    fun await(surface: Surface, callback: (Long) -> Unit) = synchronized(lock) {
        if (!closed) pending = Pending(surface, callback)
    }

    fun cancel(surface: Surface? = null) = synchronized(lock) {
        if (surface == null || pending?.surface === surface) pending = null
    }

    fun onFrameSubmitted(surface: Surface, delayMillis: Long) {
        val callback = synchronized(lock) {
            pending?.takeIf { it.surface === surface }?.also { pending = null }?.callback
        }
        callback?.invoke(delayMillis)
    }

    fun close() = synchronized(lock) {
        closed = true
        pending = null
    }
}
