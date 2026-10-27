package com.healthhelper.app.domain.model

/**
 * Why an image-parse request failed. Lets the UI tell the user whether retaking the photo
 * can help (UNREADABLE) or whether something else is wrong (bad key, no network, service busy).
 */
enum class ImageParseErrorKind {
    AUTH,
    RATE_LIMITED,
    SERVER,
    NETWORK,
    UNREADABLE,
    UNKNOWN,
}
