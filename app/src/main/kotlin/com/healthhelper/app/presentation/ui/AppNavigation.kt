package com.healthhelper.app.presentation.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

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
                popUpTo("sync") { inclusive = false }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "sync",
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable("sync") { backStackEntry ->
            SyncScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToCamera = { navController.navigate("camera-bp") },
                onNavigateToGlucoseCamera = { navController.navigate("camera-glucose") },
                snackbarMessage = backStackEntry.savedStateHandle.get<String>("snackbar_msg"),
                onSnackbarShown = { backStackEntry.savedStateHandle.remove<String>("snackbar_msg") },
                bpScanError = backStackEntry.savedStateHandle.get<String>("bp_scan_error"),
                onBpScanErrorShown = { backStackEntry.savedStateHandle.remove<String>("bp_scan_error") },
                glucoseScanError = backStackEntry.savedStateHandle.get<String>("glucose_scan_error"),
                onGlucoseScanErrorShown = { backStackEntry.savedStateHandle.remove<String>("glucose_scan_error") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "camera-bp?sharedPath={sharedPath}",
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
                    navController.navigate("bp-confirm/$sys/$dia")
                },
                onNavigateBack = { navController.popBackStack() },
                onNavigateBackWithError = { error ->
                    navController.popBackStack()
                    navController.getBackStackEntry("sync").savedStateHandle["bp_scan_error"] = error
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
                    navController.navigate("sync") {
                        popUpTo("sync") { inclusive = true }
                    }
                    navController.getBackStackEntry("sync").savedStateHandle["snackbar_msg"] = snackbarMsg
                },
                onCancel = {
                    navController.navigate("sync") {
                        popUpTo("sync") { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = "camera-glucose?sharedPath={sharedPath}",
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
                onNavigateToConfirmation = { value, unit, detectedUnit ->
                    val encodedUnit = java.net.URLEncoder.encode(unit, "UTF-8")
                    val encodedDetected = java.net.URLEncoder.encode(detectedUnit, "UTF-8")
                    navController.navigate("glucose-confirm/$value/$encodedUnit/$encodedDetected")
                },
                onNavigateBack = { navController.popBackStack() },
                onNavigateBackWithError = { error ->
                    navController.popBackStack()
                    navController.getBackStackEntry("sync").savedStateHandle["glucose_scan_error"] = error
                },
                sharedImagePath = decodedPath,
            )
        }
        composable(
            route = "glucose-confirm/{value}/{unit}/{detectedUnit}",
            arguments = listOf(
                navArgument("value") { type = NavType.FloatType },
                navArgument("unit") { type = NavType.StringType },
                navArgument("detectedUnit") { type = NavType.StringType },
            ),
        ) {
            GlucoseConfirmationScreen(
                onNavigateHome = { snackbarMsg ->
                    navController.navigate("sync") {
                        popUpTo("sync") { inclusive = true }
                    }
                    navController.getBackStackEntry("sync").savedStateHandle["snackbar_msg"] = snackbarMsg
                },
                onCancel = {
                    navController.navigate("sync") {
                        popUpTo("sync") { inclusive = true }
                    }
                },
            )
        }
    }
}
