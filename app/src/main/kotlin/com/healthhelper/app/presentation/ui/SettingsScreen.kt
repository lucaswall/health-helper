package com.healthhelper.app.presentation.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthhelper.app.presentation.viewmodel.MAX_SYNC_INTERVAL_MINUTES
import com.healthhelper.app.presentation.viewmodel.MIN_SYNC_INTERVAL_MINUTES
import com.healthhelper.app.presentation.viewmodel.SettingsViewModel
import kotlin.math.roundToInt

// Password keyboard with no autocorrect/suggestions so IMEs don't learn the secret.
private val SecretKeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
    imeAction = ImeAction.Next,
)

private val UrlKeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri,
    imeAction = ImeAction.Next,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var apiKeyVisible by remember { mutableStateOf(false) }
    var anthropicApiKeyVisible by remember { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var exitAfterSave by rememberSaveable { mutableStateOf(false) }

    SecureWindowEffect()

    val requestBack = {
        if (uiState.hasUnsavedChanges) showDiscardDialog = true else onNavigateBack()
    }
    BackHandler(enabled = uiState.hasUnsavedChanges, onBack = requestBack)

    LaunchedEffect(exitAfterSave, uiState) {
        if (!exitAfterSave) return@LaunchedEffect
        when {
            uiState.saveError != null || uiState.baseUrlError != null -> exitAfterSave = false
            !uiState.hasUnsavedChanges -> {
                exitAfterSave = false
                onNavigateBack()
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved changes to your settings.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        exitAfterSave = true
                        viewModel.save()
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDiscardDialog = false }) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = {
                            showDiscardDialog = false
                            viewModel.reset()
                            onNavigateBack()
                        },
                    ) {
                        Text("Discard")
                    }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back",
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
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = uiState.baseUrl,
                onValueChange = viewModel::updateBaseUrl,
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.isLoaded,
                singleLine = true,
                isError = uiState.baseUrlError != null,
                supportingText = uiState.baseUrlError?.let { error -> { Text(error) } },
                keyboardOptions = UrlKeyboardOptions,
            )

            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = viewModel::updateApiKey,
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.isLoaded,
                singleLine = true,
                keyboardOptions = SecretKeyboardOptions,
                visualTransformation = if (apiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Icon(
                            imageVector = if (apiKeyVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (apiKeyVisible) "Hide API key" else "Show API key",
                        )
                    }
                },
            )

            OutlinedTextField(
                value = uiState.anthropicApiKey,
                onValueChange = viewModel::updateAnthropicApiKey,
                label = { Text("Anthropic API Key") },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.isLoaded,
                singleLine = true,
                keyboardOptions = SecretKeyboardOptions,
                visualTransformation = if (anthropicApiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { anthropicApiKeyVisible = !anthropicApiKeyVisible }) {
                        Icon(
                            imageVector = if (anthropicApiKeyVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (anthropicApiKeyVisible) "Hide Anthropic API key" else "Show Anthropic API key",
                        )
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Sync interval: ${uiState.syncInterval} min",
                    modifier = Modifier.weight(1f),
                )
            }
            Slider(
                value = uiState.syncInterval.toFloat(),
                onValueChange = { viewModel.updateSyncInterval(it.roundToInt()) },
                valueRange = MIN_SYNC_INTERVAL_MINUTES.toFloat()..MAX_SYNC_INTERVAL_MINUTES.toFloat(),
                steps = 20,
                enabled = uiState.isLoaded,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Sync interval"
                        stateDescription = "${uiState.syncInterval} minutes"
                    },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.reset() },
                    enabled = uiState.isLoaded && uiState.hasUnsavedChanges,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Reset")
                }
                Button(
                    onClick = { viewModel.save() },
                    enabled = uiState.isLoaded && uiState.hasUnsavedChanges,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save")
                }
            }
            uiState.saveError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Keeps secrets out of screenshots and the Recents thumbnail while Settings is shown. */
@Composable
private fun SecureWindowEffect() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
