package com.healthhelper.app.domain.usecase

import com.healthhelper.app.data.HealthConnectStatusProvider
import com.healthhelper.app.domain.model.HealthConnectStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CheckHealthConnectStatusUseCaseTest {

    private lateinit var statusProvider: HealthConnectStatusProvider
    private lateinit var useCase: CheckHealthConnectStatusUseCase

    @BeforeEach
    fun setup() {
        statusProvider = mockk()
        useCase = CheckHealthConnectStatusUseCase(statusProvider)
    }

    @Test
    @DisplayName("returns Available when provider reports Available")
    fun sdkAvailable() {
        every { statusProvider.getStatus() } returns HealthConnectStatus.Available

        val result = useCase()

        assertEquals(HealthConnectStatus.Available, result)
    }

    @Test
    @DisplayName("returns NotInstalled when provider reports NotInstalled")
    fun sdkUnavailable() {
        every { statusProvider.getStatus() } returns HealthConnectStatus.NotInstalled

        val result = useCase()

        assertEquals(HealthConnectStatus.NotInstalled, result)
    }

    @Test
    @DisplayName("returns NeedsUpdate when provider reports NeedsUpdate")
    fun sdkNeedsUpdate() {
        every { statusProvider.getStatus() } returns HealthConnectStatus.NeedsUpdate

        val result = useCase()

        assertEquals(HealthConnectStatus.NeedsUpdate, result)
    }
}
