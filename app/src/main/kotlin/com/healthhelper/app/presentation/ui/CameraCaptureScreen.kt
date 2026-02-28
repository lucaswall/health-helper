package com.healthhelper.app.presentation.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthhelper.app.presentation.viewmodel.CameraCaptureViewModel
import timber.log.Timber
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraCaptureScreen(
    onNavigateToConfirmation: (Int, Int) -> Unit,
    onNavigateBack: () -> Unit,
    sharedImageUri: String? = null,
    viewModel: CameraCaptureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var photoUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) {
        viewModel.navigateToConfirmation.collect { (systolic, diastolic) ->
            onNavigateToConfirmation(systolic, diastolic)
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = photoUri
        try {
            if (success && uri != null) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    viewModel.onPhotoCaptured(bytes)
                } else {
                    viewModel.onCaptureError("Could not read captured photo.")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read captured photo")
            viewModel.onCaptureError("Could not read captured photo.")
        } finally {
            // Clean up temp file — runs even if user cancels camera
            uri?.path?.let { File(it).delete() }
        }
    }

    // Handle shared image URI — go straight to processing
    LaunchedEffect(sharedImageUri) {
        if (sharedImageUri != null) {
            try {
                val uri = Uri.parse(sharedImageUri)
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    viewModel.onPhotoCaptured(bytes)
                } else {
                    viewModel.onCaptureError("Could not read shared image.")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to read shared image")
                viewModel.onCaptureError("Could not read shared image.")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Blood Pressure") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                uiState.isProcessing -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.size(16.dp))
                        Text(
                            text = "Analyzing...",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                uiState.error != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(modifier = Modifier.size(16.dp))
                        Button(onClick = viewModel::onRetake) {
                            Text("Try Again")
                        }
                    }
                }
                else -> {
                    Button(
                        onClick = {
                            try {
                                val imageDir = File(context.cacheDir, "bp_images")
                                imageDir.mkdirs()
                                val tempFile = File.createTempFile("bp_", ".jpg", imageDir)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    tempFile,
                                )
                                photoUri = uri
                                takePictureLauncher.launch(uri)
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to launch camera")
                                viewModel.onCaptureError("Could not open camera.")
                            }
                        },
                    ) {
                        Text("Take Photo")
                    }
                }
            }
        }
    }
}
