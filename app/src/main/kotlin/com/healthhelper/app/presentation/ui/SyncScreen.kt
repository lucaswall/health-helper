package com.healthhelper.app.presentation.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthhelper.app.domain.model.HealthPermissions
import com.healthhelper.app.presentation.viewmodel.SYNC_STATUS_NEVER_SYNCED
import com.healthhelper.app.presentation.viewmodel.SyncViewModel
import timber.log.Timber

private fun humanReadablePermissionLabel(permission: String): String = when (permission) {
    "android.permission.health.READ_BLOOD_GLUCOSE" -> "Read blood glucose"
    "android.permission.health.WRITE_BLOOD_GLUCOSE" -> "Write blood glucose"
    "android.permission.health.READ_BLOOD_PRESSURE" -> "Read blood pressure"
    "android.permission.health.WRITE_BLOOD_PRESSURE" -> "Write blood pressure"
    "android.permission.health.READ_HYDRATION" -> "Read hydration"
    "android.permission.health.WRITE_NUTRITION" -> "Write nutrition"
    "android.permission.health.READ_HEALTH_DATA_HISTORY" -> "Read history > 30 days"
    "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" -> "Read health data in background"
    else -> permission.substringAfterLast('.')
}

private fun openHealthConnectSettings(context: android.content.Context) {
    val actions = listOf(
        HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS,
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    )
    for (action in actions) {
        val intent = Intent(action).apply {
            if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            return
        } catch (e: Exception) {
            Timber.w(e, "openHealthConnectSettings: failed for %s", action)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToCamera: () -> Unit = {},
    onNavigateToGlucoseCamera: () -> Unit = {},
    snackbarMessage: String? = null,
    onSnackbarShown: () -> Unit = {},
    bpScanError: String? = null,
    onBpScanErrorShown: () -> Unit = {},
    glucoseScanError: String? = null,
    onGlucoseScanErrorShown: () -> Unit = {},
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Tracks whether we've auto-launched the HC permission sheet for the current missing set.
    // rememberSaveable survives rotation; ON_RESUME refreshes permissions and, if still missing
    // after the user returns from HC, we do NOT auto-prompt again — the banner shows a manual CTA.
    var autoRequestedFor by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage != null) {
            snackbarHostState.showSnackbar(snackbarMessage)
            onSnackbarShown()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { grantedPermissions ->
        val stillMissing = SyncViewModel.REQUIRED_HC_PERMISSIONS - grantedPermissions
        viewModel.onPermissionResult(
            grantedAll = stillMissing.isEmpty(),
            stillMissing = stillMissing,
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onCameraPermissionResult(granted)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Timber.d("POST_NOTIFICATIONS granted=%b", granted)
    }

    LaunchedEffect(Unit) {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Refresh permissions on every resume — the user may have changed grants in HC settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-launch the permission sheet the first time we see a given missing set.
    // If the user returns with the same missing set, we stop auto-prompting to avoid
    // pestering them — the banner still offers a manual Grant button and HC-settings fallback.
    LaunchedEffect(uiState.healthConnectAvailable, uiState.missingHealthPermissions) {
        if (!uiState.healthConnectAvailable) return@LaunchedEffect
        val missing = uiState.missingHealthPermissions
        if (missing.isNotEmpty() && autoRequestedFor != missing) {
            autoRequestedFor = missing
            permissionLauncher.launch(missing)
        } else if (missing.isEmpty()) {
            autoRequestedFor = emptySet()
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
                    } else if (uiState.missingHealthPermissions.isNotEmpty()) {
                        val missingLabels = uiState.missingHealthPermissions
                            .map { humanReadablePermissionLabel(it) }
                            .sorted()
                        Text(
                            text = "Health Connect permissions needed:",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        missingLabels.forEach { label ->
                            Text(
                                text = "• $label",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = {
                                permissionLauncher.launch(uiState.missingHealthPermissions)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Grant in Health Connect")
                        }
                        if (uiState.permissionLockoutSuspected) {
                            Text(
                                text = "Permission sheet didn't appear? Open Health Connect settings directly.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = { openHealthConnectSettings(context) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Open Health Connect settings")
                            }
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

                    if (uiState.isSyncing) {
                        OutlinedButton(
                            onClick = viewModel::cancelSync,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Cancel Sync")
                        }
                    } else {
                        Button(
                            onClick = viewModel::triggerSync,
                            enabled = uiState.isConfigured
                                && uiState.healthConnectAvailable && uiState.permissionGranted,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Sync Now")
                        }
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

                    if (uiState.bpSyncStatus.isNotEmpty()) {
                        Text(
                            text = uiState.bpSyncStatus,
                            style = MaterialTheme.typography.bodySmall,
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

            // Section 3: Blood Glucose
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Blood Glucose",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    if (glucoseScanError != null) {
                        Text(
                            text = glucoseScanError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (uiState.lastGlucoseReadingDisplay.isNotEmpty()) {
                        val timeLabel = if (uiState.lastGlucoseReadingTime.isNotEmpty()) " · ${uiState.lastGlucoseReadingTime}" else ""
                        Text(
                            text = "${uiState.lastGlucoseReadingDisplay}$timeLabel",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            text = "No readings yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (uiState.glucoseSyncStatus.isNotEmpty()) {
                        Text(
                            text = uiState.glucoseSyncStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Button(
                        onClick = {
                            onGlucoseScanErrorShown()
                            viewModel.refreshLastGlucoseReading()
                            onNavigateToGlucoseCamera()
                        },
                        enabled = uiState.permissionGranted && uiState.cameraPermissionGranted,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Log Glucose")
                    }

                    if (!uiState.cameraPermissionGranted) {
                        Text(
                            text = "Camera permission required to scan glucose readings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Section 4: Hydration
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Hydration",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    if (uiState.hydrationReadPermissionMissing) {
                        Text(
                            text = "Health Connect read permission denied for hydration.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = {
                                try {
                                    permissionLauncher.launch(setOf(HealthPermissions.READ_HYDRATION))
                                } catch (e: Exception) {
                                    openHealthConnectSettings(context)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Grant hydration access")
                        }
                    } else if (uiState.hydrationTodayDisplay.isNotEmpty()) {
                        Text(
                            text = "${uiState.hydrationTodayDisplay} today",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        val noSyncYet = uiState.hydrationSyncStatus.isEmpty() ||
                            uiState.hydrationSyncStatus == SYNC_STATUS_NEVER_SYNCED
                        Text(
                            text = if (noSyncYet) "Waiting for first sync\u2026" else "No water logged yet today",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (uiState.hydrationSyncStatus.isNotEmpty()) {
                        Text(
                            text = uiState.hydrationSyncStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (uiState.hydrationHistoryStatus.isNotEmpty()) {
                        Text(
                            text = uiState.hydrationHistoryStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
