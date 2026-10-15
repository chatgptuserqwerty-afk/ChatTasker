package com.chattasker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.chattasker.data.Store
import com.chattasker.reminders.Reminders
import com.chattasker.ui.CaptureScreen
import com.chattasker.ui.NotesScreen
import com.chattasker.ui.SettingsScreen
import com.chattasker.ui.TasksScreen

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Reminders.createChannels(this)
        Reminders.scheduleDigest(this)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (savedInstanceState == null) handleShare(intent)

        setContent { AppTheme { AppRoot(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Text shared from WhatsApp, ChatGPT or Gemini lands here. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            vm.ingest(text)
            vm.tab.value = 0
            intent.action = null // don't re-import on rotation
        }
    }
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("Capture", Icons.Default.Send),
    Tab("Tasks", Icons.Default.List),
    Tab("Notes", Icons.Default.Create),
    Tab("Settings", Icons.Default.Settings),
)

@Composable
private fun AppRoot(vm: AppViewModel) {
    val tab by vm.tab.collectAsState()
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { vm.tab.value = i },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> CaptureScreen(vm)
                1 -> TasksScreen()
                2 -> NotesScreen()
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF7FD8C3))
        else -> lightColorScheme(primary = Color(0xFF0F5C4D))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
