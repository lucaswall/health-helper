package com.healthhelper.app.presentation.viewmodel

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.domain.model.HealthConnectStatus
import com.healthhelper.app.domain.model.HealthRecord
import com.healthhelper.app.domain.model.PermissionStatus
import com.healthhelper.app.domain.model.StepsErrorType
import com.healthhelper.app.domain.model.StepsResult
import com.healthhelper.app.domain.usecase.CheckHealthConnectStatusUseCase
import com.healthhelper.app.domain.usecase.ReadStepsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class HealthUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
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
        doLoadSteps(isRefresh = false)
    }

    fun refreshSteps() {
        doLoadSteps(isRefresh = true)
    }

    private fun doLoadSteps(isRefresh: Boolean) {
        loadStepsJob?.cancel()
        loadStepsJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = !isRefresh,
                isRefreshing = isRefresh,
                errorMessage = null,
            )
            Timber.d(if (isRefresh) "Refreshing steps" else "Loading steps")
            when (val result = readStepsUseCase()) {
                is StepsResult.Success -> {
                    Timber.d("Steps loaded: %d records", result.records.size)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        records = result.records,
                    )
                }
                is StepsResult.Error -> {
                    Timber.e("Failed to load steps: %s", result.message)
                    val permissionReset = if (result.type == StepsErrorType.PermissionDenied) {
                        PermissionStatus.Denied
                    } else {
                        _uiState.value.permissionStatus
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = result.message,
                        permissionStatus = permissionReset,
                    )
                }
            }
        }
    }
}
