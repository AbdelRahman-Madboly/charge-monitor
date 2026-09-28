package com.vinex.chargemonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vinex.chargemonitor.service.ChargeMonitorService
import com.vinex.chargemonitor.ui.AppScaffold
import com.vinex.chargemonitor.ui.theme.ChargeMonitorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Opening the app is the first-run trigger for monitoring; later starts come from boot.
        ChargeMonitorService.start(this)
        setContent {
            ChargeMonitorTheme {
                AppScaffold()
            }
        }
    }
}
