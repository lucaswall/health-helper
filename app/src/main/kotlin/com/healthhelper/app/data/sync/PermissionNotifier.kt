package com.healthhelper.app.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.healthhelper.app.MainActivity
import com.healthhelper.app.R
import com.healthhelper.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Posts a notification when background sync detects missing Health Connect permissions.
 * Throttles so the user isn't spammed every sync tick: only posts if the missing set
 * changed OR >24h since the last post.
 */
class PermissionNotifier(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun notifyIfNeeded(missingPermissions: Set<String>) {
        if (missingPermissions.isEmpty()) {
            cancel()
            return
        }

        val previousSet = settingsRepository.missingPermissionsAtLastNotificationFlow.first()
        val previousTs = settingsRepository.lastPermissionNotificationTimestampFlow.first()
        val now = System.currentTimeMillis()
        val setChanged = previousSet != missingPermissions
        val stale = now - previousTs > THROTTLE_WINDOW_MS

        if (!setChanged && !stale) {
            Timber.d("PermissionNotifier: suppressed (set unchanged, last=%dms ago)", now - previousTs)
            return
        }

        ensureChannel()
        val postedOk = post(missingPermissions)
        if (postedOk) {
            settingsRepository.setLastPermissionNotificationTimestamp(now)
            settingsRepository.setMissingPermissionsAtLastNotification(missingPermissions)
        }
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Permissions required",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alerts when Health Connect permissions are missing and sync is paused"
        }
        manager.createNotificationChannel(channel)
    }

    private fun post(missing: Set<String>): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Timber.d("PermissionNotifier: notifications disabled, skipping post")
            return false
        }

        val missingSummary = missing
            .map { labelFor(it) }
            .sorted()
            .joinToString(", ")
            .ifEmpty { "one or more Health Connect permissions" }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            PENDING_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Health Connect permission needed")
            .setContentText("Sync paused: $missingSummary")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Sync paused until you grant: $missingSummary. Tap to fix."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted on Android 13+; user will see banner on next app open.
            Timber.w(e, "PermissionNotifier: POST_NOTIFICATIONS not granted")
            false
        }
    }

    private fun labelFor(permission: String): String = when (permission) {
        "android.permission.health.READ_BLOOD_GLUCOSE" -> "read glucose"
        "android.permission.health.WRITE_BLOOD_GLUCOSE" -> "write glucose"
        "android.permission.health.READ_BLOOD_PRESSURE" -> "read blood pressure"
        "android.permission.health.WRITE_BLOOD_PRESSURE" -> "write blood pressure"
        "android.permission.health.READ_HYDRATION" -> "read hydration"
        "android.permission.health.WRITE_NUTRITION" -> "write nutrition"
        "android.permission.health.READ_HEALTH_DATA_HISTORY" -> "read history > 30 days"
        else -> permission.substringAfterLast('.').lowercase().replace('_', ' ')
    }

    companion object {
        const val CHANNEL_ID = "permissions_required"
        const val NOTIFICATION_ID = 1001
        private const val PENDING_INTENT_REQUEST_CODE = 100
        private const val THROTTLE_WINDOW_MS = 24L * 60 * 60 * 1000
    }
}
