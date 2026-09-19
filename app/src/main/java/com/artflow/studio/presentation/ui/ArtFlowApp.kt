package com.artflow.studio.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.artflow.studio.presentation.ui.screens.canvas.CanvasScreen
import com.artflow.studio.presentation.ui.screens.gallery.GalleryScreen
import com.artflow.studio.presentation.ui.screens.help.HelpScreen
import com.artflow.studio.presentation.ui.screens.help.OnboardingDialog
import com.artflow.studio.presentation.ui.screens.settings.SettingsScreen
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel

/** Navigation routes, kept in one place so no screen has to hardcode strings. */
object Routes {
    const val GALLERY = "gallery"
    const val SETTINGS = "settings"
    const val HELP = "help"
    const val CANVAS = "canvas/{projectId}"

    fun canvas(projectId: Long): String = "canvas/$projectId"
}

/**
 * The app shell.
 *
 * The gallery is the home surface; the editor is a full-screen destination so the canvas gets the
 * whole display while painting.
 */
@Composable
fun ArtFlowApp(viewModel: MainViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val settings by viewModel.settings.collectAsState()
    val settingsLoaded by viewModel.settingsLoaded.collectAsState()
    var showOnboarding by remember { mutableStateOf(false) }

    LaunchedEffect(settingsLoaded, settings.seenOnboarding) {
        showOnboarding = settingsLoaded && !settings.seenOnboarding
    }

    NavHost(
        navController = navController,
        startDestination = Routes.GALLERY,
    ) {
        composable(Routes.GALLERY) {
            GalleryScreen(
                onNavigateToCanvas = { projectId -> navController.navigate(Routes.canvas(projectId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenHelp = { navController.navigate(Routes.HELP) },
            )
        }
        composable(Routes.CANVAS) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId")?.toLongOrNull() ?: 0L
            CanvasScreen(
                projectId = projectId,
                onNavigateBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Routes.HELP) {
            HelpScreen(
                onNavigateBack = { navController.popBackStack() },
                dismissedTips = settings.dismissedTips,
                onDismissTip = { viewModel.dismissTip(it) },
                onResetTips = { viewModel.restoreTips() },
            )
        }
    }

    if (showOnboarding) {
        OnboardingDialog { _ ->
            viewModel.setOnboardingSeen(true)
            showOnboarding = false
        }
    }
}
