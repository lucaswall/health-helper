package com.healthhelper.app.presentation.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.healthhelper.app.presentation.image.ImageTooLargeException
import com.healthhelper.app.presentation.image.MAX_IMAGE_BYTES
import com.healthhelper.app.presentation.image.readBytesLimited
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Remembers a function that opens the system camera (no CAMERA permission needed) and hands the
 * photo's bytes to [onPhoto]. The temp file lives in `cacheDir/[cacheSubDir]` (must be listed in
 * file_paths.xml) and is always deleted once read. Cancelling the camera calls neither callback.
 */
@Composable
fun rememberPhotoCapture(
    cacheSubDir: String,
    onPhoto: (ByteArray) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saveable so the path survives the Activity being recreated while the camera app is open.
    var tempFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val currentOnPhoto by rememberUpdatedState(onPhoto)
    val currentOnError by rememberUpdatedState(onError)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = tempFilePath?.let { File(it) }
        tempFilePath = null
        scope.launch {
            try {
                if (!success) return@launch
                if (file == null) {
                    currentOnError("Could not read captured photo.")
                    return@launch
                }
                val bytes = withContext(Dispatchers.IO) {
                    file.inputStream().use { readBytesLimited(it, MAX_IMAGE_BYTES) }
                }
                currentOnPhoto(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImageTooLargeException) {
                Timber.e(e, "Captured image too large")
                currentOnError("Image is too large. Please choose a smaller photo.")
            } catch (e: Exception) {
                Timber.e(e, "Failed to read captured photo")
                currentOnError("Could not read captured photo.")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { file?.delete() }
            }
        }
    }

    return remember(launcher, context, cacheSubDir) {
        {
            var file: File? = null
            try {
                val dir = File(context.cacheDir, cacheSubDir).apply { mkdirs() }
                val created = File.createTempFile("capture_", ".jpg", dir)
                file = created
                tempFilePath = created.absolutePath
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    created,
                )
                launcher.launch(uri)
            } catch (e: Exception) {
                Timber.e(e, "Failed to launch camera")
                tempFilePath = null
                file?.let { stale -> scope.launch(NonCancellable + Dispatchers.IO) { stale.delete() } }
                currentOnError("Could not open camera.")
            }
        }
    }
}

/**
 * Compact "Scan from photo" action for the entry screens, plus the scan status line beneath it
 * (progress, the "check the values" hint after a fill, or the scan error).
 */
@Composable
fun ScanFromPhotoSection(
    isScanning: Boolean,
    filledFromPhoto: Boolean,
    scanError: String?,
    enabled: Boolean,
    onScan: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilledTonalButton(
            onClick = onScan,
            enabled = enabled && !isScanning,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
            }
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(if (isScanning) "Reading photo…" else "Scan from photo")
        }
        when {
            scanError != null -> Text(
                text = scanError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            filledFromPhoto -> Text(
                text = "Filled from photo — check the values before saving.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
