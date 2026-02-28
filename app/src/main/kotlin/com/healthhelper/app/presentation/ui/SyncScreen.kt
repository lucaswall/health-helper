package com.healthhelper.app.presentation.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthhelper.app.presentation.viewmodel.SyncViewModel

private val NUTRITION_PERMISSION = HealthPermission.getWritePermission(NutritionRecord::class)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var permissionRequested by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { grantedPermissions ->
        viewModel.onPermissionResult(grantedPermissions.contains(NUTRITION_PERMISSION))
    }

    // Show permission message when HC is available but permission not yet granted
    LaunchedEffect(uiState.healthConnectAvailable, uiState.permissionGranted) {
        if (uiState.healthConnectAvailable && !uiState.permissionGranted) {
            permissionRequested = true
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!uiState.healthConnectAvailable) {
                Text(
                    text = "Health Connect is not available on this device.",
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!uiState.permissionGranted && permissionRequested) {
                Text(
                    text = "Write Nutrition permission is required to sync data.",
                    color = MaterialTheme.colorScheme.error,
                )
                Button(
                    onClick = { permissionLauncher.launch(setOf(NUTRITION_PERMISSION)) },
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

            Spacer(modifier = Modifier.height(8.dp))

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
}
