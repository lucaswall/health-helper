package com.healthhelper.app.data.api

open class FoodScannerApiException(message: String, val httpStatus: Int) : Exception(message)

class RateLimitException(message: String) : FoodScannerApiException(message, 429)

class AuthenticationException(message: String) : FoodScannerApiException(message, 401)

class ServerException(message: String, httpStatus: Int) : FoodScannerApiException(message, httpStatus)
