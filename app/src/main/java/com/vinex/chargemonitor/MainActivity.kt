package com.vinex.chargemonitor

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinex.chargemonitor.data.ThemeMode
import com.vinex.chargemonitor.notify.Notifications
import com.vinex.chargemonitor.service.ChargeMonitorService
import com.vinex.chargemonitor.ui.AppScaffold
import com.vinex.chargemonitor.ui.theme.ChargeMonitorTheme

class MainActivity : ComponentActivity() {

    /** Session whose source picker a tapped "Connected to?" prompt asked for; null once shown. */
    private val pickSourceFor = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Opening the app is the first-run trigger for monitoring; later starts come from boot.
        ChargeMonitorService.start(this)
        // A recreated activity must not reopen the picker from the intent it was first launched with.
        if (savedInstanceState == null) readPickRequest(intent)
        setContent {
            val mode by ServiceLocator.settings.themeMode.flow.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status and navigation bar icons follow the app's theme, not the system's, so a
            // forced light theme on a dark phone still gets dark icons.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            ChargeMonitorTheme(darkTheme = dark) {
                AppScaffold(
                    pickSourceFor = pickSourceFor.value,
                    onPickSourceHandled = { pickSourceFor.value = null },
                )
            }
        }
    }

    // singleTop: a prompt tapped while the app is already open arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readPickRequest(intent)
    }

    private fun readPickRequest(intent: Intent?) {
        val id = intent?.getLongExtra(Notifications.EXTRA_PICK_SOURCE_SESSION_ID, -1L) ?: -1L
        if (id >= 0) pickSourceFor.value = id
    }

    private companion object {
        // The scrims enableEdgeToEdge uses by default, for three-button navigation bars.
        val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
