package com.vinex.chargemonitor.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vinex.chargemonitor.R
import com.vinex.chargemonitor.ui.dashboard.DashboardScreen
import com.vinex.chargemonitor.ui.history.HistoryScreen
import com.vinex.chargemonitor.ui.live.LiveScreen
import com.vinex.chargemonitor.ui.settings.SettingsScreen

/** The four bottom-navigation destinations; [route] is the NavHost route. */
enum class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    LIVE("live", R.string.tab_live, Icons.Filled.PlayArrow),
    DASHBOARD("dashboard", R.string.tab_dashboard, Icons.Filled.Home),
    HISTORY("history", R.string.tab_history, Icons.Filled.DateRange),
    SETTINGS("settings", R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun AppScaffold() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            nav.navigate(tab.route) {
                                // Standard bottom-nav behaviour: one copy of each tab, state kept.
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.LIVE.route, modifier = Modifier.padding(padding)) {
            composable(Tab.LIVE.route) { LiveScreen() }
            composable(Tab.DASHBOARD.route) { DashboardScreen() }
            composable(Tab.HISTORY.route) { HistoryScreen() }
            composable(Tab.SETTINGS.route) { SettingsScreen() }
        }
    }
}
