package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.WaistReading
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.domain.repository.WaistReadingRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LogWaistReadingUseCaseTest {

    private val repository = mockk<WaistReadingRepository>()
    private val syncPending = mockk<SyncPendingWaistReadingsUseCase>()
    private val settings = mockk<SettingsRepository> { coEvery { isConfigured() } returns true }
    private val useCase = LogWaistReadingUseCase(repository, syncPending, settings)
    private val reading = WaistReading(valueCm = 104.5)

    @Test
    @DisplayName("saves locally before sending, and reports SYNCED when nothing is left pending")
    fun savedThenSynced() = runTest {
        coJustRun { repository.save(reading) }
        coEvery { syncPending() } returns 0

        assertEquals(WaistSaveResult.SYNCED, useCase(reading))
        coVerifyOrder {
            repository.save(reading)
            syncPending()
        }
    }

    @Test
    @DisplayName("reports PENDING when food-scanner could not be reached")
    fun pendingWhenOffline() = runTest {
        coJustRun { repository.save(reading) }
        coEvery { syncPending() } returns 1

        assertEquals(WaistSaveResult.PENDING, useCase(reading))
    }

    @Test
    @DisplayName("reports PENDING when the send throws")
    fun pendingWhenSendThrows() = runTest {
        coJustRun { repository.save(reading) }
        coEvery { syncPending() } throws RuntimeException("boom")

        assertEquals(WaistSaveResult.PENDING, useCase(reading))
    }

    @Test
    @DisplayName("reports FAILED and sends nothing when the local save fails")
    fun failedLocalSave() = runTest {
        coEvery { repository.save(reading) } throws RuntimeException("disk full")

        assertEquals(WaistSaveResult.FAILED, useCase(reading))
        coVerify(exactly = 0) { syncPending() }
    }

    @Test
    @DisplayName("reports PENDING_NOT_CONFIGURED and sends nothing when food-scanner isn't set up")
    fun notConfigured() = runTest {
        coJustRun { repository.save(reading) }
        coEvery { settings.isConfigured() } returns false

        assertEquals(WaistSaveResult.PENDING_NOT_CONFIGURED, useCase(reading))
        coVerify(exactly = 0) { syncPending() }
    }
}
