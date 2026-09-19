// fakephone 调试工具（协议 v4）：模拟手机端向 server 推送 440Hz 正弦波 PCM，
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

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	addr := flag.String("addr", "127.0.0.1:9432", "server 地址 host:port")
	fp := flag.String("fingerprint", "", "高级选项：服务端证书 SHA-256 指纹(hex)，留空自动 TOFU")
	role := flag.String("role", "phone", "角色: phone（推流）| mac（注册并统计）")
	password := flag.String("password", "", "服务器密码（phone 认证必填；mac 提供则注册/热更）")
	target := flag.String("target", "", "phone: 目标 Mac 的 device_id（留空=仅登录并 LIST）")
	deviceID := flag.String("device-id", "", "mac: 设备 ID（留空则本次随机生成）")
	deviceKey := flag.String("device-key", "", "mac: 设备凭据（留空则本次随机生成）")
	name := flag.String("name", "", "mac: 设备名（默认主机名）")
	freq := flag.Float64("freq", 440, "正弦波频率 Hz")
	flag.Parse()

	fingerprint := selfcert.NormalizeFingerprint(*fp)
	if *fp != "" && (len(fingerprint) != 64 || !isHex(fingerprint)) {
		log.Fatal("-fingerprint 必须是 64 位 hex")
	}

	conn := dialTLS(*addr, fingerprint)
	defer conn.Close()

	switch *role {
	case "phone":
		runPhone(conn, *password, *target, *freq)
	case "mac":
		id, key := *deviceID, *deviceKey
		if id == "" && key == "" {
			id, key = newDeviceIdentity()
		} else if id == "" || key == "" {
			log.Fatal("-device-id 与 -device-key 必须同时提供")
		}
		runMac(conn, id, key, *name, *password)
	default:
		log.Fatalf("未知角色 %q", *role)
	}
}

// runPhone 手机侧（v4）：密码哈希认证（target 空则登录会话，先 LIST 展示目录）
// → 建桥后按 20ms 节拍推流。
func runPhone(conn *tls.Conn, passwordRaw, target string, freq float64) {
	if passwordRaw == "" {
		log.Fatal("phone 模式必须提供 -password（服务器密码）")
	}
	authPayload, _ := json.Marshal(protocol.AuthRequest{
		Role: protocol.RolePhone, Proto: protocol.ProtoVersion,
		Secret: hashSecret(passwordRaw), Target: target,
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
		if target == "" {
			log.Printf("登录成功（登录会话），请求目录…")
			mustWrite(conn, protocol.FrameList, nil)
			lt, lp, err := protocol.ReadFrame(conn)
			if err != nil {
				log.Fatalf("读取目录失败: %v", err)
			}
			if lt != protocol.FrameList {
				log.Fatalf("意外的帧类型 0x%02x（期望 LIST）", lt)
			}
			var list protocol.ListPayload
			_ = json.Unmarshal(lp, &list)
			for _, m := range list.Macs {
				log.Printf("目录: device_id=%s name=%q online=%t busy=%t",
					m.DeviceID, m.Name, m.Online, m.Busy)
			}
			log.Printf("目录共 %d 台；用 -target <device_id> 建桥推流", len(list.Macs))
			return
		}
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

// runMac mac 侧（v4）：设备身份认证 → 注册设备名与服务器密码哈希 → 统计下行音频帧。
// 提供的 -password 即服务器密码（手机端用同一密码连入）。
func runMac(conn *tls.Conn, deviceID, deviceKey, name, passwordRaw string) {
	if name == "" {
		name, _ = os.Hostname()
	}
	authPayload, _ := json.Marshal(protocol.AuthRequest{
		Role: protocol.RoleMac, Proto: protocol.ProtoVersion,
		DeviceID: deviceID, DeviceKey: deviceKey,
	})
	mustWrite(conn, protocol.FrameAuth, authPayload)

	typ, payload, err := protocol.ReadFrame(conn)
	if err != nil {
		log.Fatalf("读取认证应答失败: %v", err)
	}
	if typ == protocol.FrameAuthErr {
		log.Fatalf("认证被拒（%s）", payload)
	}

	regPayload, _ := json.Marshal(protocol.RegisterRequest{
		Name:         name,
		PasswordHash: hashSecret(passwordRaw), // 空 password 时为空串=只改名
	})
	mustWrite(conn, protocol.FrameRegister, regPayload)
	log.Printf("mac 已注册 name=%q", name)
	if passwordRaw != "" {
		log.Printf("服务器密码已设置为手机端输入: %s", passwordRaw)
	}

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

// dialTLS 建立 TLS 连接；有指纹时固定校验，留空时首次连接自动 TOFU。
func dialTLS(addr, fingerprintHex string) *tls.Conn {
	cfg := &tls.Config{
		// 信任根来自指纹而非 CA：跳过系统校验，由下方 pinning/TOFU 决定信任。
		InsecureSkipVerify: true,
		MinVersion:         tls.VersionTLS12,
	}
	if fingerprintHex != "" {
		cfg.VerifyPeerCertificate = func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			return selfcert.VerifyRawCerts(rawCerts, fingerprintHex)
		}
	}
	c, err := tls.Dial("tcp", addr, cfg)
	if err != nil {
		log.Fatalf("TLS 连接失败: %v", err)
	}
	if fingerprintHex == "" {
		certs := c.ConnectionState().PeerCertificates
		if len(certs) == 0 {
			c.Close()
			log.Fatal("TLS 未返回服务器证书")
		}
		log.Printf("TOFU 已信任服务端证书 %s", fingerprintOf(certs[0].Raw))
	}
	return c
}

func newDeviceIdentity() (string, string) {
	idRaw := make([]byte, 16)
	keyRaw := make([]byte, 32)
	if _, err := rand.Read(idRaw); err != nil {
		log.Fatalf("生成 fake Mac 设备 ID 失败: %v", err)
	}
	if _, err := rand.Read(keyRaw); err != nil {
		log.Fatalf("生成 fake Mac 设备凭据失败: %v", err)
	}
	return "fake-mac-" + hex.EncodeToString(idRaw), hex.EncodeToString(keyRaw)
}

func fingerprintOf(der []byte) string {
	sum := sha256.Sum256(der)
	return hex.EncodeToString(sum[:])
}

func isHex(value string) bool {
	for _, c := range value {
		if !(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}

func mustWrite(conn *tls.Conn, typ byte, payload []byte) {
	if err := protocol.WriteFrame(conn, typ, payload); err != nil {
		log.Fatalf("写入失败: %v", err)
	}
}
