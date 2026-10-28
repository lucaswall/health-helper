package com.healthhelper.app.presentation.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.healthhelper.app.presentation.viewmodel.BpEntryViewModel
import com.healthhelper.app.presentation.viewmodel.SyncViewModel

private const val ROUTE_SYNC = "sync"
private const val ARG_SHARED_PATH = BpEntryViewModel.ARG_SHARED_PATH
private const val ROUTE_BP_ENTRY_BASE = "bp-entry"
private const val ROUTE_GLUCOSE_ENTRY_BASE = "glucose-entry"
private const val ROUTE_BP_ENTRY = "$ROUTE_BP_ENTRY_BASE?$ARG_SHARED_PATH={$ARG_SHARED_PATH}"
private const val ROUTE_GLUCOSE_ENTRY = "$ROUTE_GLUCOSE_ENTRY_BASE?$ARG_SHARED_PATH={$ARG_SHARED_PATH}"

private const val KEY_SNACKBAR_MSG = "snackbar_msg"

/** Optional path of a shared image to scan as soon as the entry screen opens. */
private fun sharedPathArgument() = navArgument(ARG_SHARED_PATH) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

/** Pops only if [route] is the current destination, so double taps can never pop the start destination. */
private fun NavController.popIfCurrent(route: String) {
    if (currentBackStackEntry?.destination?.route == route) {
        popBackStack()
    }
}

/**
 * Returns to the existing sync entry (keeping its ViewModel, so an in-flight manual sync survives),
 * optionally handing it a message through its SavedStateHandle.
 */
private fun NavController.returnToSync(key: String? = null, message: String? = null) {
    val syncEntry = try {
        getBackStackEntry(ROUTE_SYNC)
    } catch (_: IllegalArgumentException) {
        null
    } ?: return
    if (key != null) {
        syncEntry.savedStateHandle[key] = message
    }
    popBackStack(ROUTE_SYNC, inclusive = false)
}

@Composable
fun AppNavigation(
    sharedImagePath: String? = null,
    shareTarget: String? = null,
) {
    val navController = rememberNavController()

    // If launched via share intent, open the matching entry screen, which scans the image right away.
    LaunchedEffect(sharedImagePath, shareTarget) {
        if (sharedImagePath != null && shareTarget != null) {
            val encodedPath = java.net.URLEncoder.encode(sharedImagePath, "UTF-8")
            val base = when (shareTarget) {
                "glucose" -> ROUTE_GLUCOSE_ENTRY_BASE
                else -> ROUTE_BP_ENTRY_BASE
            }
            val route = "$base?$ARG_SHARED_PATH=$encodedPath"
            navController.navigate(route) {
                popUpTo(ROUTE_SYNC) { inclusive = false }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = ROUTE_SYNC,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(ROUTE_SYNC) { backStackEntry ->
            val syncViewModel: SyncViewModel = hiltViewModel()
            val snackbarMessage = backStackEntry.savedStateHandle.get<String>(KEY_SNACKBAR_MSG)
            // The sync entry (and its ViewModel) now survives a capture flow, so refresh the
            // last-reading cards when a confirmation screen reports a saved reading.
            LaunchedEffect(snackbarMessage) {
                if (snackbarMessage != null) {
                    syncViewModel.refreshLastBpReading()
                    syncViewModel.refreshLastGlucoseReading()
                }
            }
            SyncScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                onLogBloodPressure = { navController.navigate(ROUTE_BP_ENTRY_BASE) { launchSingleTop = true } },
                onLogGlucose = { navController.navigate(ROUTE_GLUCOSE_ENTRY_BASE) { launchSingleTop = true } },
                snackbarMessage = snackbarMessage,
                onSnackbarShown = { backStackEntry.savedStateHandle.remove<String>(KEY_SNACKBAR_MSG) },
                viewModel = syncViewModel,
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popIfCurrent("settings") },
            )
        }
        composable(
            route = ROUTE_BP_ENTRY,
            arguments = listOf(sharedPathArgument()),
        ) {
            BpEntryScreen(
                onNavigateHome = { snackbarMsg ->
                    navController.returnToSync(KEY_SNACKBAR_MSG, snackbarMsg)
                },
                onCancel = { navController.returnToSync() },
            )
        }
        composable(
            route = ROUTE_GLUCOSE_ENTRY,
            arguments = listOf(sharedPathArgument()),
        ) {
            GlucoseEntryScreen(
                onNavigateHome = { snackbarMsg ->
                    navController.returnToSync(KEY_SNACKBAR_MSG, snackbarMsg)
                },
                onCancel = { navController.returnToSync() },
            )
        }
    }
}
