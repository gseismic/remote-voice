// fakephone 调试工具：模拟手机端向 relay 推送 440Hz 正弦波 PCM，
// 用于在没有真机的情况下联调"服务器 → Mac 接收器 → BlackHole"链路。
package main

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"flag"
	"log"
	"math"
	"os"
	"os/signal"
	"sync/atomic"
	"syscall"
	"time"

	"remote-voice/relay/internal/protocol"
	"remote-voice/relay/internal/selfcert"
)

const (
	sampleRate = 48000
	frameMs    = 20
	frameBytes = sampleRate / 1000 * frameMs * 2 // 960 样本 × 2 字节 = 1920
)

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	addr := flag.String("addr", "127.0.0.1:9432", "relay 地址 host:port")
	token := flag.String("token", "", "预共享 token")
	fp := flag.String("fingerprint", "", "服务端证书 SHA-256 指纹(hex)")
	freq := flag.Float64("freq", 440, "正弦波频率 Hz")
	flag.Parse()

	if *token == "" || selfcert.NormalizeFingerprint(*fp) == "" {
		log.Fatal("必须提供 -token 与 -fingerprint")
	}

	conn := dialTLS(*addr, selfcert.NormalizeFingerprint(*fp))
	defer conn.Close()

	authPayload, _ := json.Marshal(protocol.AuthRequest{
		Role: protocol.RolePhone, Token: *token, Proto: protocol.ProtoVersion,
	})
	mustWrite(conn, protocol.FrameAuth, authPayload)

	typ, payload, err := protocol.ReadFrame(conn)
	if err != nil {
		log.Fatalf("读取认证应答失败: %v", err)
	}
	switch typ {
	case protocol.FrameAuthErr:
		log.Fatalf("认证被拒: %s", payload)
	case protocol.FrameAuthOK:
		log.Println("认证成功，等待对端(mac)上线…")
	default:
		log.Fatalf("意外的帧类型 0x%02x", typ)
	}

	var streaming atomic.Bool
	done := make(chan struct{})

	// 读循环：处理 PEER_STATE / PING，未知类型丢弃
	go func() {
		defer close(done)
		for {
			typ, payload, err := protocol.ReadFrame(conn)
			if err != nil {
				log.Printf("连接结束: %v", err)
				return
			}
			switch typ {
			case protocol.FramePeerState:
				if len(payload) == 1 && payload[0] == protocol.PeerOnline {
					log.Println("对端已上线，开始推流")
					streaming.Store(true)
				} else {
					log.Println("对端已掉线，暂停推流")
					streaming.Store(false)
				}
			case protocol.FramePing:
				mustWrite(conn, protocol.FramePong, nil)
			}
		}
	}()

	// 发送循环：恒定 20ms 节拍生成并发送一帧
	ticker := time.NewTicker(frameMs * time.Millisecond)
	defer ticker.Stop()
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)

	var phase float64
	const step = 2 * math.Pi * 1 / float64(sampleRate)
	sent := uint64(0)
	lastCount := uint64(0)
	lastReport := time.Now()

	for {
		select {
		case <-done:
			return
		case <-sig:
			log.Printf("退出，共发送 %d 帧", sent)
			return
		case <-ticker.C:
		}
		if !streaming.Load() {
			continue
		}
		frame := make([]byte, frameBytes)
		for i := 0; i < frameBytes/2; i++ {
			v := int16(8000 * math.Sin(phase))
			frame[2*i] = byte(v)
			frame[2*i+1] = byte(v >> 8)
			phase += step * *freq
			if phase > 2*math.Pi {
				phase -= 2 * math.Pi
			}
		}
		if err := protocol.WriteFrame(conn, protocol.FrameAudio, frame); err != nil {
			log.Printf("发送失败: %v", err)
			return
		}
		sent++
		if time.Since(lastReport) >= 5*time.Second {
			elapsed := time.Since(lastReport).Seconds()
			log.Printf("已发送 %d 帧（近期 %.1f 帧/秒）", sent,
				float64(sent-lastCount)/elapsed)
			lastReport, lastCount = time.Now(), sent
		}
	}
}

// dialTLS 建立带证书指纹固定的 TLS 连接（与真实客户端同语义）。
func dialTLS(addr, fingerprintHex string) *tls.Conn {
	cfg := &tls.Config{
		// 信任根来自指纹而非 CA：跳过系统校验，由 VerifyPeerCertificate 执行 pinning
		InsecureSkipVerify: true,
		VerifyPeerCertificate: func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			return selfcert.VerifyRawCerts(rawCerts, fingerprintHex)
		},
		MinVersion: tls.VersionTLS12,
	}
	c, err := tls.Dial("tcp", addr, cfg)
	if err != nil {
		log.Fatalf("TLS 连接失败: %v", err)
	}
	return c
}

func mustWrite(conn *tls.Conn, typ byte, payload []byte) {
	if err := protocol.WriteFrame(conn, typ, payload); err != nil {
		log.Fatalf("写入失败: %v", err)
	}
}
