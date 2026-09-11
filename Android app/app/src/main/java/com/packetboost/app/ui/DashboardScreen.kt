package com.packetboost.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.packetboost.app.TunnelVpnService
import com.packetboost.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    connectionState: TunnelVpnService.ConnectionState,
    boostMode: String,
    serverAddress: String,
    secretKey: String,
    autoConnect: Boolean,
    onBoostModeChange: (String) -> Unit,
    onServerAddressChange: (String) -> Unit,
    onSecretKeyChange: (String) -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onToggleConnection: () -> Unit
) {
    val isConnected = connectionState == TunnelVpnService.ConnectionState.CONNECTED
    val isConnecting = connectionState == TunnelVpnService.ConnectionState.CONNECTING

    // Pulsing animation for active/connecting state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isConnected || isConnecting) 1.25f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val buttonColor by animateColorAsState(
        targetValue = when (connectionState) {
            TunnelVpnService.ConnectionState.CONNECTED -> PrimaryNeon
            TunnelVpnService.ConnectionState.CONNECTING -> WarningAmber
            else -> SurfaceVariantDark
        },
        label = "color"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = PrimaryNeon,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "PacketBoost",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // Top Status & Badge Info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Connection Status Chip
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = when (connectionState) {
                        TunnelVpnService.ConnectionState.CONNECTED -> PrimaryNeon.copy(alpha = 0.15f)
                        TunnelVpnService.ConnectionState.CONNECTING -> WarningAmber.copy(alpha = 0.15f)
                        else -> SurfaceVariantDark
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    when (connectionState) {
                                        TunnelVpnService.ConnectionState.CONNECTED -> PrimaryNeon
                                        TunnelVpnService.ConnectionState.CONNECTING -> WarningAmber
                                        else -> DisconnectedGray
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (connectionState) {
                                TunnelVpnService.ConnectionState.CONNECTED -> "ACCELERATION ACTIVE"
                                TunnelVpnService.ConnectionState.CONNECTING -> "CONNECTING TUNNEL..."
                                TunnelVpnService.ConnectionState.DISCONNECTING -> "DISCONNECTING..."
                                else -> "DISCONNECTED"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (connectionState) {
                                TunnelVpnService.ConnectionState.CONNECTED -> PrimaryNeon
                                TunnelVpnService.ConnectionState.CONNECTING -> WarningAmber
                                else -> TextSecondary
                            }
                        )
                    }
                }

                // Latency Badge
                if (isConnected) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceDark,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.NetworkCheck,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (boostMode == "standalone")
                                    "Direct 4G/5G Wire-Speed | MTU: 1350 | Cloudflare Anycast DNS"
                                else
                                    "Latency: 28 ms | RS-FEC: 10:3 | MTU: 1350",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // Central Animated Connect Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(vertical = 16.dp)
            ) {
                // Pulse Ring Background
                if (isConnected || isConnecting) {
                    Box(
                        modifier = Modifier
                            .size(170.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(buttonColor.copy(alpha = 0.2f))
                    )
                }

                // Main Button Outer Ring
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    SurfaceDark,
                                    DarkBackground
                                )
                            )
                        )
                        .border(
                            width = 3.dp,
                            color = buttonColor,
                            shape = CircleShape
                        )
                        .clickable(enabled = !isConnecting) {
                            onToggleConnection()
                        }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Toggle Connection",
                            tint = buttonColor,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isConnected) "STOP" else if (isConnecting) "BOOSTING..." else "BOOST",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            color = buttonColor
                        )
                    }
                }
            }

            // Mode Selector & Configuration Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Mode Selection Segmented Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkBackground)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (boostMode == "standalone") PrimaryNeon.copy(alpha = 0.2f) else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !isConnected && !isConnecting) {
                                    onBoostModeChange("standalone")
                                }
                        ) {
                            Text(
                                text = "⚡ Standalone (No VPS)",
                                fontSize = 11.sp,
                                fontWeight = if (boostMode == "standalone") FontWeight.Bold else FontWeight.Normal,
                                color = if (boostMode == "standalone") PrimaryNeon else TextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (boostMode == "vps") PrimaryNeon.copy(alpha = 0.2f) else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !isConnected && !isConnecting) {
                                    onBoostModeChange("vps")
                                }
                        ) {
                            Text(
                                text = "🌐 VPS Tunnel",
                                fontSize = 11.sp,
                                fontWeight = if (boostMode == "vps") FontWeight.Bold else FontWeight.Normal,
                                color = if (boostMode == "vps") PrimaryNeon else TextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }

                    if (boostMode == "standalone") {
                        // Standalone Features Overview
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = PrimaryNeon,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "100% Direct Wire-Speed (Zero Server Lag)",
                                    fontSize = 11.sp,
                                    color = TextPrimary
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.NetworkCheck,
                                    contentDescription = null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "MTU 1350 Clamping (Stops 4G Packet Drops)",
                                    fontSize = 11.sp,
                                    color = TextPrimary
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Router,
                                    contentDescription = null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Cloudflare 1.1.1.1 Anycast Low-Ping DNS",
                                    fontSize = 11.sp,
                                    color = TextPrimary
                                )
                            }
                        }
                    } else {
                        // VPS Inputs
                        OutlinedTextField(
                            value = serverAddress,
                            onValueChange = onServerAddressChange,
                            label = { Text("VPS IP : Port") },
                            placeholder = { Text("e.g. 140.238.xxx.xxx:29900") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Router,
                                    contentDescription = null,
                                    tint = AccentCyan
                                )
                            },
                            singleLine = true,
                            enabled = !isConnected && !isConnecting,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryNeon,
                                unfocusedBorderColor = SurfaceVariantDark,
                                focusedLabelColor = PrimaryNeon,
                                unfocusedLabelColor = TextSecondary,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = secretKey,
                            onValueChange = onSecretKeyChange,
                            label = { Text("Secret Key (AES-128)") },
                            placeholder = { Text("Enter tunnel secret key") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = AccentCyan
                                )
                            },
                            singleLine = true,
                            enabled = !isConnected && !isConnecting,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryNeon,
                                unfocusedBorderColor = SurfaceVariantDark,
                                focusedLabelColor = PrimaryNeon,
                                unfocusedLabelColor = TextSecondary,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Auto Connect Toggle Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Autorenew,
                                contentDescription = null,
                                tint = PrimaryNeon,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Auto-Boost on App Launch",
                                fontSize = 12.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Switch(
                            checked = autoConnect,
                            onCheckedChange = onAutoConnectChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PrimaryNeon,
                                checkedTrackColor = PrimaryNeon.copy(alpha = 0.3f),
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = SurfaceVariantDark
                            )
                        )
                    }
                }
            }

            // Bottom Protocol Note
            Text(
                text = if (boostMode == "standalone")
                    "Engine: On-Device Cellular Accelerator | MTU 1350 Clamping | Cloudflare Anycast DNS"
                else
                    "Engine: KCP ARQ (fast3) | Reed-Solomon FEC 10:3 | Server BBR v3",
                fontSize = 10.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
