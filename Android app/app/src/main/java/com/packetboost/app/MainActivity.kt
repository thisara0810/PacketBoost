package com.packetboost.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.packetboost.app.ui.DashboardScreen
import com.packetboost.app.ui.theme.PacketBoostTheme

class MainActivity : ComponentActivity() {

    private const val PREFS_NAME = "packetboost_prefs"
    private const val KEY_SERVER_ADDR = "server_addr"
    private const val KEY_SECRET_KEY = "secret_key"
    private const val KEY_AUTO_CONNECT = "auto_connect"

    private var pendingServerAddr = ""
    private var pendingSecretKey = ""

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService(pendingServerAddr, pendingSecretKey)
        } else {
            Toast.makeText(this, "VPN Permission rejected by user", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission required for status bar badge", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedServerAddr = prefs.getString(KEY_SERVER_ADDR, "YOUR_ORACLE_VPS_IP:29900") ?: "YOUR_ORACLE_VPS_IP:29900"
        val savedSecretKey = prefs.getString(KEY_SECRET_KEY, "packetboost_secret") ?: "packetboost_secret"
        val savedAutoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, false)

        // Request Notification Permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Auto-connect on app launch if enabled and server IP is configured
        if (savedAutoConnect && savedServerAddr.isNotBlank() && savedServerAddr != "YOUR_ORACLE_VPS_IP:29900") {
            handleConnectRequest(savedServerAddr, savedSecretKey)
        }

        setContent {
            PacketBoostTheme {
                val connectionState by TunnelVpnService.connectionState.collectAsState()

                var serverAddress by remember { mutableStateOf(savedServerAddr) }
                var secretKey by remember { mutableStateOf(savedSecretKey) }
                var autoConnect by remember { mutableStateOf(savedAutoConnect) }

                DashboardScreen(
                    connectionState = connectionState,
                    serverAddress = serverAddress,
                    secretKey = secretKey,
                    autoConnect = autoConnect,
                    onServerAddressChange = { newAddr ->
                        serverAddress = newAddr
                        prefs.edit().putString(KEY_SERVER_ADDR, newAddr).apply()
                    },
                    onSecretKeyChange = { newKey ->
                        secretKey = newKey
                        prefs.edit().putString(KEY_SECRET_KEY, newKey).apply()
                    },
                    onAutoConnectChange = { newAutoConnect ->
                        autoConnect = newAutoConnect
                        prefs.edit().putBoolean(KEY_AUTO_CONNECT, newAutoConnect).apply()
                    },
                    onToggleConnection = {
                        if (connectionState == TunnelVpnService.ConnectionState.CONNECTED) {
                            stopVpnService()
                        } else {
                            handleConnectRequest(serverAddress, secretKey)
                        }
                    }
                )
            }
        }
    }

    private fun handleConnectRequest(serverAddr: String, secretKey: String) {
        if (serverAddr.isBlank() || serverAddr.contains("YOUR_ORACLE_VPS_IP")) {
            Toast.makeText(this, "Please enter your Oracle Cloud VPS IP address", Toast.LENGTH_SHORT).show()
            return
        }

        pendingServerAddr = serverAddr.trim()
        pendingSecretKey = secretKey.trim()

        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            startVpnService(pendingServerAddr, pendingSecretKey)
        }
    }

    private fun startVpnService(serverAddr: String, secretKey: String) {
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_CONNECT
            putExtra(TunnelVpnService.EXTRA_SERVER_ADDR, serverAddr)
            putExtra(TunnelVpnService.EXTRA_SECRET_KEY, secretKey)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }
}
