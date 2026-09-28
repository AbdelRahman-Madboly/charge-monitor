package com.vinex.chargemonitor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
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
            ChargeMonitorTheme {
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
}
