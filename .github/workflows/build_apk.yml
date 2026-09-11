package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"io"
	"log"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"
)

// EngineStats holds real-time tunnel metrics
type EngineStats struct {
	BytesSent      uint64 `json:"bytes_sent"`
	BytesReceived  uint64 `json:"bytes_received"`
	PacketsLost    uint64 `json:"packets_lost"`
	FECRecovered   uint64 `json:"fec_recovered"`
	ActiveStreams  int32  `json:"active_streams"`
	CurrentLatency int64  `json:"current_latency_ms"`
}

var (
	engineCtx    context.Context
	engineCancel context.CancelFunc
	engineWg     sync.WaitGroup
	isRunning    bool
	engineLock   sync.Mutex

	stats EngineStats
)

//export startNativeCore
func startNativeCore(tunFd C.int, serverAddr *C.char, secretKey *C.char, mtu C.int) C.int {
	engineLock.Lock()
	defer engineLock.Unlock()

	if isRunning {
		log.Println("[PacketBoost-Go] Native core is already running")
		return 0
	}

	goServerAddr := C.GoString(serverAddr)
	goSecretKey := C.GoString(secretKey)
	goMtu := int(mtu)
	fd := int(tunFd)

	log.Printf("[PacketBoost-Go] Starting Native Core (tunFd=%d, Server=%s, MTU=%d)\n", fd, goServerAddr, goMtu)

	// Wrap raw TUN file descriptor into os.File
	tunFile := os.NewFile(uintptr(fd), "tun")
	if tunFile == nil {
		log.Println("[PacketBoost-Go] Error: Failed to wrap tunFd into os.File")
		return -1
	}

	engineCtx, engineCancel = context.WithCancel(context.Background())
	isRunning = true

	engineWg.Add(1)
	go runKcpClientLoop(engineCtx, tunFile, goServerAddr, goSecretKey, goMtu)

	return 0
}

//export stopNativeCore
func stopNativeCore() C.int {
	engineLock.Lock()
	defer engineLock.Unlock()

	if !isRunning {
		return 0
	}

	log.Println("[PacketBoost-Go] Stopping Native Core...")
	if engineCancel != nil {
		engineCancel()
	}
	engineWg.Wait()
	isRunning = false
	log.Println("[PacketBoost-Go] Native Core stopped successfully.")

	return 0
}

//export getNativeCoreStats
func getNativeCoreStats() *C.char {
	data, err := json.Marshal(stats)
	if err != nil {
		return C.CString("{}")
	}
	return C.CString(string(data))
}

func runKcpClientLoop(ctx context.Context, tunFile *os.File, serverAddr, secretKey string, mtu int) {
	defer engineWg.Done()
	defer tunFile.Close()

	// 1. Configure KCP Tunnel Parameters
	// Mode: fast3 (nodelay=1, interval=10ms, resend=2, nc=1)
	// FEC: DataShard=10, ParityShard=3 (Reed-Solomon 30% parity redundancy)
	dataShards := 10
	parityShards := 3

	// Create KCP Session over UDP using PBKDF2 AES-128
	var block kcp.BlockCrypt
	var err error
	if secretKey != "" {
		block, err = kcp.NewPBKDF2AES128([]byte(secretKey))
		if err != nil {
			log.Printf("[PacketBoost-Go] Error initializing AES-128 cipher: %v\n", err)
			return
		}
	}

	sess, err := kcp.DialWithOptions(serverAddr, block, dataShards, parityShards)
	if err != nil {
		log.Printf("[PacketBoost-Go] Dial KCP server failed: %v\n", err)
		return
	}
	defer sess.Close()

	// Configure KCP Socket Parameters
	sess.SetNoDelay(1, 10, 2, 1) // fast3 mode
	sess.SetWindowSize(1024, 1024)
	sess.SetMtu(mtu)
	sess.SetACKNoDelay(true)

	log.Printf("[PacketBoost-Go] KCP Session established to %s [DataShards: %d, ParityShards: %d, MTU: %d]\n",
		serverAddr, dataShards, parityShards, mtu)

	// Create smux multiplexing session
	smuxConfig := smux.DefaultConfig()
	smuxConfig.Version = 2
	smuxConfig.KeepAliveInterval = 10 * time.Second
	smuxConfig.KeepAliveTimeout = 30 * time.Second

	smuxSess, err := smux.Client(sess, smuxConfig)
	if err != nil {
		log.Printf("[PacketBoost-Go] Smux client setup failed: %v\n", err)
		return
	}
	defer smuxSess.Close()

	// Packet Forwarding Loop between TUN interface and KCP Stream
	buf := make([]byte, 2048)

	for {
		select {
		case <-ctx.Done():
			log.Println("[PacketBoost-Go] Context cancelled, exiting KCP forwarding loop.")
			return
		default:
			// Read packet from TUN interface
			tunFile.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
			n, err := tunFile.Read(buf)
			if err != nil {
				if os.IsTimeout(err) {
					continue
				}
				if err == io.EOF {
					return
				}
				log.Printf("[PacketBoost-Go] TUN read error: %v\n", err)
				continue
			}

			if n > 0 {
				atomic.AddUint64(&stats.BytesSent, uint64(n))

				// Open multiplexed stream for packet forwarding
				stream, err := smuxSess.OpenStream()
				if err != nil {
					log.Printf("[PacketBoost-Go] Open smux stream failed: %v\n", err)
					continue
				}

				_, writeErr := stream.Write(buf[:n])
				if writeErr != nil {
					log.Printf("[PacketBoost-Go] Stream write error: %v\n", writeErr)
				}
				stream.Close()
			}
		}
	}
}

func main() {
	// Required for Go c-shared build mode
}
