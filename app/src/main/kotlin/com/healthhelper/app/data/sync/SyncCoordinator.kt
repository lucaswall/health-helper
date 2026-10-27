package com.healthhelper.app.data.sync

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes the periodic [SyncWorker] and the manual "Sync now" path so both never
 * run the nutrition / health-readings sync at the same time (which would double
 * Health Connect quota use and could move sync watermarks backwards).
 *
 * Callers never wait: if a sync is already in progress, [runIfIdle] returns null.
 */
@Singleton
class SyncCoordinator @Inject constructor() {

    private val mutex = Mutex()

    val isSyncing: Boolean
        get() = mutex.isLocked

    /** Acquires the sync lock without waiting. Every `true` must be paired with [release]. */
    fun tryAcquire(): Boolean = mutex.tryLock()

    fun release() {
        mutex.unlock()
    }

    /** Runs [block] if no other sync holds the lock; returns null without running it otherwise. */
    suspend fun <T> runIfIdle(block: suspend () -> T): T? {
        if (!tryAcquire()) return null
        try {
            return block()
        } finally {
            release()
        }
    }
}
