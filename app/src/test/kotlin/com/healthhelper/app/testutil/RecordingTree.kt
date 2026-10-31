package com.healthhelper.app.testutil

import android.util.Log
import timber.log.Timber

/**
 * Records every Timber call. The app's SentryTimberTree turns ERROR and above into Sentry events,
 * so a zero error count means nothing reached Sentry as an error.
 */
class RecordingTree : Timber.Tree() {
    data class Entry(val priority: Int, val message: String)

    val entries = mutableListOf<Entry>()

    val errorCount: Int get() = entries.count { it.priority >= Log.ERROR }
    val warnCount: Int get() = entries.count { it.priority == Log.WARN }

    fun priorityOf(messagePart: String): Int? = entries.lastOrNull { messagePart in it.message }?.priority

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        entries.add(Entry(priority, message))
    }

    companion object {
        /** Plants a fresh tree, runs [block], and always uproots it. */
        inline fun <T> recording(block: (RecordingTree) -> T): T {
            val tree = RecordingTree()
            Timber.plant(tree)
            try {
                return block(tree)
            } finally {
                Timber.uproot(tree)
            }
        }
    }
}
