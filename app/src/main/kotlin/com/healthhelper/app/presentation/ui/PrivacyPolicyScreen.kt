package com.healthhelper.app.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Privacy policy / permissions rationale content shown by Health Connect
 * (ACTION_SHOW_PERMISSIONS_RATIONALE on Android 13 and lower, VIEW_PERMISSION_USAGE on 14+).
 */
object PrivacyPolicyContent {
    const val TITLE = "Privacy policy"

    data class Section(val heading: String, val body: String)

    val sections: List<Section> = listOf(
        Section(
            heading = "Health Connect data we read",
            body = "Blood pressure, blood glucose and hydration records, including history older than " +
                "30 days and reads while the app is in the background, so they can be synced to your " +
                "Food Scanner server.",
        ),
        Section(
            heading = "Health Connect data we write",
            body = "Nutrition records for meals logged in your Food Scanner account, and blood pressure " +
                "(with heart rate) and blood glucose readings you enter or scan in this app.",
        ),
        Section(
            heading = "Where your data goes",
            body = "Health readings are sent only to the Food Scanner server URL you configure in " +
                "Settings. Photos you take or share of a blood pressure monitor or glucose meter are " +
                "sent to Anthropic's API, using your own API key, to read the values on the display. " +
                "No health data is sold or used for advertising.",
        ),
        Section(
            heading = "Crash reports",
            body = "Crash and error reports are sent to Sentry to help fix bugs. They contain technical " +
                "diagnostics, not your health readings.",
        ),
        Section(
            heading = "Your control",
            body = "You can revoke any permission at any time in Health Connect settings. Revoking a " +
                "permission stops the related sync; data already sent to your server stays there.",
        ),
    )
}

@Composable
fun PrivacyPolicyScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = PrivacyPolicyContent.TITLE,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            PrivacyPolicyContent.sections.forEach { section ->
                Text(
                    text = section.heading,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = section.body,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
