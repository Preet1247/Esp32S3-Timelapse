package com.watchlink.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.watchlink.mobile.ui.AppsScreen
import com.watchlink.mobile.ui.SettingsScreen
import com.watchlink.mobile.ui.StatusScreen
import com.watchlink.mobile.ui.WatchFacesScreen
import com.watchlink.mobile.ui.WatchLinkTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatchLinkTheme {
                WatchLinkApp(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
        WatchLinkNotificationService.instance?.registerCallMonitor()
    }
}

private data class TabItem(val label: String, val icon: ImageVector)

@Composable
private fun WatchLinkApp(vm: MainViewModel) {
    val tabs = listOf(
        TabItem("Status", Icons.Filled.Watch),
        TabItem("Apps", Icons.Filled.Apps),
        TabItem("Watch faces", Icons.Filled.Face),
        TabItem("Settings", Icons.Filled.Settings),
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner)) {
            when (selected) {
                0 -> StatusScreen(vm)
                1 -> AppsScreen(vm)
                2 -> WatchFacesScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}
