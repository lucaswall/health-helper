package com.healthhelper.app.data.api

// The exception hierarchy lives in the domain layer (domain must not depend on data).
// These aliases keep existing data-layer call sites compiling unchanged.
typealias FoodScannerApiException = com.healthhelper.app.domain.model.FoodScannerApiException
typealias RateLimitException = com.healthhelper.app.domain.model.RateLimitException
typealias AuthenticationException = com.healthhelper.app.domain.model.AuthenticationException
typealias ServerException = com.healthhelper.app.domain.model.ServerException
