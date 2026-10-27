package com.healthhelper.app.presentation.viewmodel

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncFormattersTest {

    private val now = Instant.parse("2026-03-15T12:00:00Z").toEpochMilli()
    private val dayMs = 24 * 60 * 60 * 1000L

    @ParameterizedTest
    @CsvSource(
        "0, today",
        "1, 1 day ago",
        "2, 2 days ago",
        "7, 1 week ago",
        "13, 1 week ago",
        "14, 2 weeks ago",
        "30, 1 month ago",
        "60, 2 months ago",
        "365, 1 year ago",
        "730, 2 years ago",
    )
    fun `formatHistoryAge pluralizes correctly`(days: Long, expected: String) {
        assertEquals(expected, formatHistoryAge(now - days * dayMs, now))
    }

    @Test
    fun `formatHistoryAge returns empty for non-positive timestamp`() {
        assertEquals("", formatHistoryAge(0L, now))
    }

    @Test
    fun `formatRelativeTime uses singular day`() {
        assertEquals("1 day ago", formatRelativeTime(now - dayMs, now))
        assertEquals("3 days ago", formatRelativeTime(now - 3 * dayMs, now))
    }

    @Test
    fun `formatHydrationHistoryStatus uses singular day`() {
        assertEquals(
            "History: synced to 1 day ago",
            formatHydrationHistoryStatus(now - dayMs, caughtUp = false, runTimestamp = now, now = now),
        )
    }

    @Test
    fun `formatNextSyncTime shows less than a minute`() {
        assertEquals("Next sync in <1m", formatNextSyncTime(now + 30_000L, now))
    }

    @Test
    fun `formatNextSyncTime shows minutes under an hour`() {
        assertEquals("Next sync in ~14m", formatNextSyncTime(now + 14 * 60_000L + 5_000L, now))
    }

    @Test
    fun `formatNextSyncTime shows pending when in the past`() {
        assertEquals("Sync pending...", formatNextSyncTime(now - 1_000L, now))
    }

    @Test
    fun `formatNextSyncTime uses localized clock time beyond an hour`() {
        val text = formatNextSyncTime(now + 3 * 60 * 60_000L, now)
        assertTrue(text.startsWith("Next sync at "), "got '$text'")
    }
}
