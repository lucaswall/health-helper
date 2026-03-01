package com.healthhelper.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.healthhelper.app.presentation.ui.AppNavigation
import com.healthhelper.app.presentation.ui.theme.HealthHelperTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only process share intent on fresh launch, not on recreation (config change / process restore)
        val shareInfo = if (savedInstanceState == null) extractShareInfo(intent) else null

        setContent {
            HealthHelperTheme {
                AppNavigation(
                    sharedImagePath = shareInfo?.second,
                    shareTarget = shareInfo?.first,
                )
            }
        }
    }

    /**
     * Extract shared image from intent, copy to local temp file (while URI permission is active),
     * and determine share target based on which activity-alias was triggered.
     * Returns (shareTarget, filePath) or null if not a share intent.
     */
    private fun extractShareInfo(intent: Intent): Pair<String, String>? {
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

        // Copy to local temp file immediately while content URI permission is active
        return try {
            val dir = File(cacheDir, "shared_images")
            dir.mkdirs()
            val file = File.createTempFile("shared_", ".jpg", dir)
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Pair(shareTarget, file.absolutePath)
        } catch (e: Exception) {
            Timber.e(e, "Failed to copy shared image to local file")
            null
        }
    }
}
