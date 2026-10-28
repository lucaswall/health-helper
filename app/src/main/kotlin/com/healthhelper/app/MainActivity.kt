package com.healthhelper.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.healthhelper.app.presentation.image.CAPTURE_IMAGE_CACHE_DIRS
import com.healthhelper.app.presentation.image.ImageTooLargeException
import com.healthhelper.app.presentation.image.MAX_IMAGE_BYTES
import com.healthhelper.app.presentation.image.SHARED_IMAGES_DIR
import com.healthhelper.app.presentation.image.copyLimited
import com.healthhelper.app.presentation.image.isAllowedSharedImageUri
import com.healthhelper.app.presentation.image.sweepStaleFiles
import com.healthhelper.app.presentation.ui.AppNavigation
import com.healthhelper.app.presentation.ui.theme.HealthHelperTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private data class ShareRequest(val target: String, val uri: Uri)
    private data class SharedImage(val target: String, val path: String)

    private var sharedImage by mutableStateOf<SharedImage?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only process share intent on fresh launch, not on recreation (config change / process restore)
        if (savedInstanceState == null) {
            sweepStaleCaptureFiles()
            parseShareRequest(intent)?.let { request ->
                // Copy off the main thread while this Activity (and thus the URI read grant) is alive.
                lifecycleScope.launch {
                    val path = withContext(Dispatchers.IO) { copySharedImage(request.uri) }
                    if (path != null) {
                        sharedImage = SharedImage(request.target, path)
                    }
                }
            }
        }

        setContent {
            HealthHelperTheme {
                AppNavigation(
                    sharedImagePath = sharedImage?.path,
                    shareTarget = sharedImage?.target,
                )
            }
        }
    }

    /**
     * Determines whether [intent] is an image share we accept and which flow it targets.
     * Returns null if it is not a share intent or the URI is not an acceptable content:// URI.
     */
    private fun parseShareRequest(intent: Intent): ShareRequest? {
        if (intent.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("image/") != true) return null

        val shareTarget = when (componentName?.shortClassName) {
            ".ShareGlucoseActivity" -> "glucose"
            else -> "bp"
        }

        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        if (uri == null) return null
        if (!isAllowedSharedImageUri(uri.scheme, uri.authority, packageName)) {
            Timber.w("Rejected shared image URI with scheme=%s", uri.scheme)
            return null
        }
        return ShareRequest(shareTarget, uri)
    }

    /**
     * Copies the shared image to a local temp file, bounded by [MAX_IMAGE_BYTES].
     * Returns the file path, or null (and deletes any partial file) on failure. Must run off the main thread.
     */
    private fun copySharedImage(uri: Uri): String? {
        var file: File? = null
        return try {
            val type = contentResolver.getType(uri)
            if (type != null && !type.startsWith("image/")) {
                Timber.w("Rejected shared content with non-image type %s", type)
                return null
            }
            val dir = File(cacheDir, SHARED_IMAGES_DIR)
            dir.mkdirs()
            val tempFile = File.createTempFile("shared_", ".jpg", dir)
            file = tempFile
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                Timber.w("Shared image URI returned no stream")
                tempFile.delete()
                return null
            }
            input.use { stream ->
                tempFile.outputStream().use { output ->
                    copyLimited(stream, output, MAX_IMAGE_BYTES)
                }
            }
            tempFile.absolutePath
        } catch (e: CancellationException) {
            file?.delete()
            throw e
        } catch (e: ImageTooLargeException) {
            Timber.w(e, "Shared image too large")
            file?.delete()
            null
        } catch (e: Exception) {
            Timber.e(e, "Failed to copy shared image to local file")
            file?.delete()
            null
        }
    }

    /** Removes capture photos abandoned by process death (only files older than an hour). */
    private fun sweepStaleCaptureFiles() {
        val dirs = CAPTURE_IMAGE_CACHE_DIRS.map { File(cacheDir, it) }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val deleted = sweepStaleFiles(dirs, System.currentTimeMillis())
                if (deleted > 0) Timber.d("Swept %d stale capture image(s)", deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to sweep stale capture images")
            }
        }
    }
}
