package com.dragonfly.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dragonfly.ui.detail.AppDetailScreen
import com.dragonfly.ui.home.HomeScreen
import com.dragonfly.ui.settings.SettingsScreen
import com.dragonfly.ui.status.SuiteStatusScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val STATUS = "status"
    const val APP_DETAIL = "app/{key}"
    fun appDetail(key: String) = "app/$key"
}

@Composable
fun DragonflyNavGraph() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenApp = { key -> navController.navigate(Routes.appDetail(key)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenStatus = { navController.navigate(Routes.STATUS) },
            )
        }
        composable(Routes.APP_DETAIL) {
            AppDetailScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.STATUS) {
            SuiteStatusScreen(onBack = { navController.popBackStack() })
        }
    }
}
