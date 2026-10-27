package com.healthhelper.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.healthhelper.app.presentation.ui.PrivacyPolicyScreen
import com.healthhelper.app.presentation.ui.theme.HealthHelperTheme

/**
 * Shown by Health Connect when the user asks why the app needs its permissions:
 * - `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (Android 13 and lower; required or the
 *   Health Connect permission request finishes immediately without granting anything)
 * - `VIEW_PERMISSION_USAGE` + `HEALTH_PERMISSIONS` via the ViewPermissionUsageActivity alias (Android 14+)
 */
class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HealthHelperTheme {
                PrivacyPolicyScreen()
            }
        }
    }
}
