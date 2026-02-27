package com.healthhelper.app.presentation.ui

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthhelper.app.presentation.viewmodel.SyncViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
            if (!uiState.isConfigured) {
                Text("Please configure API settings to enable sync.")
            }

            if (uiState.lastSyncedDate.isNotEmpty()) {
                Text("Last synced: ${uiState.lastSyncedDate}")
            }

            uiState.lastSyncResult?.let { result ->
                Text("Last result: $result")
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
                enabled = !uiState.isSyncing && uiState.isConfigured,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (uiState.isSyncing) "Syncing..." else "Sync Now")
            }
        }
    }
}
