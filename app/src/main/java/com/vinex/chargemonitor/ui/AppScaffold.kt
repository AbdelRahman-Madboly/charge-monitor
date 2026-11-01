package com.vinex.chargemonitor.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.vinex.chargemonitor.LiveBus
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.ui.bank.BankDetailScreen
import com.vinex.chargemonitor.ui.history.SessionDetailScreen
import com.vinex.chargemonitor.ui.onboarding.OnboardingScreen
import com.vinex.chargemonitor.R
import com.vinex.chargemonitor.ui.dashboard.DashboardScreen
import com.vinex.chargemonitor.ui.history.HistoryScreen
import com.vinex.chargemonitor.ui.live.LiveScreen
import com.vinex.chargemonitor.ui.components.AppIcons
import com.vinex.chargemonitor.ui.settings.SettingsScreen

/** The four bottom-navigation destinations; [route] is the NavHost route. */
enum class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    LIVE("live", R.string.tab_live, AppIcons.Bolt),
    DASHBOARD("dashboard", R.string.tab_dashboard, AppIcons.BarChart),
    HISTORY("history", R.string.tab_history, AppIcons.History),
    SETTINGS("settings", R.string.tab_settings, Icons.Filled.Settings),
}

private const val BANK_ROUTE = "bank/{id}"
private const val SESSION_ROUTE = "session/{id}"

/**
 * [pickSourceFor] is a session whose source the user asked to choose from a notification: the
 * recording session opens the Live picker, an older one opens its detail screen, which has the
 * same control. [onPickSourceHandled] is called once the request has been routed.
 */
@Composable
fun AppScaffold(pickSourceFor: Long? = null, onPickSourceHandled: () -> Unit = {}) {
    val settings = ServiceLocator.settings
    val onboarded by settings.onboardingDone.flow.collectAsState()
    if (!onboarded) {
        // Surface supplies onBackground as the content colour; without it untinted text is black
        // on the dark theme.
        androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
            OnboardingScreen(onDone = { settings.onboardingDone.set(true) })
        }
        return
    }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    val onTab = current?.route != SESSION_ROUTE && current?.route != BANK_ROUTE
    var livePickerRequested by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pickSourceFor) {
        val id = pickSourceFor ?: return@LaunchedEffect
        if (LiveBus.state.value.sessionId == id) {
            navigateToTab(nav, Tab.LIVE)
            livePickerRequested = true
        } else {
            nav.navigate("session/$id")
        }
        onPickSourceHandled()
    }

    Scaffold(
        bottomBar = {
            if (onTab) Column {
                // Hairline edge so the bar separates from content that scrolls under it.
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                ) {
                    Tab.entries.forEach { tab ->
                        val selected = current?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateToTab(nav, tab) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = {
                                Text(
                                    stringResource(tab.label),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.LIVE.route, modifier = Modifier.padding(padding)) {
            composable(Tab.LIVE.route) {
                LiveScreen(
                    openPicker = livePickerRequested,
                    onPickerOpened = { livePickerRequested = false },
                )
            }
            composable(Tab.DASHBOARD.route) { DashboardScreen(onOpenBank = { nav.navigate("bank/$it") }) }
            composable(Tab.HISTORY.route) { HistoryScreen(onOpenSession = { nav.navigate("session/$it") }) }
            composable(BANK_ROUTE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                BankDetailScreen(bankId = entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
            }
            composable(SESSION_ROUTE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                SessionDetailScreen(
                    sessionId = entry.arguments?.getLong("id") ?: 0L,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Tab.SETTINGS.route) { SettingsScreen(onOpenHistory = { navigateToTab(nav, Tab.HISTORY) }) }
        }
    }
}

/** Standard bottom-nav behaviour: one copy of each tab, each keeping its state. */
private fun navigateToTab(nav: NavHostController, tab: Tab) {
    nav.navigate(tab.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
