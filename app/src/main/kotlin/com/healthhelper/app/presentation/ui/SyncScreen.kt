package com.healthhelper.app.presentation.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthhelper.app.presentation.viewmodel.SyncViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToCamera: () -> Unit = {},
    snackbarMessage: String? = null,
    onSnackbarShown: () -> Unit = {},
    bpScanError: String? = null,
    onBpScanErrorShown: () -> Unit = {},
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage != null) {
            snackbarHostState.showSnackbar(snackbarMessage)
            onSnackbarShown()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { grantedPermissions ->
        viewModel.onPermissionResult(grantedPermissions.containsAll(SyncViewModel.REQUIRED_HC_PERMISSIONS))
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onCameraPermissionResult(granted)
    }

    // Request camera permission independently of Health Connect
    LaunchedEffect(Unit) {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Request HC permissions when available
    LaunchedEffect(uiState.healthConnectAvailable) {
        if (uiState.healthConnectAvailable && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(SyncViewModel.REQUIRED_HC_PERMISSIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HealthHelper Sync") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Section 1: Nutrition Sync
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Nutrition Sync",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    if (!uiState.healthConnectAvailable) {
                        Text(
                            text = "Health Connect is not available on this device.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (!uiState.permissionGranted) {
                        Text(
                            text = "Health Connect permissions are required to sync data.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = { permissionLauncher.launch(SyncViewModel.REQUIRED_HC_PERMISSIONS) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Grant Permission")
                        }
                    }

                    if (!uiState.isConfigured) {
                        Text("Please configure API settings to enable sync.")
                    }

                    if (uiState.lastSyncTime.isNotEmpty()) {
                        Text("Last synced: ${uiState.lastSyncTime}")
                    } else if (uiState.lastSyncedDate.isNotEmpty()) {
                        Text("Last synced: ${uiState.lastSyncedDate}")
                    }

                    if (uiState.nextSyncTime.isNotEmpty()) {
                        Text(
                            text = uiState.nextSyncTime,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    uiState.lastSyncResult?.let { result ->
                        Text("Last result: $result")
                    }

                    if (uiState.lastSyncedMeals.isNotEmpty()) {
                        Text(
                            text = "Recent syncs:",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        uiState.lastSyncedMeals.forEach { meal ->
                            Text(
                                text = "${meal.foodName} · ${meal.mealType.name.lowercase().replaceFirstChar { it.uppercase() }} · ${meal.calories} cal",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (uiState.isSyncing) {
                        val progress = uiState.syncProgress
                        if (progress != null && progress.totalDays > 0) {
                            val fraction = progress.completedDays.toFloat() / progress.totalDays.toFloat()
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("Syncing ${progress.currentDate} (${progress.completedDays}/${progress.totalDays})")
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("Syncing...")
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = viewModel::triggerSync,
                        enabled = !uiState.isSyncing && uiState.isConfigured
                            && uiState.healthConnectAvailable && uiState.permissionGranted,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (uiState.isSyncing) "Syncing..." else "Sync Now")
                    }
                }
            }

            // Section 2: Blood Pressure
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Blood Pressure",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    if (bpScanError != null) {
                        Text(
                            text = bpScanError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (uiState.lastBpReadingDisplay.isNotEmpty()) {
                        val timeLabel = if (uiState.lastBpReadingTime.isNotEmpty()) " · ${uiState.lastBpReadingTime}" else ""
                        Text(
                            text = "${uiState.lastBpReadingDisplay}$timeLabel",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            text = "No readings yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Button(
                        onClick = {
                            onBpScanErrorShown()
                            viewModel.refreshLastBpReading()
                            onNavigateToCamera()
                        },
                        enabled = uiState.permissionGranted && uiState.cameraPermissionGranted,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Log Blood Pressure")
                    }

                    if (!uiState.cameraPermissionGranted) {
                        Text(
                            text = "Camera permission required to scan blood pressure readings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
