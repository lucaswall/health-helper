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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthhelper.app.presentation.viewmodel.CameraCaptureViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream

private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024 // 20MB

private fun readBytesLimited(inputStream: InputStream, maxSize: Int): ByteArray {
    val buffer = ByteArray(8192)
    var totalRead = 0
    val output = java.io.ByteArrayOutputStream()
    while (true) {
        val bytesRead = inputStream.read(buffer)
        if (bytesRead == -1) break
        totalRead += bytesRead
        if (totalRead > maxSize) {
            throw IllegalArgumentException("Image exceeds maximum size of ${maxSize / (1024 * 1024)}MB")
        }
        output.write(buffer, 0, bytesRead)
    }
    return output.toByteArray()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraCaptureScreen(
    onNavigateToConfirmation: (Int, Int) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateBackWithError: (String) -> Unit = {},
    sharedImageUri: String? = null,
    viewModel: CameraCaptureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tempFilePath by viewModel.tempFilePath.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraLaunched by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.navigateToConfirmation.collect { (systolic, diastolic) ->
            onNavigateToConfirmation(systolic, diastolic)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navigateBackWithError.collect { error ->
            onNavigateBackWithError(error)
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        scope.launch {
            val file = tempFilePath?.let { File(it) }
            try {
                if (success) {
                    if (file == null) {
                        viewModel.onCaptureError("Could not read captured photo.")
                        return@launch
                    }
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            ),
                        )?.use { readBytesLimited(it, MAX_IMAGE_BYTES) }
                    }
                    if (bytes != null) {
                        viewModel.onPhotoCaptured(bytes)
                    } else {
                        viewModel.onCaptureError("Could not read captured photo.")
                    }
                } else {
                    onNavigateBack()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "Image too large")
                viewModel.onCaptureError("Image is too large. Please choose a smaller photo.")
            } catch (e: Exception) {
                Timber.e(e, "Failed to read captured photo")
                viewModel.onCaptureError("Could not read captured photo.")
            } finally {
                withContext(Dispatchers.IO) { file?.delete() }
                viewModel.clearTempFilePath()
            }
        }
    }

    // Handle shared image URI — go straight to processing
    LaunchedEffect(sharedImageUri) {
        if (sharedImageUri != null) {
            try {
                val uri = Uri.parse(sharedImageUri)
                if (uri.scheme != "content") {
                    viewModel.onCaptureError("Unsupported image source.")
                    return@LaunchedEffect
                }
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        readBytesLimited(it, MAX_IMAGE_BYTES)
                    }
                }
                if (bytes != null) {
                    viewModel.onPhotoCaptured(bytes)
                } else {
                    viewModel.onCaptureError("Could not read shared image.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "Shared image too large")
                viewModel.onCaptureError("Image is too large. Please choose a smaller photo.")
            } catch (e: Exception) {
                Timber.e(e, "Failed to read shared image")
                viewModel.onCaptureError("Could not read shared image.")
            }
        }
    }

    // Launch camera immediately if not a share intent
    LaunchedEffect(Unit) {
        if (sharedImageUri == null && !cameraLaunched) {
            cameraLaunched = true
            try {
                val imageDir = File(context.cacheDir, "bp_images")
                imageDir.mkdirs()
                val file = File.createTempFile("bp_", ".jpg", imageDir)
                viewModel.setTempFilePath(file.absolutePath)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                takePictureLauncher.launch(uri)
            } catch (e: Exception) {
                Timber.e(e, "Failed to launch camera")
                viewModel.clearTempFilePath()
                viewModel.onCaptureError("Could not open camera.")
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
            if (uiState.isProcessing) {
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
        }
    }
}
