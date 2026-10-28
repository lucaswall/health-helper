package com.healthhelper.app.domain.model

object HealthPermissions {
    const val READ_BLOOD_GLUCOSE = "android.permission.health.READ_BLOOD_GLUCOSE"
    const val WRITE_BLOOD_GLUCOSE = "android.permission.health.WRITE_BLOOD_GLUCOSE"
    const val READ_BLOOD_PRESSURE = "android.permission.health.READ_BLOOD_PRESSURE"
    const val WRITE_BLOOD_PRESSURE = "android.permission.health.WRITE_BLOOD_PRESSURE"
    const val WRITE_HEART_RATE = "android.permission.health.WRITE_HEART_RATE"
    const val READ_HYDRATION = "android.permission.health.READ_HYDRATION"
    const val WRITE_NUTRITION = "android.permission.health.WRITE_NUTRITION"
    const val READ_HEALTH_DATA_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
    const val READ_HEALTH_DATA_IN_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    /**
     * Permissions the core features (manual sync, BP/glucose logging, hydration display) need.
     * Missing any of these blocks the corresponding actions in the UI.
     */
    val CORE: Set<String> = setOf(
        WRITE_NUTRITION,
        WRITE_BLOOD_PRESSURE,
        READ_BLOOD_PRESSURE,
        WRITE_BLOOD_GLUCOSE,
        READ_BLOOD_GLUCOSE,
        READ_HYDRATION,
    )

    /**
     * Permissions that only add to a core feature: background hydration reads and history older
     * than 30 days (feature-gated — some HC builds can't grant them), and heart rate saved alongside
     * blood pressure. They are requested, but never block foreground actions: without heart rate
     * the blood pressure reading is still saved.
     */
    val OPTIONAL: Set<String> = setOf(
        READ_HEALTH_DATA_HISTORY,
        READ_HEALTH_DATA_IN_BACKGROUND,
        WRITE_HEART_RATE,
    )

    /**
     * Full set the app asks for, before feature-availability filtering.
     */
    val REQUIRED: Set<String> = CORE + OPTIONAL
}

enum class HealthSyncType { GLUCOSE, BLOOD_PRESSURE, HYDRATION }

val HealthSyncType.readPermission: String
    get() = when (this) {
        HealthSyncType.GLUCOSE -> HealthPermissions.READ_BLOOD_GLUCOSE
        HealthSyncType.BLOOD_PRESSURE -> HealthPermissions.READ_BLOOD_PRESSURE
        HealthSyncType.HYDRATION -> HealthPermissions.READ_HYDRATION
    }
