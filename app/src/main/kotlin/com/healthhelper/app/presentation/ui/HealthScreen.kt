package com.healthhelper.app.presentation.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthhelper.app.domain.model.HealthConnectStatus
import com.healthhelper.app.domain.model.HealthRecord
import com.healthhelper.app.domain.model.PermissionStatus
import com.healthhelper.app.presentation.viewmodel.HealthViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val REQUIRED_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(
    viewModel: HealthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        viewModel.onPermissionsResult(granted)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkAndLoad()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Health Helper") })
        },
    ) { padding ->
        when {
            // Initial state — status not yet checked
            uiState.healthConnectStatus == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            // Availability states (highest priority)
            uiState.healthConnectStatus == HealthConnectStatus.NotInstalled -> {
                CenteredMessage(
                    modifier = Modifier.padding(padding),
                    title = "Health Connect Not Installed",
                    message = "This app requires Health Connect to track your health data. Please install it from the Play Store.",
                ) {
                    Button(onClick = { openHealthConnectPlayStore(context) }) {
                        Text("Install Health Connect")
                    }
                }
            }
            uiState.healthConnectStatus == HealthConnectStatus.NeedsUpdate -> {
                CenteredMessage(
                    modifier = Modifier.padding(padding),
                    title = "Health Connect Needs Update",
                    message = "Please update Health Connect to the latest version.",
                ) {
                    Button(onClick = { openHealthConnectPlayStore(context) }) {
                        Text("Update Health Connect")
                    }
                }
            }
            // Permission states
            uiState.permissionStatus == PermissionStatus.NotRequested -> {
                CenteredMessage(
                    modifier = Modifier.padding(padding),
                    title = "Permission Required",
                    message = "Health Helper needs access to your step data from Health Connect to display your activity.",
                ) {
                    Button(onClick = {
                        permissionLauncher.launch(REQUIRED_PERMISSIONS)
                    }) {
                        Text("Grant Permissions")
                    }
                }
            }
            uiState.permissionStatus == PermissionStatus.Denied -> {
                CenteredMessage(
                    modifier = Modifier.padding(padding),
                    title = "Permission Denied",
                    message = "Health Helper cannot read your step data without permission. You can grant access in Health Connect settings.",
                ) {
                    Button(onClick = {
                        permissionLauncher.launch(REQUIRED_PERMISSIONS)
                    }) {
                        Text("Try Again")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        val intent = Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS")
                            .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
                        context.startActivity(intent)
                    }) {
                        Text("Open Settings")
                    }
                }
            }
            // Loading state
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            // Error state
            uiState.errorMessage != null -> {
                CenteredMessage(
                    modifier = Modifier.padding(padding),
                    title = "Something Went Wrong",
                    message = uiState.errorMessage!!,
                ) {
                    Button(onClick = { viewModel.loadSteps() }) {
                        Text("Retry")
                    }
                }
            }
            // Empty state
            uiState.records.isEmpty() -> {
                PullToRefreshBox(
                    isRefreshing = false,
                    onRefresh = { viewModel.loadSteps() },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    CenteredMessage(
                        title = "No Step Records Found",
                        message = "Walk around with your phone to track steps, or check that Health Connect is tracking your activity.",
                    )
                }
            }
            // Data state
            else -> {
                PullToRefreshBox(
                    isRefreshing = uiState.isLoading,
                    onRefresh = { viewModel.loadSteps() },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            StepsSummaryCard(records = uiState.records)
                        }
                        items(uiState.records, key = { it.id }) { record ->
                            StepRecordCard(record = record)
                        }
                    }
                }
            }
        }
    }
}

private fun openHealthConnectPlayStore(context: android.content.Context) {
    val marketIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=com.google.android.apps.healthdata"),
    )
    try {
        context.startActivity(marketIntent)
    } catch (_: ActivityNotFoundException) {
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"),
        )
        context.startActivity(webIntent)
    }
}

@Composable
private fun CenteredMessage(
    modifier: Modifier = Modifier,
    title: String,
    message: String,
    actions: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actions != null) {
                Spacer(modifier = Modifier.height(24.dp))
                actions()
            }
        }
    }
}

@Composable
private fun StepsSummaryCard(records: List<HealthRecord>) {
    val totalSteps = records.sumOf { it.value.toLong() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "%,d".format(totalSteps),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "Total Steps",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "Last 7 Days",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")

@Composable
private fun StepRecordCard(record: HealthRecord) {
    val zone = ZoneId.systemDefault()
    val startLocal = record.startTime.atZone(zone)
    val endLocal = record.endTime.atZone(zone)

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = startLocal.format(dateFormatter),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${startLocal.format(timeFormatter)} - ${endLocal.format(timeFormatter)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "%,.0f".format(record.value),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
