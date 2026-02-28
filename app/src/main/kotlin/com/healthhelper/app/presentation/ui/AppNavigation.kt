package com.healthhelper.app.presentation.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

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
                snackbarMessage = backStackEntry.savedStateHandle.get<String>("snackbar_msg"),
                onSnackbarShown = { backStackEntry.savedStateHandle.remove<String>("snackbar_msg") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable("camera-bp") {
            CameraCaptureScreen(
                onNavigateToConfirmation = { sys, dia ->
                    navController.navigate("bp-confirm/$sys/$dia")
                },
                onNavigateBack = { navController.popBackStack() },
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
                    navController.currentBackStackEntry?.savedStateHandle?.set("snackbar_msg", snackbarMsg)
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
