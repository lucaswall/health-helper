package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.repository.BloodGlucoseRepository
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class GetLastGlucoseReadingUseCase @Inject constructor(
    private val bloodGlucoseRepository: BloodGlucoseRepository,
) {
    suspend operator fun invoke(): GlucoseReading? = try {
        bloodGlucoseRepository.getLastReading()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "GetLastGlucoseReading: failed")
        null
    }
}
