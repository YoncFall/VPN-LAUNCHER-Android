// Точка входа: тёмное окно с Compose-раскладкой (порт главного окна
// vpn_launcher/ui/window.py; логика подключения - этапы 6-7).
package com.yoncfall.vpnlauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yoncfall.vpnlauncher.ui.AppScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppScreen() }
    }
}
