package com.healthhelper.app.data.api

import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.serialization.ContentConvertException
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import kotlinx.serialization.SerializationException

/**
 * Network-level failure (offline, DNS, timeout). Subclasses [IOException] so retry logic that
 * checks `is IOException` keeps treating it as transient. The original exception is kept as cause.
 */
class NetworkException(
    message: String,
    cause: Throwable,
) : IOException(message, cause)

/** The response body could not be decoded into the expected shape. Not retryable. */
class ResponseParseException(
    cause: Throwable,
) : Exception("Failed to parse response", cause)

/** Any other unexpected client-side failure, with a user-facing message. */
class UnexpectedApiException(
    cause: Throwable,
) : Exception("Unexpected error communicating with server", cause)

internal object ApiErrors {

    /**
     * Ktor 3 wraps kotlinx decode errors in [ContentConvertException] (JsonConvertException);
     * a non-JSON content type (e.g. a 200 text/html proxy page) yields [NoTransformationFoundException].
     */
    fun isParseError(e: Throwable): Boolean =
        e is ContentConvertException ||
            e is NoTransformationFoundException ||
            e is SerializationException

    fun isNetworkError(e: Throwable): Boolean =
        e is IOException || e is UnresolvedAddressException

    fun isTimeout(e: Throwable): Boolean =
        e is HttpRequestTimeoutException ||
            e is ConnectTimeoutException ||
            e is SocketTimeoutException ||
            e is java.net.SocketTimeoutException

    /** Maps an arbitrary non-cancellation exception to a typed exception with a user-facing message. */
    fun toUserFacing(e: Exception): Exception = when {
        isParseError(e) -> ResponseParseException(e)
        e is NetworkException -> e
        isNetworkError(e) && isTimeout(e) -> NetworkException("Request timed out", e)
        isNetworkError(e) -> NetworkException("Network unavailable", e)
        else -> UnexpectedApiException(e)
    }

    /**
     * Returns the normalized (trimmed, no trailing slash) base URL when it is a valid HTTPS URL
     * with a non-empty host, or null otherwise.
     */
    fun normalizeHttpsBaseUrl(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (!trimmed.lowercase().startsWith("https://")) return null
        val host = trimmed.substring("https://".length).substringBefore('/').substringBefore('?')
        if (host.isBlank()) return null
        val parsed = try {
            Url(trimmed)
        } catch (_: Exception) {
            return null
        }
        if (parsed.protocol != URLProtocol.HTTPS || parsed.host.isBlank()) return null
        return trimmed.trimEnd('/')
    }
}
