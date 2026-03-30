package com.healthhelper.app.data.api

open class FoodScannerApiException(
    message: String,
    val httpStatus: Int,
) : Exception(message)

class RateLimitException(
    httpStatus: Int = 429,
) : FoodScannerApiException("Rate limited", httpStatus)

class AuthenticationException(
    httpStatus: Int = 401,
) : FoodScannerApiException("Authentication failed", httpStatus)

class ServerException(
    httpStatus: Int,
) : FoodScannerApiException("Server unavailable", httpStatus)
