// fakephone 调试工具（协议 v2）：模拟手机端向 relay 推送 440Hz 正弦波 PCM，
// 或以 mac 身份连接注册并统计下行帧数，用于没有真机时的全链路联调。
package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"flag"
	"log"
	"math"
	"os"
	"os/signal"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"remote-voice/server/internal/protocol"
	"remote-voice/server/internal/selfcert"
)

const (
	sampleRate = 48000
	frameMs    = 20
	frameBytes = sampleRate / 1000 * frameMs * 2 // 960 样本 × 2 字节 = 1920
)

// 与 Mac 客户端一致的秘密字母表（剔除易混字符 0O1lI）
const secretAlphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	addr := flag.String("addr", "127.0.0.1:9432", "server 地址 host:port")
	fp := flag.String("fingerprint", "", "服务端证书 SHA-256 指纹(hex)")
	role := flag.String("role", "phone", "角色: phone（推流）| mac（注册并统计）")
	secret := flag.String("secret", "", "phone: 秘密原文（或临时秘密 4+4 短码）")
	regkey := flag.String("regkey", "", "mac: 注册密钥")
	name := flag.String("name", "", "mac: 设备名（默认主机名）")
	freq := flag.Float64("freq", 440, "正弦波频率 Hz")
	flag.Parse()

	if selfcert.NormalizeFingerprint(*fp) == "" {
		log.Fatal("必须提供 -fingerprint")
	}

	conn := dialTLS(*addr, selfcert.NormalizeFingerprint(*fp))
	defer conn.Close()

	switch *role {
	case "phone":
		runPhone(conn, *secret, *freq)
	case "mac":
		runMac(conn, *regkey, *name)
	default:
		log.Fatalf("未知角色 %q", *role)
	}
}

// runPhone 手机侧：认证（秘密哈希）→ 桥接后按 20ms 节拍推流。
func runPhone(conn *tls.Conn, secretRaw string, freq float64) {
	if secretRaw == "" {
		log.Fatal("phone 模式必须提供 -secret")
	}
	authPayload, _ := json.Marshal(protocol.AuthRequest{
		Role: protocol.RolePhone, Proto: protocol.ProtoVersion,
		Secret: hashSecret(secretRaw),
	})
	mustWrite(conn, protocol.FrameAuth, authPayload)

	typ, payload, err := protocol.ReadFrame(conn)
	if err != nil {
		log.Fatalf("读取认证应答失败: %v", err)
	}
	switch typ {
	case protocol.FrameAuthErr:
		log.Fatalf("认证被拒（%s）", payload)
	case protocol.FrameAuthOK:
		var ok protocol.AuthOKPayload
		_ = json.Unmarshal(payload, &ok)
		log.Printf("认证成功，对端设备: %q，等待上线…", ok.Mac)
	default:
		log.Fatalf("意外的帧类型 0x%02x", typ)
	}

	var streaming atomic.Bool
	done := make(chan struct{})
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
			phase += step * freq
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

// runMac mac 侧：regkey 认证 → 生成临时秘密并注册 → 统计下行音频帧。
// 生成的临时秘密打印在日志里，供手机侧用同一秘密连入。
func runMac(conn *tls.Conn, regkey, name string) {
	if regkey == "" {
		log.Fatal("mac 模式必须提供 -regkey")
	}
	if name == "" {
		name, _ = os.Hostname()
	}
	authPayload, _ := json.Marshal(protocol.AuthRequest{
		Role: protocol.RoleMac, Proto: protocol.ProtoVersion, Key: regkey,
	})
	mustWrite(conn, protocol.FrameAuth, authPayload)

	typ, payload, err := protocol.ReadFrame(conn)
	if err != nil {
		log.Fatalf("读取认证应答失败: %v", err)
	}
	if typ == protocol.FrameAuthErr {
		log.Fatalf("认证被拒（%s）", payload)
	}

	tempSecret := newTempSecret()
	regPayload, _ := json.Marshal(protocol.RegisterRequest{
		Name: name, Temp: hashSecret(tempSecret), TempExp: time.Now().Add(8 * time.Hour).Unix(),
	})
	mustWrite(conn, protocol.FrameRegister, regPayload)
	log.Printf("mac 已注册 name=%q", name)
	log.Printf("临时秘密（手机端输入）: %s", tempSecret)

	var inFrames, lastFrames atomic.Uint64
	lastReport := time.Now()
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	for {
		typ, payload, err := protocol.ReadFrame(conn)
		if err != nil {
			log.Printf("连接结束: %v", err)
			return
		}
		switch typ {
		case protocol.FrameAudio:
			inFrames.Add(1)
			_ = payload
		case protocol.FramePeerState:
			if len(payload) == 1 && payload[0] == protocol.PeerOnline {
				log.Println("对端上线")
			} else {
				log.Println("对端离线")
			}
		case protocol.FrameEvent:
			var ev protocol.EventNotify
			if json.Unmarshal(payload, &ev) == nil {
				log.Printf("事件: %s ip=%s kind=%s reason=%s", ev.Event, ev.IP, ev.Kind, ev.Reason)
			}
		case protocol.FramePing:
			mustWrite(conn, protocol.FramePong, nil)
		}
		if lf := inFrames.Load(); time.Since(lastReport) >= 5*time.Second {
			log.Printf("收到 %d 帧（近期 %d）", lf, lf-lastFrames.Load())
			lastFrames.Store(lf)
			lastReport = time.Now()
		}
		select {
		case <-sig:
			log.Printf("退出，共收到 %d 帧", inFrames.Load())
			return
		default:
		}
	}
}

// newTempSecret 生成 4+4 位 base32 短码（与 Mac 客户端同规格）。
func newTempSecret() string {
	buf := make([]byte, 8)
	if _, err := rand.Read(buf); err != nil {
		panic(err)
	}
	var s []byte
	for i := 0; i < 4; i++ {
		s = append(s, secretAlphabet[int(buf[i*2])%len(secretAlphabet)])
	}
	s = append(s, '-')
	for i := 0; i < 4; i++ {
		s = append(s, secretAlphabet[int(buf[i*2+1])%len(secretAlphabet)])
	}
	return string(s)
}

// sha256Hex 计算字符串的 SHA-256 十六进制摘要。
func sha256Hex(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}

// hashSecret 规范化（去空白/连字符 + 大写）后 SHA-256 hex，与客户端一致。
func hashSecret(raw string) string {
	var b strings.Builder
	for _, c := range raw {
		switch {
		case c == ' ' || c == '-':
			continue
		case c >= 'a' && c <= 'z':
			b.WriteRune(c - 32)
		default:
			b.WriteRune(c)
		}
	}
	return sha256Hex(b.String())
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
