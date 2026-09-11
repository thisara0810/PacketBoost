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
echo "[1/5] Installing prerequisite packages..."
apt-get update -qq
apt-get install -y -qq wget curl jq iptables iptables-persistent ca-certificates

# 2. Configure sysctl Kernel Parameters for Google BBR v3 & TCP Buffers
echo "[2/5] Optimizing Linux kernel network stack for BBR & low bufferbloat..."

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

sysctl --system

# Verify BBR Activation
ACTIVE_CC=$(sysctl net.ipv4.tcp_congestion_control | awk '{print $3}')
echo "Active TCP Congestion Control: $ACTIVE_CC"

# 3. Setup NAT Packet Forwarding via iptables
echo "[3/5] Setting up iptables NAT forwarding..."
DEFAULT_IFACE=$(ip route show default | awk '/default/ {print $5}' | head -n1)
echo "Default Network Interface: $DEFAULT_IFACE"

iptables -t nat -A POSTROUTING -o "$DEFAULT_IFACE" -j MASQUERADE
iptables-save > /etc/iptables/rules.v4

# 4. Download & Install KCP Server Binary
echo "[4/5] Installing KCP Acceleration Server..."
KCP_VERSION="v20240108"
KCP_ARCH="amd64"
DOWNLOAD_URL="https://github.com/xtaci/kcptun/releases/download/${KCP_VERSION}/kcptun-linux-${KCP_ARCH}-${KCP_VERSION}.tar.gz"

mkdir -p /opt/kcptun /etc/kcptun
cd /tmp
wget -q "$DOWNLOAD_URL" -O kcptun.tar.gz
tar -zxf kcptun.tar.gz server_linux_amd64
mv server_linux_amd64 /opt/kcptun/kcptun-server
chmod +x /opt/kcptun/kcptun-server
rm -f kcptun.tar.gz

# 5. Copy Configuration & Systemd Service
echo "[5/5] Configuring systemd service unit..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -f "$SCRIPT_DIR/server.json" ]; then
    cp "$SCRIPT_DIR/server.json" /etc/kcptun/server.json
else
    echo "Warning: server.json not found in script dir. Make sure to place /etc/kcptun/server.json!"
fi

if [ -f "$SCRIPT_DIR/kcptun.service" ]; then
    cp "$SCRIPT_DIR/kcptun.service" /etc/systemd/system/kcptun.service
    systemctl daemon-reload
    systemctl enable kcptun
    systemctl restart kcptun
    echo "kcptun service started successfully!"
fi

echo "======================================================================"
echo " PacketBoost VPS Relay Setup Complete!                                "
echo " Server listening on UDP Port :29900                                 "
echo "======================================================================"
