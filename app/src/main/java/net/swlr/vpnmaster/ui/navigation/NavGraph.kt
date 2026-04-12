package net.swlr.vpnmaster.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.swlr.vpnmaster.ui.screens.HomeScreen
import net.swlr.vpnmaster.ui.screens.ProfileEditScreen
import net.swlr.vpnmaster.ui.screens.ProfileListScreen
import net.swlr.vpnmaster.ui.screens.QrScanScreen
import net.swlr.vpnmaster.ui.screens.SettingsScreen
import net.swlr.vpnmaster.ui.screens.SplitTunnelScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "Home", Icons.Default.Home)
    data object Profiles : Screen("profiles", "Profiles", Icons.Default.List)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

object Routes {
    const val PROFILE_EDIT = "profile_edit/{profileId}"
    const val PROFILE_NEW = "profile_new"
    const val SPLIT_TUNNEL = "split_tunnel/{profileId}"
    const val QR_SCAN = "qr_scan"

    fun profileEdit(id: String) = "profile_edit/$id"
    fun splitTunnel(id: String) = "split_tunnel/$id"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val bottomBarScreens = listOf(Screen.Home, Screen.Profiles, Screen.Settings)

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            val showBottomBar = bottomBarScreens.any { screen ->
                currentDestination?.hierarchy?.any { it.route == screen.route } == true
            }

            if (showBottomBar) {
                NavigationBar {
                    bottomBarScreens.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(Screen.Home.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(navController = navController)
            }
            composable(Screen.Profiles.route) {
                ProfileListScreen(navController = navController)
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
            composable(Routes.PROFILE_NEW) {
                ProfileEditScreen(navController = navController, profileId = null)
            }
            composable(
                Routes.PROFILE_EDIT,
                arguments = listOf(navArgument("profileId") { type = NavType.StringType })
            ) { backStackEntry ->
                val profileId = backStackEntry.arguments?.getString("profileId")
                ProfileEditScreen(navController = navController, profileId = profileId)
            }
            composable(
                Routes.SPLIT_TUNNEL,
                arguments = listOf(navArgument("profileId") { type = NavType.StringType })
            ) { backStackEntry ->
                val profileId = backStackEntry.arguments?.getString("profileId") ?: return@composable
                SplitTunnelScreen(navController = navController, profileId = profileId)
            }
            composable(Routes.QR_SCAN) {
                QrScanScreen(navController = navController)
            }
        }
    }
}
