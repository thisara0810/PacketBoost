# PacketBoost - Step-by-Step Build & Verification Manual

This guide provides end-to-end instructions for building the native Go engine, compiling the Android application, deploying the backend VPS acceleration relay, and verifying network performance gains.

---

## 1. Environment & Tools Requirements

Before beginning, ensure your host development machine has the following tools installed:
- **Android Studio / Android SDK** (API 34+ / Target 35)
- **Android NDK** (`r25c` or newer)
- **Go Programming Language** (Go 1.20+)
- **CMake** (3.22.1+)
- **Java Development Kit** (JDK 17)

---

## 2. Compiling the Go Native Core for Android (`libpacketboost_core.so`)

The PacketBoost acceleration engine is written in Go and cross-compiled into native C-shared `.so` libraries for Android CPU architectures (`arm64-v8a`, `armeabi-v7a`, `x86_64`).

### Step 2.1: Initialize Go Module & Download Dependencies

```bash
cd "c:\Users\N THISARA\Desktop\Android app\native\core"

# Initialize Go module
go mod init packetboost/native/core

# Download required KCP and smux dependencies
go get github.com/xtaci/kcp-go/v5
go get github.com/xtaci/smux
go get github.com/klauspost/reedsolomon
go mod tidy
```

### Step 2.2: Cross-Compile Go Engine Shared Libraries

Run the following commands in your shell to build `libpacketboost_core.so` for each target Android ABI:

#### For `arm64-v8a` (Modern 64-bit Android Phones):
```bash
# Set Android NDK C cross-compiler path (Adjust NDK path as needed)
$env:CGO_ENABLED="1"
$env:GOOS="android"
$env:GOARCH="arm64"
$env:CC="C:\Users\N THISARA\AppData\Local\Android\Sdk\ndk\25.2.9519653\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android30-clang.cmd"

# Create output directory inside app/src/main/jniLibs/arm64-v8a
mkdir -p "..\..\app\src\main\jniLibs\arm64-v8a"

# Compile shared library
go build -buildmode=c-shared -o "..\..\app\src\main\jniLibs\arm64-v8a\libpacketboost_core.so" bridge.go
```

#### For `armeabi-v7a` (32-bit Legacy Android Devices):
```bash
$env:CGO_ENABLED="1"
$env:GOOS="android"
$env:GOARCH="arm"
$env:GOARM="7"
$env:CC="C:\Users\N THISARA\AppData\Local\Android\Sdk\ndk\25.2.9519653\toolchains\llvm\prebuilt\windows-x86_64\bin\armv7a-linux-androideabi30-clang.cmd"

mkdir -p "..\..\app\src\main\jniLibs\armeabi-v7a"

go build -buildmode=c-shared -o "..\..\app\src\main\jniLibs\armeabi-v7a\libpacketboost_core.so" bridge.go
```

---

## 3. Building the Android Application APK

Once the native `.so` binaries are generated in `app/src/main/jniLibs/`, assemble the APK using Gradle.

```bash
cd "c:\Users\N THISARA\Desktop\Android app"

# Clean and Assemble Debug APK
.\gradlew.bat clean assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/app-debug.apk
```

### Install APK onto Physical Test Device via ADB:

```bash
# Ensure ADB is connected to physical Android device via USB/Wi-Fi
adb devices

# Install APK
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 4. Deploying Backend VPS Relay Server

Deploy the PacketBoost acceleration relay on a remote Linux server (Ubuntu 24.04 LTS or Debian 12) with a dedicated public IPv4 address.

### Step 4.1: Transfer Server Scripts to VPS
```bash
scp -r server/ root@YOUR_VPS_IP:/root/packetboost-server/
```

### Step 4.2: Execute Automated Installation
```bash
ssh root@YOUR_VPS_IP

cd /root/packetboost-server/
chmod +x setup_vps.sh
./setup_vps.sh
```

### Step 4.3: Verify Server Status & Logs
```bash
# Check systemd service status
systemctl status kcptun.service

# Inspect live KCP server traffic logs
journalctl -u kcptun.service -f -n 50
```

---

## 5. Verification & Performance Validation Protocol

Follow these verification steps on your physical phone to confirm zero-RTT packet loss recovery and carrier anti-throttling.

### Test 1: Dynamic MTU Clamping Verification
On phone terminal or via `adb shell`:
```bash
# Verify VPN Virtual Interface MTU is set to 1350
adb shell ifconfig tun0

# Expected Output:
# tun0: flags=4305<UP,POINTOPOINT,RUNNING,NOARP,MULTICAST> mtu 1350
```

### Test 2: BBR Congestion Control & Throughput Test
1. Launch PacketBoost app on phone.
2. Enter your VPS Server IP & Port (e.g. `YOUR_VPS_IP:29900`) and Secret Key `packetboost_secret`.
3. Tap **BOOST**. Confirm notification "Accelerated Tunnel Active".
4. Run a network speed/latency test:
```bash
# Check HTTP throughput & low-latency connection via curl
adb shell curl -w "\nTime Connect: %{time_connect}s\nTime Transfer: %{time_starttransfer}s\nTotal Time: %{time_total}s\n" https://speed.cloudflare.com/__down?bytes=100000000 -o /dev/null
```

### Test 3: Packet Loss Recovery & FEC Validation (Simulated 20% Packet Loss)
On VPS server, simulate 20% random packet drop to test Reed-Solomon (10:3) error correction recovery:
```bash
# Introduce 20% packet drop on VPS UDP port 29900
iptables -A INPUT -p udp --dport 29900 -m statistic --mode random --probability 0.20 -j DROP

# Observe live stats in PacketBoost Android logcat:
adb logcat -s PacketBoostJNI TunnelVpnService NativeCoreBridge

# Clean up test iptables drop rule when complete:
iptables -D INPUT -p udp --dport 29900 -m statistic --mode random --probability 0.20 -j DROP
```
*Outcome*: Connection maintains full responsiveness and throughput without TCP backoff freezes due to 30% RS-FEC parity redundancy!
