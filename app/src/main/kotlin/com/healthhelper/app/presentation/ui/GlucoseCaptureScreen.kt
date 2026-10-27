package com.healthhelper.app.presentation.ui

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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthhelper.app.domain.model.GlucoseUnit
import com.healthhelper.app.presentation.image.ImageTooLargeException
import com.healthhelper.app.presentation.image.MAX_IMAGE_BYTES
import com.healthhelper.app.presentation.image.readBytesLimited
import com.healthhelper.app.presentation.viewmodel.GlucoseCaptureViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlucoseCaptureScreen(
    onNavigateToConfirmation: (Double, GlucoseUnit) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateBackWithError: (String) -> Unit = {},
    sharedImagePath: String? = null,
    viewModel: GlucoseCaptureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tempFilePath by viewModel.tempFilePath.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraLaunched by rememberSaveable { mutableStateOf(false) }
    // One-shot guard: the nav arg survives rotation/back-stack restore, but the file is deleted after the first read.
    var sharedImageConsumed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.navigateToConfirmation.collect { (value, unit) ->
            onNavigateToConfirmation(value, unit)
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
            } catch (e: ImageTooLargeException) {
                Timber.e(e, "Image too large")
                viewModel.onCaptureError("Image is too large. Please choose a smaller photo.")
            } catch (e: Exception) {
                Timber.e(e, "Failed to read captured photo")
                viewModel.onCaptureError("Could not read captured photo.")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { file?.delete() }
                viewModel.clearTempFilePath()
            }
        }
    }

    // Handle shared image — go straight to processing
    LaunchedEffect(sharedImagePath) {
        if (sharedImagePath != null && !sharedImageConsumed) {
            sharedImageConsumed = true
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val file = File(sharedImagePath)
                    file.inputStream().use { readBytesLimited(it, MAX_IMAGE_BYTES) }
                }
                viewModel.onPhotoCaptured(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImageTooLargeException) {
                Timber.e(e, "Shared image too large")
                viewModel.onCaptureError("Image is too large. Please choose a smaller photo.")
            } catch (e: Exception) {
                Timber.e(e, "Failed to read shared image")
                viewModel.onCaptureError("Could not read shared image.")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { File(sharedImagePath).delete() }
            }
        }
    }

    // Launch camera immediately if not a share intent
    LaunchedEffect(Unit) {
        if (sharedImagePath == null && !cameraLaunched) {
            cameraLaunched = true
            try {
                val imageDir = File(context.cacheDir, "glucose_images")
                imageDir.mkdirs()
                val file = File.createTempFile("glucose_", ".jpg", imageDir)
                viewModel.setTempFilePath(file.absolutePath)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                takePictureLauncher.launch(uri)
            } catch (e: Exception) {
                Timber.e(e, "Failed to launch camera")
                withContext(NonCancellable + Dispatchers.IO) { viewModel.tempFilePath.value?.let { File(it).delete() } }
                viewModel.clearTempFilePath()
                viewModel.onCaptureError("Could not open camera.")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Glucose") },
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
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
