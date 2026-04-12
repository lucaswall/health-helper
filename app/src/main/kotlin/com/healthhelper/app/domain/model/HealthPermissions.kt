package com.healthhelper.app.domain.model

object HealthPermissions {
    const val READ_BLOOD_GLUCOSE = "android.permission.health.READ_BLOOD_GLUCOSE"
    const val WRITE_BLOOD_GLUCOSE = "android.permission.health.WRITE_BLOOD_GLUCOSE"
    const val READ_BLOOD_PRESSURE = "android.permission.health.READ_BLOOD_PRESSURE"
    const val WRITE_BLOOD_PRESSURE = "android.permission.health.WRITE_BLOOD_PRESSURE"
    const val READ_HYDRATION = "android.permission.health.READ_HYDRATION"
    const val WRITE_NUTRITION = "android.permission.health.WRITE_NUTRITION"
    const val READ_HEALTH_DATA_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

    /**
     * Full set the app needs to function. Used to check runtime grants on app open.
     */
    val REQUIRED: Set<String> = setOf(
        WRITE_NUTRITION,
        WRITE_BLOOD_PRESSURE,
        READ_BLOOD_PRESSURE,
        WRITE_BLOOD_GLUCOSE,
        READ_BLOOD_GLUCOSE,
        READ_HYDRATION,
        READ_HEALTH_DATA_HISTORY,
    )
}

enum class HealthSyncType { GLUCOSE, BLOOD_PRESSURE, HYDRATION }

val HealthSyncType.readPermission: String
    get() = when (this) {
        HealthSyncType.GLUCOSE -> HealthPermissions.READ_BLOOD_GLUCOSE
        HealthSyncType.BLOOD_PRESSURE -> HealthPermissions.READ_BLOOD_PRESSURE
        HealthSyncType.HYDRATION -> HealthPermissions.READ_HYDRATION
    }
