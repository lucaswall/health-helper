package com.healthhelper.app.data.sync

import android.content.Context
import com.healthhelper.app.domain.repository.SettingsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PermissionNotifierTest {

    private val hydration = "android.permission.health.READ_HYDRATION"
    private val glucose = "android.permission.health.READ_BLOOD_GLUCOSE"
    private val now = 10L * PermissionNotifier.THROTTLE_WINDOW_MS

    @Test
    @DisplayName("shouldPost is false when the set is unchanged and the last post is recent")
    fun suppressesUnchangedRecent() {
        assertFalse(
            PermissionNotifier.shouldPost(setOf(hydration), setOf(hydration), now - 1_000L, now),
        )
    }

    @Test
    @DisplayName("shouldPost is true when the missing set changed")
    fun postsWhenSetChanged() {
        assertTrue(
            PermissionNotifier.shouldPost(setOf(hydration, glucose), setOf(hydration), now - 1_000L, now),
        )
    }

    @Test
    @DisplayName("shouldPost is true when the last post is older than 24h")
    fun postsWhenStale() {
        assertTrue(
            PermissionNotifier.shouldPost(
                setOf(hydration),
                setOf(hydration),
                now - PermissionNotifier.THROTTLE_WINDOW_MS - 1,
                now,
            ),
        )
    }

    @Test
    @DisplayName("shouldPost is false exactly at the 24h boundary with unchanged set")
    fun suppressesAtBoundary() {
        assertFalse(
            PermissionNotifier.shouldPost(
                setOf(hydration),
                setOf(hydration),
                now - PermissionNotifier.THROTTLE_WINDOW_MS,
                now,
            ),
        )
    }

    @Test
    @DisplayName("shouldPost is false when nothing is missing")
    fun neverPostsForEmptySet() {
        assertFalse(PermissionNotifier.shouldPost(emptySet(), setOf(hydration), 0L, now))
    }

    @Test
    @DisplayName("notifyIfNeeded does not persist throttle state when suppressed")
    fun suppressedDoesNotPersist() = runTest {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.missingPermissionsAtLastNotificationFlow } returns flowOf(setOf(hydration))
        every { settings.lastPermissionNotificationTimestampFlow } returns
            flowOf(System.currentTimeMillis())
        val notifier = PermissionNotifier(mockk<Context>(relaxed = true), settings)

        notifier.notifyIfNeeded(setOf(hydration))

        coVerify(exactly = 0) { settings.setLastPermissionNotificationTimestamp(any()) }
        coVerify(exactly = 0) { settings.setMissingPermissionsAtLastNotification(any()) }
    }
}
