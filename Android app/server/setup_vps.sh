#!/usr/bin/env bash
# ==============================================================================
# PacketBoost VPS Backend Acceleration Relay Deployment Script
# Target OS: Ubuntu 24.04 LTS / Debian 12
# Enables: Google BBR v3 Congestion Control, TCP Buffer Optimization,
#          IP Forwarding, NAT Masquerading, and KCP Server Daemon.
# ==============================================================================

set -euo pipefail

echo "======================================================================"
echo "          PacketBoost - VPS Acceleration Relay Installer              "
echo "======================================================================"

if [ "$EUID" -ne 0 ]; then
  echo "Error: Please run this script as root or with sudo."
  exit 1
fi

# 1. Update system & install dependencies
echo "[1/6] Installing prerequisite packages..."
apt-get update -qq
apt-get install -y -qq wget curl jq iptables iptables-persistent ca-certificates

# Try installing microsocks for socks5 proxy backend
if ! apt-get install -y -qq microsocks 2>/dev/null; then
    echo "microsocks not in repos, installing build-essential to compile..."
    apt-get install -y -qq build-essential git
    cd /tmp
    rm -rf microsocks
    git clone --depth 1 https://github.com/rofl0r/microsocks.git
    cd microsocks
    make
    cp microsocks /usr/local/bin/microsocks
    chmod +x /usr/local/bin/microsocks
fi

# Configure microsocks systemd service
cat << 'EOF' > /etc/systemd/system/microsocks.service
[Unit]
Description=MicroSocks SOCKS5 Backend Proxy
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/microsocks -i 127.0.0.1 -p 1080
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

# If compiled locally, path is /usr/local/bin
if [ -f /usr/local/bin/microsocks ] && [ ! -f /usr/bin/microsocks ]; then
    sed -i 's|/usr/bin/microsocks|/usr/local/bin/microsocks|g' /etc/systemd/system/microsocks.service
fi

systemctl daemon-reload
systemctl enable --now microsocks

# 2. Configure sysctl Kernel Parameters for Google BBR v3 & TCP Buffers
echo "[2/6] Optimizing Linux kernel network stack for BBR & low bufferbloat..."

cat << 'EOF' > /etc/sysctl.d/99-packetboost.conf
# Enable Fair Queuing (FQ) & Google BBR Congestion Control
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr

# TCP Buffer Maximization (64MB max read/write buffers)
net.core.rmem_max = 67108864
net.core.wmem_max = 67108864
net.ipv4.tcp_rmem = 4096 87380 67108864
net.ipv4.tcp_wmem = 4096 65536 67108864

# Connection Backlog & Network Interface Tuning
net.core.netdev_max_backlog = 100000
net.core.somaxconn = 65535
net.ipv4.tcp_max_syn_backlog = 65535

# Enable IPv4 Forwarding
net.ipv4.ip_forward = 1

# Disable TCP slow start after idle
net.ipv4.tcp_slow_start_after_idle = 0
EOF

sysctl --system >/dev/null 2>&1

ACTIVE_CC=$(sysctl net.ipv4.tcp_congestion_control | awk '{print $3}')
echo "Active TCP Congestion Control: $ACTIVE_CC"

# 3. Setup NAT Packet Forwarding via iptables
echo "[3/6] Setting up iptables NAT forwarding..."
DEFAULT_IFACE=$(ip route show default 2>/dev/null | awk '/default/ {print $5}' | head -n1 || echo "eth0")
echo "Default Network Interface: $DEFAULT_IFACE"

iptables -t nat -A POSTROUTING -o "$DEFAULT_IFACE" -j MASQUERADE 2>/dev/null || true
mkdir -p /etc/iptables
iptables-save > /etc/iptables/rules.v4 2>/dev/null || true

# 4. Download & Install KCP Server Binary (auto-detect architecture)
echo "[4/6] Installing KCP Acceleration Server..."
KCP_VERSION="v20240108"
RAW_ARCH=$(uname -m)
case "$RAW_ARCH" in
  x86_64) KCP_ARCH="amd64" ;;
  aarch64|arm64) KCP_ARCH="arm64" ;;
  armv7l) KCP_ARCH="arm" ;;
  *) KCP_ARCH="amd64" ;;
esac

echo "Detected Architecture: $RAW_ARCH (using kcptun-$KCP_ARCH)"
DOWNLOAD_URL="https://github.com/xtaci/kcptun/releases/download/${KCP_VERSION}/kcptun-linux-${KCP_ARCH}-${KCP_VERSION}.tar.gz"

mkdir -p /opt/kcptun /etc/kcptun
cd /tmp
wget -q "$DOWNLOAD_URL" -O kcptun.tar.gz
tar -zxf kcptun.tar.gz "server_linux_${KCP_ARCH}"
mv "server_linux_${KCP_ARCH}" /opt/kcptun/kcptun-server
chmod +x /opt/kcptun/kcptun-server
rm -f kcptun.tar.gz

# 5. Generate Configuration & Systemd Service
echo "[5/6] Generating KCP configuration & systemd service..."
cat << 'EOF' > /etc/kcptun/server.json
{
  "listen": ":29900",
  "target": "127.0.0.1:1080",
  "key": "packetboost_secret",
  "crypt": "aes-128",
  "mode": "fast3",
  "mtu": 1350,
  "sndwnd": 1024,
  "rcvwnd": 1024,
  "datashard": 10,
  "parityshard": 3,
  "dscp": 46,
  "nocomp": true,
  "acknodelay": true,
  "sockbuf": 16777217,
  "keepalive": 10
}
EOF

cat << 'EOF' > /etc/systemd/system/kcptun.service
[Unit]
Description=PacketBoost High-Performance KCP Tunnel Server
After=network.target microsocks.service

[Service]
Type=simple
User=root
LimitNOFILE=65535
ExecStart=/opt/kcptun/kcptun-server -c /etc/kcptun/server.json
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now kcptun
systemctl restart kcptun

# 6. Retrieve Public IP and Display
echo "[6/6] Verifying services..."
PUB_IP=$(curl -s -4 ifconfig.me || curl -s -4 icanhazip.com || curl -s -4 api.ipify.org || echo "YOUR_VPS_IP")

echo ""
echo "======================================================================"
echo "          🎉 PACKETBOOST VPS SETUP COMPLETED SUCCESSFULLY!            "
echo "======================================================================"
echo ""
echo " Use these credentials in your PacketBoost Android App:"
echo ""
echo "   👉 Server Address : ${PUB_IP}:29900"
echo "   👉 Secret Key     : packetboost_secret"
echo ""
echo " Services Running:"
echo "   ● SOCKS5 Backend Proxy : 127.0.0.1:1080 (Active)"
echo "   ● KCP Tunnel Engine    : UDP :29900 (Active)"
echo "   ● TCP Congestion       : BBR (Enabled)"
echo "======================================================================"
echo ""
