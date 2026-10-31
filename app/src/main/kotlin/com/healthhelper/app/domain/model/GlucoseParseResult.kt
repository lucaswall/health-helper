package com.healthhelper.app.domain.model

sealed class GlucoseParseResult {
    data class Success(
        val value: Double,
        val detectedUnit: GlucoseUnit,
    ) : GlucoseParseResult()

    data class Error(
        val message: String,
        val kind: ImageParseErrorKind = ImageParseErrorKind.UNREADABLE,
        /**
         * The model's explanation of why the display couldn't be read, written for the user. It can
         * quote digits off the display, so it is shown but never logged above DEBUG.
         */
        val detail: String? = null,
    ) : GlucoseParseResult()
}
