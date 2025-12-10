package com.leafdoc.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.leafdoc.app.ui.camera.CameraScreen
import com.leafdoc.app.ui.gallery.GalleryScreen
import com.leafdoc.app.ui.results.ResultsScreen
import com.leafdoc.app.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    data object Camera : Screen("camera")
    data object Gallery : Screen("gallery")
    data object Settings : Screen("settings")
    data object Results : Screen("results/{sessionId}") {
        fun createRoute(sessionId: String) = "results/$sessionId"
    }
}

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Camera.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Camera.route) {
            CameraScreen(
                onNavigateToGallery = {
                    navController.navigate(Screen.Gallery.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToResults = { sessionId ->
                    navController.navigate(Screen.Results.createRoute(sessionId)) {
                        // Remove Camera from backstack when navigating to Results after session complete
                        // This prevents multiple back presses and ensures clean navigation
                        popUpTo(Screen.Camera.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.Gallery.route) {
            GalleryScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToSession = { sessionId ->
                    navController.navigate(Screen.Results.createRoute(sessionId))
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.Results.route,
            arguments = listOf(
                navArgument("sessionId") {
                    type = NavType.StringType
                }
            )
        ) {
            ResultsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
