package com.chetbox.mousecursor

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = getSharedPreferences("MousePrefs", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        sharedPreferences = sharedPreferences,
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onRequestNotificationPermission = { requestNotificationPermission() },
                        onRequestShizukuPermission = { requestShizukuPermission() },
                        onToggleService = { enabled ->
                            if (enabled) {
                                startService(Intent(this, TouchpadService::class.java))
                            } else {
                                stopService(Intent(this, TouchpadService::class.java))
                            }
                        }
                    )
                }
            }
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        }
    }

    private fun requestShizukuPermission() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0)
            }
        }
    }
}

@Composable
fun MainScreen(
    sharedPreferences: SharedPreferences,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onToggleService: (Boolean) -> Unit
) {
    var isServiceRunning by remember { mutableStateOf(false) } // Simplification, ideally check service status

    var alpha by remember { mutableStateOf(sharedPreferences.getFloat("touchpad_alpha", 0.5f)) }
    var sizeMultiplier by remember { mutableStateOf(sharedPreferences.getFloat("touchpad_size", 1.0f)) }
    var cursorSizeMultiplier by remember { mutableStateOf(sharedPreferences.getFloat("cursor_size", 1.0f)) }
    var fullScreenMode by remember { mutableStateOf(sharedPreferences.getBoolean("full_screen_mode", false)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Android Mouse Cursor", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onRequestShizukuPermission) {
            Text("Request Shizuku Permission")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onRequestOverlayPermission) {
            Text("Request Display Over Other Apps")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onRequestNotificationPermission) {
            Text("Request Notification Permission")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable Touchpad")
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = isServiceRunning,
                onCheckedChange = {
                    isServiceRunning = it
                    onToggleService(it)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Full Screen Invisible Mode")
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = fullScreenMode,
                onCheckedChange = {
                    fullScreenMode = it
                    sharedPreferences.edit().putBoolean("full_screen_mode", it).apply()
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Touchpad Transparency")
        Slider(
            value = alpha,
            onValueChange = {
                alpha = it
                sharedPreferences.edit().putFloat("touchpad_alpha", it).apply()
            },
            valueRange = 0f..1f,
            enabled = !fullScreenMode
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Touchpad Size")
        Slider(
            value = sizeMultiplier,
            onValueChange = {
                sizeMultiplier = it
                sharedPreferences.edit().putFloat("touchpad_size", it).apply()
            },
            valueRange = 0.5f..2.0f,
            enabled = !fullScreenMode
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Cursor Size")
        Slider(
            value = cursorSizeMultiplier,
            onValueChange = {
                cursorSizeMultiplier = it
                sharedPreferences.edit().putFloat("cursor_size", it).apply()
            },
            valueRange = 0.5f..3.0f
        )
    }
}
