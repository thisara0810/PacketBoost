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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

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
    private var isCurrentStandalone = true

    companion object {
        private const val TAG = "TunnelVpnService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "packetboost_vpn_channel"

        const val ACTION_CONNECT = "com.packetboost.app.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.packetboost.app.ACTION_DISCONNECT"

        const val EXTRA_SERVER_ADDR = "extra_server_addr"
        const val EXTRA_SECRET_KEY = "extra_secret_key"
        const val EXTRA_IS_STANDALONE = "extra_is_standalone"

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
                val isStandalone = intent.getBooleanExtra(EXTRA_IS_STANDALONE, true)
                val serverAddr = intent.getStringExtra(EXTRA_SERVER_ADDR) ?: ""
                val secretKey = intent.getStringExtra(EXTRA_SECRET_KEY) ?: ""
                startTunnel(serverAddr, secretKey, isStandalone)
            }
            ACTION_DISCONNECT -> {
                stopTunnel()
            }
        }

        return START_STICKY
    }

    private fun startTunnel(serverAddr: String, secretKey: String, isStandalone: Boolean) {
        if (isRunning) return
        isRunning = true
        isCurrentStandalone = isStandalone
        _connectionState.value = ConnectionState.CONNECTING

        val notification = createNotification(
            if (isStandalone) "Activating Standalone Cellular Boost..." else "Connecting to PacketBoost node..."
        )
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            try {
                // 1. Establish Virtual TUN Interface
                val builder = Builder().apply {
                    setSession(if (isStandalone) "PacketBoost Standalone" else "PacketBoost Tunnel")
                    setMtu(1350) // MTU 1350 prevents carrier packet fragmentation
                    addAddress("10.255.0.2", 30)
                    addDnsServer("1.1.1.1")
                    addDnsServer("1.0.0.1")
                    addDnsServer("8.8.8.8")

                    if (isStandalone) {
                        // Route DNS traffic through TUN to accelerate resolution & clamp MTU
                        addRoute("1.1.1.1", 32)
                        addRoute("1.0.0.1", 32)
                        addRoute("8.8.8.8", 32)
                    } else {
                        // Capture all IPv4 traffic for VPS relay
                        addRoute("0.0.0.0", 0)
                    }
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
                Log.i(TAG, "TUN interface established with fd=$tunFd, MTU=1350, Standalone=$isStandalone")

                _connectionState.value = ConnectionState.CONNECTED
                updateNotification(
                    if (isStandalone) "PacketBoost Active (MTU 1350 + Cloudflare Anycast DNS)"
                    else "Accelerated Tunnel Active ($serverAddr)"
                )

                if (isStandalone) {
                    // Run on-device low-latency DNS forwarder with MTU clamping
                    runStandaloneLoop(pfd)
                } else {
                    // Start Native Go Cgo Engine Bridge for VPS relay
                    val result = NativeCoreBridge.startNativeCore(
                        tunFd = tunFd,
                        serverAddr = serverAddr,
                        secretKey = secretKey,
                        mtu = 1350
                    )

                    if (result != 0) {
                        Log.e(TAG, "Native core exited with error code: $result")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting tunnel service", e)
            } finally {
                cleanUp()
            }
        }
    }

    private fun runStandaloneLoop(pfd: ParcelFileDescriptor) {
        val inputStream = FileInputStream(pfd.fileDescriptor)
        val outputStream = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteArray(2048)
        val cloudflareDns = InetAddress.getByName("1.1.1.1")

        val socket = DatagramSocket()
        protect(socket)
        socket.soTimeout = 3000

        try {
            while (isRunning) {
                val length = try {
                    inputStream.read(buffer)
                } catch (e: Exception) {
                    break
                }
                if (length <= 0) continue

                // Check IPv4 UDP packet (Protocol 17)
                if (length >= 28 && (buffer[0].toInt() and 0xF0) == 0x40 && buffer[9].toInt() == 17) {
                    val srcPort = ((buffer[20].toInt() and 0xFF) shl 8) or (buffer[21].toInt() and 0xFF)
                    val dstPort = ((buffer[22].toInt() and 0xFF) shl 8) or (buffer[23].toInt() and 0xFF)

                    // If DNS query (destination port 53)
                    if (dstPort == 53) {
                        val dnsPayload = buffer.copyOfRange(28, length)
                        serviceScope.launch(Dispatchers.IO) {
                            try {
                                val queryPacket = DatagramPacket(dnsPayload, dnsPayload.size, cloudflareDns, 53)
                                val responseBuf = ByteArray(2048)
                                val responsePacket = DatagramPacket(responseBuf, responseBuf.size)

                                socket.send(queryPacket)
                                socket.receive(responsePacket)

                                val respLen = responsePacket.length
                                if (respLen > 0) {
                                    val ipPacket = ByteArray(28 + respLen)
                                    // IPv4 header (20 bytes)
                                    ipPacket[0] = 0x45.toByte()
                                    val totalLen = 28 + respLen
                                    ipPacket[2] = ((totalLen shr 8) and 0xFF).toByte()
                                    ipPacket[3] = (totalLen and 0xFF).toByte()
                                    ipPacket[8] = 64.toByte() // TTL
                                    ipPacket[9] = 17.toByte() // UDP Protocol

                                    // Swap IPs: Src IP becomes Dst IP, Dst IP becomes Src IP
                                    System.arraycopy(buffer, 16, ipPacket, 12, 4)
                                    System.arraycopy(buffer, 12, ipPacket, 16, 4)

                                    // Compute IP Checksum
                                    computeIpChecksum(ipPacket, 20)

                                    // UDP header (8 bytes)
                                    ipPacket[20] = ((dstPort shr 8) and 0xFF).toByte() // 53
                                    ipPacket[21] = (dstPort and 0xFF).toByte()
                                    ipPacket[22] = ((srcPort shr 8) and 0xFF).toByte()
                                    ipPacket[23] = (srcPort and 0xFF).toByte()
                                    val udpLen = 8 + respLen
                                    ipPacket[24] = ((udpLen shr 8) and 0xFF).toByte()
                                    ipPacket[25] = (udpLen and 0xFF).toByte()

                                    // Copy DNS Response Payload
                                    System.arraycopy(responseBuf, 0, ipPacket, 28, respLen)

                                    synchronized(outputStream) {
                                        outputStream.write(ipPacket)
                                    }
                                }
                            } catch (_: Exception) {
                                // Ignore dropped or timed-out DNS packets
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Standalone loop terminated", e)
        } finally {
            socket.close()
        }
    }

    private fun computeIpChecksum(header: ByteArray, length: Int) {
        header[10] = 0
        header[11] = 0
        var sum = 0
        for (i in 0 until length step 2) {
            val high = header[i].toInt() and 0xFF
            val low = if (i + 1 < length) header[i + 1].toInt() and 0xFF else 0
            sum += (high shl 8) or low
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = (sum.inv()) and 0xFFFF
        header[10] = ((checksum shr 8) and 0xFF).toByte()
        header[11] = (checksum and 0xFF).toByte()
    }

    private fun stopTunnel() {
        if (!isRunning) return
        _connectionState.value = ConnectionState.DISCONNECTING

        serviceScope.launch {
            try {
                if (!isCurrentStandalone) {
                    NativeCoreBridge.stopNativeCore()
                }
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
                "PacketBoost Tunnel Service",
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
