// Harness shim of androidx.navigation:navigation-compose 2.8.x (NavHost, composable, rememberNavController).
@file:JvmName("ShimNavigationComposeKt")
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.navigation.compose

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.Navigator
import androidx.navigation.NavDestination

@Composable
fun rememberNavController(vararg navigators: Navigator<out NavDestination>): NavHostController =
    throw UnsupportedOperationException("harness stub")

@Composable
fun NavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    route: String? = null,
    enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = { fadeIn() },
    exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { fadeOut() },
    popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = enterTransition,
    popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = exitTransition,
    sizeTransform: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> SizeTransform?)? = null,
    builder: NavGraphBuilder.() -> Unit,
) {
    throw UnsupportedOperationException("harness stub")
}

fun NavGraphBuilder.composable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    enterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = null,
    exitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = null,
    popEnterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = enterTransition,
    popExitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = exitTransition,
    sizeTransform: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> SizeTransform?)? = null,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    throw UnsupportedOperationException("harness stub")
}
