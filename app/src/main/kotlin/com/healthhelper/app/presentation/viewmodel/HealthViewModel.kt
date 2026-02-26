package com.healthhelper.app.presentation.viewmodel

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.domain.model.HealthConnectStatus
import com.healthhelper.app.domain.model.HealthRecord
import com.healthhelper.app.domain.model.PermissionStatus
import com.healthhelper.app.domain.usecase.CheckHealthConnectStatusUseCase
import com.healthhelper.app.domain.usecase.ReadStepsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject

data class HealthUiState(
    val isLoading: Boolean = false,
    val records: List<HealthRecord> = emptyList(),
    val errorMessage: String? = null,
    val healthConnectStatus: HealthConnectStatus? = null,
    val permissionStatus: PermissionStatus = PermissionStatus.NotRequested,
)

@HiltViewModel
class HealthViewModel @Inject constructor(
    private val readStepsUseCase: ReadStepsUseCase,
    private val checkHealthConnectStatusUseCase: CheckHealthConnectStatusUseCase,
) : ViewModel() {

    companion object {
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
        )
    }

    private var loadStepsJob: Job? = null
    private val _uiState = MutableStateFlow(HealthUiState())
    val uiState: StateFlow<HealthUiState> = _uiState.asStateFlow()

    init {
        checkAndLoad()
    }

    fun checkAndLoad() {
        val status = checkHealthConnectStatusUseCase()
        Timber.d("Health Connect status: %s", status)
        _uiState.value = _uiState.value.copy(healthConnectStatus = status)

        if (status != HealthConnectStatus.Available) {
            _uiState.value = _uiState.value.copy(isLoading = false)
            return
        }

        if (_uiState.value.permissionStatus == PermissionStatus.Granted) {
            loadSteps()
        }
    }

    fun onPermissionsResult(granted: Set<String>) {
        Timber.d("Permissions result: %s", granted)
        if (granted.containsAll(REQUIRED_PERMISSIONS)) {
            _uiState.value = _uiState.value.copy(permissionStatus = PermissionStatus.Granted)
            loadSteps()
        } else {
            _uiState.value = _uiState.value.copy(permissionStatus = PermissionStatus.Denied)
        }
    }

    fun loadSteps() {
        loadStepsJob?.cancel()
        loadStepsJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            Timber.d("Loading steps")
            try {
                val records = readStepsUseCase()
                Timber.d("Steps loaded: %d records", records.size)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    records = records,
                )
            } catch (e: TimeoutCancellationException) {
                Timber.e(e, "Failed to load steps")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Request timed out. Please try again.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to load steps")
                val message = when (e) {
                    is SecurityException -> "Permission denied"
                    is java.io.IOException -> "Service temporarily unavailable"
                    else -> "Failed to load steps"
                }
                val permissionReset = if (e is SecurityException) {
                    PermissionStatus.Denied
                } else {
                    _uiState.value.permissionStatus
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = message,
                    permissionStatus = permissionReset,
                )
            }
        }
    }
}
