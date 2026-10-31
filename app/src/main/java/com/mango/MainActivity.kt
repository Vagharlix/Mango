package com.mango

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.mango.ui.MangoNavHost
import com.mango.ui.MangoTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_TIMER = "open_timer"
    }

    private val openTimer = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openTimer.value = intent?.getBooleanExtra(EXTRA_OPEN_TIMER, false) == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MangoTheme {
                MangoNavHost(openTimer = openTimer.value, onTimerOpened = { openTimer.value = false })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_TIMER, false)) openTimer.value = true
    }
}
