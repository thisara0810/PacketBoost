package com.packetboost.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

class TunnelVpnService : VpnService() {

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    companion object {
        private const val TAG = "TunnelVpnService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "packetboost_vpn_channel"

        const val ACTION_CONNECT = "com.packetboost.app.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.packetboost.app.ACTION_DISCONNECT"

        const val EXTRA_SERVER_ADDR = "extra_server_addr"
        const val EXTRA_SECRET_KEY = "extra_secret_key"

        private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_CONNECT -> {
                val serverAddr = intent.getStringExtra(EXTRA_SERVER_ADDR) ?: ""
                val secretKey = intent.getStringExtra(EXTRA_SECRET_KEY) ?: ""
                if (serverAddr.isNotEmpty()) {
                    startTunnel(serverAddr, secretKey)
                } else {
                    Log.e(TAG, "Cannot start VPN: Server address is empty")
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> {
                stopTunnel()
            }
        }

        return START_STICKY
    }

    private fun startTunnel(serverAddr: String, secretKey: String) {
        if (isRunning) return
        isRunning = true
        _connectionState.value = ConnectionState.CONNECTING

        val notification = createNotification("Connecting to PacketBoost node...")
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            try {
                // 1. Establish Virtual TUN Interface
                val builder = Builder().apply {
                    setSession("PacketBoostTunnel")
                    setMtu(1350) // MTU 1350 to prevent carrier packet fragmentation
                    addAddress("10.255.0.2", 30)
                    addDnsServer("1.1.1.1")
                    addDnsServer("8.8.8.8")
                    addRoute("0.0.0.0", 0) // Capture all IPv4 traffic
                    setBlocking(true)
                }

                vpnInterface = builder.establish()
                val pfd = vpnInterface
                if (pfd == null) {
                    Log.e(TAG, "Failed to establish TUN interface: Builder returned null")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    stopSelf()
                    return@launch
                }

                val tunFd = pfd.fd
                Log.i(TAG, "TUN interface established with fd=$tunFd, MTU=1350")

                _connectionState.value = ConnectionState.CONNECTED
                updateNotification("Accelerated Tunnel Active ($serverAddr)")

                // 2. Start Native Go Cgo Engine Bridge
                val result = NativeCoreBridge.startNativeCore(
                    tunFd = tunFd,
                    serverAddr = serverAddr,
                    secretKey = secretKey,
                    mtu = 1350
                )

                if (result != 0) {
                    Log.e(TAG, "Native core exited with error code: $result")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting tunnel service", e)
            } finally {
                cleanUp()
            }
        }
    }

    private fun stopTunnel() {
        if (!isRunning) return
        _connectionState.value = ConnectionState.DISCONNECTING

        serviceScope.launch {
            try {
                NativeCoreBridge.stopNativeCore()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping native core", e)
            } finally {
                cleanUp()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cleanUp() {
        isRunning = false
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN TUN interface", e)
        }
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTunnel()
        serviceJob.cancel()
    }

    override fun onRevoke() {
        super.onRevoke()
        stopTunnel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_service_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PacketBoost VPN status notification"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, TunnelVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PacketBoost Network Accelerator")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                disconnectPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification(statusText))
    }
}
