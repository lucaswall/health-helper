package com.healthhelper.app.domain.model

/**
 * Errors reported by the Food Scanner API. They live in the domain layer so use cases can
 * classify failures (e.g. retryable vs. not) without depending on the data-layer HTTP client.
 */
open class FoodScannerApiException(
    message: String,
    val httpStatus: Int,
) : Exception(message)

/** HTTP 429 — retryable after backoff. */
class RateLimitException(
    httpStatus: Int = 429,
) : FoodScannerApiException("Rate limited", httpStatus)

/** HTTP 401 — not retryable; credentials must be fixed. */
class AuthenticationException(
    httpStatus: Int = 401,
) : FoodScannerApiException("Authentication failed", httpStatus)

/** HTTP 5xx — retryable after backoff. */
class ServerException(
    httpStatus: Int,
) : FoodScannerApiException("Server unavailable", httpStatus)
