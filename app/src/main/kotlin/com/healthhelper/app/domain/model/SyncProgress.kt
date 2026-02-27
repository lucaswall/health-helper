package com.healthhelper.app.domain.model

data class SyncProgress(
    val currentDate: String,
    val totalDays: Int,
    val completedDays: Int,
    val recordsSynced: Int,
)
