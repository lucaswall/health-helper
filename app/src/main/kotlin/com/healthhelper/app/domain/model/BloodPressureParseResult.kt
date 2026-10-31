package com.healthhelper.app.domain.model

sealed class BloodPressureParseResult {
    data class Success(
        val systolic: Int,
        val diastolic: Int,
        /** Pulse shown on the monitor, or null when it wasn't visible or wasn't plausible. */
        val heartRate: Int? = null,
    ) : BloodPressureParseResult()

    data class Error(
        val message: String,
        val kind: ImageParseErrorKind = ImageParseErrorKind.UNREADABLE,
        /**
         * The model's explanation of why the display couldn't be read, written for the user. It can
         * quote digits off the display, so it is shown but never logged above DEBUG.
         */
        val detail: String? = null,
    ) : BloodPressureParseResult()
}
