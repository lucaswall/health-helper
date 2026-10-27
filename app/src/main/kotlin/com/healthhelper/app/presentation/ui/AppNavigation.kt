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
import com.healthhelper.app.presentation.viewmodel.GlucoseConfirmationViewModel
import com.healthhelper.app.presentation.viewmodel.SyncViewModel

private const val ROUTE_SYNC = "sync"
private const val ROUTE_CAMERA_BP = "camera-bp?sharedPath={sharedPath}"
private const val ROUTE_CAMERA_GLUCOSE = "camera-glucose?sharedPath={sharedPath}"

private const val KEY_SNACKBAR_MSG = "snackbar_msg"
private const val KEY_BP_SCAN_ERROR = "bp_scan_error"
private const val KEY_GLUCOSE_SCAN_ERROR = "glucose_scan_error"

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

/** Like [returnToSync], but only when [route] is the current destination (guards duplicate callbacks). */
private fun NavController.returnToSyncFrom(route: String, key: String, message: String) {
    if (currentBackStackEntry?.destination?.route == route) {
        returnToSync(key, message)
    }
}

@Composable
fun AppNavigation(
    sharedImagePath: String? = null,
    shareTarget: String? = null,
) {
    val navController = rememberNavController()

    // If launched via share intent, navigate directly to the correct camera screen
    LaunchedEffect(sharedImagePath, shareTarget) {
        if (sharedImagePath != null && shareTarget != null) {
            val encodedPath = java.net.URLEncoder.encode(sharedImagePath, "UTF-8")
            val route = when (shareTarget) {
                "glucose" -> "camera-glucose?sharedPath=$encodedPath"
                else -> "camera-bp?sharedPath=$encodedPath"
            }
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
                onNavigateToCamera = { navController.navigate("camera-bp") },
                onNavigateToGlucoseCamera = { navController.navigate("camera-glucose") },
                snackbarMessage = snackbarMessage,
                onSnackbarShown = { backStackEntry.savedStateHandle.remove<String>(KEY_SNACKBAR_MSG) },
                bpScanError = backStackEntry.savedStateHandle.get<String>(KEY_BP_SCAN_ERROR),
                onBpScanErrorShown = { backStackEntry.savedStateHandle.remove<String>(KEY_BP_SCAN_ERROR) },
                glucoseScanError = backStackEntry.savedStateHandle.get<String>(KEY_GLUCOSE_SCAN_ERROR),
                onGlucoseScanErrorShown = { backStackEntry.savedStateHandle.remove<String>(KEY_GLUCOSE_SCAN_ERROR) },
                viewModel = syncViewModel,
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popIfCurrent("settings") },
            )
        }
        composable(
            route = ROUTE_CAMERA_BP,
            arguments = listOf(
                navArgument("sharedPath") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("sharedPath")
            val decodedPath = encodedPath?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            }
            CameraCaptureScreen(
                onNavigateToConfirmation = { sys, dia ->
                    // Drop the capture entry so system Back from confirmation goes home, not to a blank capture screen.
                    navController.navigate("bp-confirm/$sys/$dia") {
                        popUpTo(ROUTE_CAMERA_BP) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popIfCurrent(ROUTE_CAMERA_BP) },
                onNavigateBackWithError = { error ->
                    navController.returnToSyncFrom(ROUTE_CAMERA_BP, KEY_BP_SCAN_ERROR, error)
                },
                sharedImagePath = decodedPath,
            )
        }
        composable(
            route = "bp-confirm/{systolic}/{diastolic}",
            arguments = listOf(
                navArgument("systolic") { type = NavType.IntType },
                navArgument("diastolic") { type = NavType.IntType },
            ),
        ) {
            BpConfirmationScreen(
                onNavigateHome = { snackbarMsg ->
                    navController.returnToSync(KEY_SNACKBAR_MSG, snackbarMsg)
                },
                onCancel = { navController.returnToSync() },
            )
        }
        composable(
            route = ROUTE_CAMERA_GLUCOSE,
            arguments = listOf(
                navArgument("sharedPath") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("sharedPath")
            val decodedPath = encodedPath?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            }
            GlucoseCaptureScreen(
                onNavigateToConfirmation = { value, unit ->
                    val encodedValue = java.net.URLEncoder.encode(value.toString(), "UTF-8")
                    // Drop the capture entry so system Back from confirmation goes home, not to a blank capture screen.
                    navController.navigate("glucose-confirm/$encodedValue/${unit.name}") {
                        popUpTo(ROUTE_CAMERA_GLUCOSE) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popIfCurrent(ROUTE_CAMERA_GLUCOSE) },
                onNavigateBackWithError = { error ->
                    navController.returnToSyncFrom(ROUTE_CAMERA_GLUCOSE, KEY_GLUCOSE_SCAN_ERROR, error)
                },
                sharedImagePath = decodedPath,
            )
        }
        composable(
            route = "glucose-confirm/{${GlucoseConfirmationViewModel.ARG_VALUE}}/{${GlucoseConfirmationViewModel.ARG_UNIT}}",
            arguments = listOf(
                navArgument(GlucoseConfirmationViewModel.ARG_VALUE) { type = NavType.StringType },
                navArgument(GlucoseConfirmationViewModel.ARG_UNIT) { type = NavType.StringType },
            ),
        ) {
            GlucoseConfirmationScreen(
                onNavigateHome = { snackbarMsg ->
                    navController.returnToSync(KEY_SNACKBAR_MSG, snackbarMsg)
                },
                onCancel = { navController.returnToSync() },
            )
        }
    }
}
