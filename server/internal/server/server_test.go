package server

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"remote-voice/server/internal/protocol"
	"remote-voice/server/internal/selfcert"
)

// fakeTCP 包装 net.Pipe 并记录 SetNoDelay 调用，用于 H-2 回归断言。
type fakeTCP struct {
	net.Conn
	noDelay atomic.Bool
}

func (f *fakeTCP) SetNoDelay(v bool) error {
	f.noDelay.Store(v)
	return nil
}

// 回归(H-2)：setNoDelay 必须经 NetConn() 解包 TLS 层，否则对 *tls.Conn 静默失效。
func TestSetNoDelayUnwrapsTLS(t *testing.T) {
	pipeA, pipeB := net.Pipe()
	defer pipeA.Close()
	defer pipeB.Close()
	inner := &fakeTCP{Conn: pipeA}
	wrapped := tls.Client(inner, &tls.Config{})
	setNoDelay(wrapped)
	if !inner.noDelay.Load() {
		t.Fatal("setNoDelay did not reach underlying conn through TLS wrapper")
	}
}

// ===== 测试辅助 =====

const (
	testRegKey = "test-regkey-0123456789abcdef"
)

// hashSecret 测试内复刻客户端规范化+SHA-256（与 fakephone 同规格）。
func hashSecret(t *testing.T, raw string) string {
	t.Helper()
	norm := ""
	for _, c := range raw {
		switch {
		case c == ' ' || c == '-':
			continue
		case c >= 'a' && c <= 'z':
			norm += string(c - 32)
		default:
			norm += string(c)
		}
	}
	sum := sha256.Sum256([]byte(norm))
	return hex.EncodeToString(sum[:])
}

// startTestServer 在 127.0.0.1 随机端口起一个纯 TCP 测试服务器（单元测试不涉 TLS）。
func startTestServer(t *testing.T, readTimeout, authTimeout time.Duration) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	srv := New(Config{
		RegKey:      testRegKey,
		ReadTimeout: readTimeout,
		AuthTimeout: authTimeout,
		Logger:      log.New(io.Discard, "", 0),
	})
	go srv.Serve(ln) //nolint:errcheck // 测试中忽略 accept 错误
	t.Cleanup(func() {
		srv.Close()
		ln.Close()
	})
	return ln.Addr().String()
}

func dial(t *testing.T, addr string) net.Conn {
	t.Helper()
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	t.Cleanup(func() { conn.Close() })
	return conn
}

func authPhoneReq(t *testing.T, conn net.Conn, secret string) {
	t.Helper()
	payload, _ := json.Marshal(protocol.AuthRequest{
		Role: protocol.RolePhone, Proto: protocol.ProtoVersion,
		Secret: hashSecret(t, secret),
	})
	if err := protocol.WriteFrame(conn, protocol.FrameAuth, payload); err != nil {
		t.Fatalf("write AUTH(phone): %v", err)
	}
}

func authMacReq(t *testing.T, conn net.Conn, regkey string) {
	t.Helper()
	payload, _ := json.Marshal(protocol.AuthRequest{
		Role: protocol.RoleMac, Proto: protocol.ProtoVersion, Key: regkey,
	})
	if err := protocol.WriteFrame(conn, protocol.FrameAuth, payload); err != nil {
		t.Fatalf("write AUTH(mac): %v", err)
	}
}

func registerReq(t *testing.T, conn net.Conn, name, perm, temp string, tempExp int64) {
	t.Helper()
	payload, _ := json.Marshal(protocol.RegisterRequest{
		Name: name, Perm: perm, Temp: temp, TempExp: tempExp,
	})
	if err := protocol.WriteFrame(conn, protocol.FrameRegister, payload); err != nil {
		t.Fatalf("write REGISTER: %v", err)
	}
}

// expectFrame 读到指定类型帧并返回 payload；读到 AUTH_ERR 时以错误信息失败。
func expectFrame(t *testing.T, conn net.Conn, want byte, timeout time.Duration) []byte {
	t.Helper()
	conn.SetReadDeadline(time.Now().Add(timeout))
	for {
		typ, payload, err := protocol.ReadFrame(conn)
		if err != nil {
			t.Fatalf("expect frame 0x%02x: %v", want, err)
		}
		switch typ {
		case protocol.FrameAuthErr:
			t.Fatalf("server rejected: %s", payload)
		case want:
			return payload
		}
	}
}

// expectAuthErr 读取并校验 AUTH_ERR 帧，返回原因码 payload。
func expectAuthErr(t *testing.T, conn net.Conn) []byte {
	t.Helper()
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	typ, payload, err := protocol.ReadFrame(conn)
	if err != nil {
		t.Fatalf("expect AUTH_ERR: %v", err)
	}
	if typ != protocol.FrameAuthErr {
		t.Fatalf("want AUTH_ERR, got 0x%02x", typ)
	}
	return payload
}

// mustRegMac 连接并完成 Mac 认证+REGISTER（永久秘密 + 有效临时秘密）。
// 返回 Mac 连接与两份注册内容，供手机侧引用。
func mustRegMac(t *testing.T, addr, name, perm, temp string, tempExp int64) (net.Conn, string, string) {
	t.Helper()
	mac := dial(t, addr)
	authMacReq(t, mac, testRegKey)
	expectFrame(t, mac, protocol.FrameAuthOK, 2*time.Second)
	ph, th := "", ""
	if perm != "" {
		ph = hashSecret(t, perm)
	}
	if temp != "" {
		th = hashSecret(t, temp)
	}
	registerReq(t, mac, name, ph, th, tempExp)
	return mac, ph, th
}

// mustAuthPhone 手机认证成功（携带与已在线的 Mac 匹配的秘密）。
// 返回手机连接与 AUTH_OK payload。
func mustAuthPhone(t *testing.T, addr, secret string) (net.Conn, []byte) {
	t.Helper()
	phone := dial(t, addr)
	authPhoneReq(t, phone, secret)
	okPayload := expectFrame(t, phone, protocol.FrameAuthOK, 3*time.Second)
	expectFrame(t, phone, protocol.FramePeerState, 3*time.Second)
	return phone, okPayload
}

// ===== 认证 =====

func TestMacBadRegkeyRejected(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	conn := dial(t, addr)
	authMacReq(t, conn, "wrong-key")
	payload := expectAuthErr(t, conn)
	if !strings.Contains(string(payload), protocol.ReasonInvalidKey) {
		t.Fatalf("want %q in error, got %q", protocol.ReasonInvalidKey, payload)
	}
	// 服务器随后关闭连接
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := protocol.ReadFrame(conn); err == nil {
		t.Fatal("connection should be closed after AUTH_ERR")
	}
}

func TestPhoneWrongSecretRejected(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	conn := dial(t, addr)
	authPhoneReq(t, conn, "wrong-secret")
	payload := expectAuthErr(t, conn)
	if !strings.Contains(string(payload), protocol.ReasonInvalidSecret) {
		t.Fatalf("want %q in error, got %q", protocol.ReasonInvalidSecret, payload)
	}
}

func TestPhoneBadHashFormatRejected(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	mac, _, _ := mustRegMac(t, addr, "MacBook", "", "ABC-123", 0)
	_ = mac
	conn := dial(t, addr)
	payload, _ := json.Marshal(protocol.AuthRequest{
		Role: protocol.RolePhone, Proto: protocol.ProtoVersion, Secret: "not-a-valid-hash",
	})
	if err := protocol.WriteFrame(conn, protocol.FrameAuth, payload); err != nil {
		t.Fatalf("write AUTH: %v", err)
	}
	reason := expectAuthErr(t, conn)
	if !strings.Contains(string(reason), protocol.ReasonInvalidSecret) {
		t.Fatalf("want %q, got %q", protocol.ReasonInvalidSecret, reason)
	}
}

func TestPhoneExpiredSecretRejected(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	// 注册一个 500ms 后过期的临时秘密，然后等待其过期再认证
	mac, _, _ := mustRegMac(t, addr, "MacBook", "", "EXP-90", time.Now().Add(2*time.Second).Unix())
	_ = mac
	time.Sleep(2500 * time.Millisecond)
	conn := dial(t, addr)
	authPhoneReq(t, conn, "EXP-90")
	payload := expectAuthErr(t, conn)
	if !strings.Contains(string(payload), protocol.ReasonSecretExpired) {
		t.Fatalf("want %q in error, got %q", protocol.ReasonSecretExpired, payload)
	}
}

func TestPhoneAuthWhenMacOfflineRejected(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	mac, _, _ := mustRegMac(t, addr, "MacBook", "", "OFF-11", time.Now().Add(time.Hour).Unix())
	mac.Close() // Mac 断开 → 注册表应被清理
	time.Sleep(150 * time.Millisecond)

	conn := dial(t, addr)
	authPhoneReq(t, conn, "OFF-11")
	payload := expectAuthErr(t, conn)
	if !strings.Contains(string(payload), protocol.ReasonInvalidSecret) {
		t.Fatalf("after mac offline want %q, got %q", protocol.ReasonInvalidSecret, payload)
	}
}

// ===== 桥接 =====

func TestBridgeFullDuplex(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	mac, _, _ := mustRegMac(t, addr, "MacBook-Pro", "PERM-42", "TEMP-17",
		time.Now().Add(time.Hour).Unix())

	// 用临时秘密认证：AUTH_OK 应回传设备名
	phone, okPayload := mustAuthPhone(t, addr, "TEMP-17")
	var ok protocol.AuthOKPayload
	if err := json.Unmarshal(okPayload, &ok); err != nil || ok.Mac != "MacBook-Pro" {
		t.Fatalf("want mac name in AUTH_OK, got %q err=%v", ok.Mac, err)
	}
	// Mac 侧收到 PEER_STATE(online)
	expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)

	// phone → mac 透传
	frameA := make([]byte, 1920)
	frameA[0] = 0x12
	if err := protocol.WriteFrame(phone, protocol.FrameAudio, frameA); err != nil {
		t.Fatalf("phone send audio: %v", err)
	}
	gotA := expectFrame(t, mac, protocol.FrameAudio, 2*time.Second)
	if string(gotA) != string(frameA) {
		t.Fatal("phone→mac payload mismatch")
	}

	// mac → phone 透传
	frameB := []byte{0x99, 0x88}
	if err := protocol.WriteFrame(mac, protocol.FrameAudio, frameB); err != nil {
		t.Fatalf("mac send audio: %v", err)
	}
	gotB := expectFrame(t, phone, protocol.FrameAudio, 2*time.Second)
	if string(gotB) != string(frameB) {
		t.Fatal("mac→phone payload mismatch")
	}

	// 用永久秘密认证另一台手机 → 另一台 Mac 设备也能建立桥接（多 Mac 并存）
	mac2, permHash2, _ := mustRegMac(t, addr, "Studio-Mac", "PERM-99", "", 0)
	_ = permHash2
	phone2, ok2 := mustAuthPhone(t, addr, "PERM-99")
	var ok2v protocol.AuthOKPayload
	_ = json.Unmarshal(ok2, &ok2v)
	if ok2v.Mac != "Studio-Mac" {
		t.Fatalf("second mac name wrong: %q", ok2v.Mac)
	}
	expectFrame(t, mac2, protocol.FramePeerState, 2*time.Second)
	if err := protocol.WriteFrame(phone2, protocol.FrameAudio, []byte{0x7f}); err != nil {
		t.Fatalf("phone2 send: %v", err)
	}
	if got := expectFrame(t, mac2, protocol.FrameAudio, 2*time.Second); got[0] != 0x7f {
		t.Fatal("phone2→mac2 mismatch")
	}
}

func TestPeerBusyRejected(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	mac, _, _ := mustRegMac(t, addr, "MacBook", "", "BUSY-1", time.Now().Add(time.Hour).Unix())
	_ = mac
	phone1, _ := mustAuthPhone(t, addr, "BUSY-1")
	expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)

	// 第二台手机携带同一秘密 → peer-busy
	phone2 := dial(t, addr)
	authPhoneReq(t, phone2, "BUSY-1")
	payload := expectAuthErr(t, phone2)
	if !strings.Contains(string(payload), protocol.ReasonPeerBusy) {
		t.Fatalf("want %q, got %q", protocol.ReasonPeerBusy, payload)
	}
	// 既有桥接不受影响
	if err := protocol.WriteFrame(phone1, protocol.FrameAudio, []byte{1, 2}); err != nil {
		t.Fatal(err)
	}
	expectFrame(t, mac, protocol.FrameAudio, 2*time.Second)
}

func TestBridgeDissolutionOnDisconnect(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	mac, _, th := mustRegMac(t, addr, "MacBook", "", "DRAW-1", time.Now().Add(time.Hour).Unix())
	phone, _ := mustAuthPhone(t, addr, "DRAW-1")
	expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)

	// phone 掉线 → mac 收 offline
	phone.Close()
	offline := expectFrame(t, mac, protocol.FramePeerState, 3*time.Second)
	if len(offline) != 1 || offline[0] != protocol.PeerOffline {
		t.Fatalf("want PeerOffline, got %v", offline)
	}
	// mac 还能重振：新手机再次连接成功
	phone2, _ := mustAuthPhone(t, addr, "DRAW-1")
	expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)
	_ = th
	phone2.Close()
	time.Sleep(100 * time.Millisecond)
}

func TestRegistersLiveUpdate(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	mac := dial(t, addr)
	authMacReq(t, mac, testRegKey)
	expectFrame(t, mac, protocol.FrameAuthOK, 2*time.Second)

	// 初始注册临时秘密 V1
	registerReq(t, mac, "MacBook", "", hashSecret(t, "V1-AAAA"), time.Now().Add(time.Hour).Unix())
	ph1, _ := mustAuthPhone(t, addr, "V1-AAAA")
	expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)
	ph1.Close()
	expectFrame(t, mac, protocol.FramePeerState, 3*time.Second)

	// 热更替换为 V2：旧秘密立即失效
	registerReq(t, mac, "MacBook", "", hashSecret(t, "V2-BBBB"), time.Now().Add(time.Hour).Unix())
	conn := dial(t, addr)
	authPhoneReq(t, conn, "V1-AAAA")
	payload := expectAuthErr(t, conn)
	if !strings.Contains(string(payload), protocol.ReasonInvalidSecret) {
		t.Fatalf("old secret should be invalid after live update, got %q", payload)
	}
	ph2, _ := mustAuthPhone(t, addr, "V2-BBBB")
	expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)
	ph2.Close()
}

func TestAuthOKEventDeliveredToMac(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	mac, _, _ := mustRegMac(t, addr, "MacBook", "", "EVT-1", time.Now().Add(time.Hour).Unix())
	phone, _ := mustAuthPhone(t, addr, "EVT-1")
	_ = phone
	ev := expectFrame(t, mac, protocol.FrameEvent, 2*time.Second)
	var n protocol.EventNotify
	if err := json.Unmarshal(ev, &n); err != nil {
		t.Fatalf("EVENT unmarshal: %v", err)
	}
	if n.Event != protocol.EventAuthOK || n.Kind != protocol.SecretKindTemp {
		t.Fatalf("want auth-ok/temp, got %+v", n)
	}
	if !strings.Contains(n.IP, "127.0.0.1") {
		t.Fatalf("want client ip, got %q", n.IP)
	}
	// ph 掉线以解除桥，避免后续清理竞态
	phone.Close()
}

func TestExpiredAttemptEventDeliveredToMac(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	mac, _, _ := mustRegMac(t, addr, "MacBook", "", "EXP-EE", time.Now().Add(2*time.Second).Unix())
	time.Sleep(2500 * time.Millisecond)
	conn := dial(t, addr)
	authPhoneReq(t, conn, "EXP-EE")
	expectAuthErr(t, conn)
	ev := expectFrame(t, mac, protocol.FrameEvent, 2*time.Second)
	var n protocol.EventNotify
	if err := json.Unmarshal(ev, &n); err != nil {
		t.Fatalf("EVENT unmarshal: %v", err)
	}
	if n.Event != protocol.EventAuthFail || n.Reason != protocol.ReasonSecretExpired {
		t.Fatalf("want auth-fail/secret-expired, got %+v", n)
	}
}

// ===== 防爆破限速 =====

func TestRateLimitLocksIP(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	// 连续 5 次错误秘密：第 5 次触发锁定（依旧是 invalid-secret 应答）
	for i := 0; i < 5; i++ {
		conn := dial(t, addr)
		authPhoneReq(t, conn, "WRONG-X")
		payload := expectAuthErr(t, conn)
		if !strings.Contains(string(payload), protocol.ReasonInvalidSecret) {
			t.Fatalf("attempt %d want %q, got %q", i+1, protocol.ReasonInvalidSecret, payload)
		}
		conn.Close()
	}
	// 第 6 次：立即 rate-limited（无需再等滑窗）
	conn6 := dial(t, addr)
	authPhoneReq(t, conn6, "WRONG-X")
	payload6 := expectAuthErr(t, conn6)
	if !strings.Contains(string(payload6), protocol.ReasonRateLimited) {
		t.Fatalf("want %q after lock, got %q", protocol.ReasonRateLimited, payload6)
	}
	// 锁定期间即使正确秘密也被拒绝（恒定排除）
	mac, _, _ := mustRegMac(t, addr, "MacBook", "", "GOOD-1", time.Now().Add(time.Hour).Unix())
	_ = mac
	conn7 := dial(t, addr)
	authPhoneReq(t, conn7, "GOOD-1")
	payload7 := expectAuthErr(t, conn7)
	if !strings.Contains(string(payload7), protocol.ReasonRateLimited) {
		t.Fatalf("locked IP must reject valid secret, got %q", payload7)
	}
}

func TestRateLimitEscalatesAndClearsOnSuccess(t *testing.T) {
	// 纯单元测试：锁定后过期复位、逐级递增（1m→5m→30m）、成功清零。
	rl := newRateLimiter()
	now := time.Now()
	ip := "10.0.0.9"

	for i := 0; i < 5; i++ {
		rl.fail(ip, now)
	}
	if !rl.locked(ip, now) {
		t.Fatal("after 5 fails within window, ip should be locked")
	}
	// 第一次锁定 1m：+30s 仍锁，+90s 应已过期复位
	if !rl.locked(ip, now.Add(30*time.Second)) {
		t.Fatal("1m 锁内 must stay locked")
	}
	if rl.locked(ip, now.Add(90*time.Second)) {
		t.Fatal("lock expired should clear")
	}
	// 二级：锁复位后再次窗口失败 5 次 → 新锁由 level(1)→2，即 5m 锁
	base := now.Add(90 * time.Second)
	for i := 0; i < 5; i++ {
		rl.fail(ip, base)
	}
	if !rl.locked(ip, base.Add(3 * time.Minute)) {
		t.Fatal("2nd lock should be 5m, still locked at +3m")
	}
	if rl.locked(ip, base.Add(6 * time.Minute)) {
		t.Fatal("2nd lock expired at +5m")
	}
	// 三级（封顶 30m）：再一轮窗口失败
	base2 := base.Add(6 * time.Minute)
	for i := 0; i < 5; i++ {
		rl.fail(ip, base2)
	}
	if !rl.locked(ip, base2.Add(10 * time.Minute)) {
		t.Fatal("3rd lock should be 30m, still locked at +10m")
	}
	if rl.locked(ip, base2.Add(31 * time.Minute)) {
		t.Fatal("3rd lock max 30m")
	}
	// 成功后清零
	rl.clear(ip)
	if rl.locked(ip, base2) {
		t.Fatal("success clear should reset")
	}
}

// ===== 心跳 / 通用 =====

func TestHeartbeatPingPongKeepsAlive(t *testing.T) {
	addr := startTestServer(t, 300*time.Millisecond, 2*time.Second)
	phone := mustAuthPhoneOK(t, addr)
	deadline := time.Now().Add(1500 * time.Millisecond)
	for time.Now().Before(deadline) {
		if err := protocol.WriteFrame(phone, protocol.FramePing, nil); err != nil {
			t.Fatalf("ping write: %v", err)
		}
		phone.SetReadDeadline(time.Now().Add(time.Second))
		typ, _, err := protocol.ReadFrame(phone)
		if err != nil {
			t.Fatalf("connection died despite pings: %v", err)
		}
		if typ != protocol.FramePong {
			t.Fatalf("want PONG, got 0x%02x", typ)
		}
		time.Sleep(100 * time.Millisecond)
	}
}

// mustAuthPhoneOK 造一台在线的 Mac（注册临时秘密 KEEP-1）后以手机认证成功，
// 并消费掉 PEER_STATE(online)，返回可直接操作的手机连接。
// Mac 连接由后台 goroutine 持续 PING 保活，防止短读超时下 Mac 会话先死
// 导致手机误收 offline 通知。
func mustAuthPhoneOK(t *testing.T, addr string) net.Conn {
	t.Helper()
	mac := dial(t, addr)
	authMacReq(t, mac, testRegKey)
	expectFrame(t, mac, protocol.FrameAuthOK, 2*time.Second)
	registerReq(t, mac, "K", "", hashSecret(t, "KEEP-1"), time.Now().Add(time.Hour).Unix())
	go func() {
		for {
			if err := protocol.WriteFrame(mac, protocol.FramePing, nil); err != nil {
				return // 连接已结束
			}
			time.Sleep(100 * time.Millisecond)
		}
	}()
	phone := dial(t, addr)
	authPhoneReq(t, phone, "KEEP-1")
	ok := expectFrame(t, phone, protocol.FrameAuthOK, 3*time.Second)
	if len(ok) == 0 {
		t.Fatal("AUTH_OK payload should carry mac")
	}
	expectFrame(t, phone, protocol.FramePeerState, 3*time.Second)
	return phone
}

func TestHeartbeatTimeoutDisconnects(t *testing.T) {
	addr := startTestServer(t, 300*time.Millisecond, 2*time.Second)
	phone := mustAuthPhoneOK(t, addr)
	phone.SetReadDeadline(time.Now().Add(3 * time.Second))
	start := time.Now()
	if _, _, err := protocol.ReadFrame(phone); err == nil {
		t.Fatal("expect disconnect on heartbeat timeout")
	}
	if elapsed := time.Since(start); elapsed > 2500*time.Millisecond {
		t.Fatalf("disconnect too late: %v", elapsed)
	}
}

func TestAuthTimeoutClosesConnection(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 150*time.Millisecond)
	conn := dial(t, addr) // 连上但不发 AUTH
	start := time.Now()
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, _, err := protocol.ReadFrame(conn); err == nil {
		t.Fatal("expect close on auth timeout")
	} else if elapsed := time.Since(start); elapsed > 2500*time.Millisecond {
		t.Fatalf("auth timeout too late: %v", elapsed)
	}
}

func TestUnknownFrameTypeDropped(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	phone := mustAuthPhoneOK(t, addr)
	// 发送未知类型帧，连接应继续工作（向前兼容）
	if err := protocol.WriteFrame(phone, 0x7F, []byte("whatever")); err != nil {
		t.Fatalf("write unknown frame: %v", err)
	}
	if err := protocol.WriteFrame(phone, protocol.FramePing, nil); err != nil {
		t.Fatalf("write ping: %v", err)
	}
	phone.SetReadDeadline(time.Now().Add(2 * time.Second))
	typ, _, err := protocol.ReadFrame(phone)
	if err != nil || typ != protocol.FramePong {
		t.Fatalf("conn should survive unknown frame and reply PONG, got typ=0x%02x err=%v", typ, err)
	}
}

// ===== TLS 集成 =====

// syncBuf 并发安全的日志缓冲：服务器 goroutine 写、测试主线程读。
type syncBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (w *syncBuf) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.b.Write(p)
}

func (w *syncBuf) String() string {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.b.String()
}

// 回归(PLAN-003)：TLS 握手失败绝不能杀死服务器——旧实现握手发生在
// tls.Listener.Accept 内，单个失败（如客户端指纹不符主动断开、扫描器探针）
// 会沿 Accept 错误路径导致整个进程退出。现握手移入每连接 goroutine。
func TestTLSHandshakeFailureDoesNotKillServer(t *testing.T) {
	dir := t.TempDir()
	cert, fp, err := selfcert.Ensure(dir)
	if err != nil {
		t.Fatalf("ensure cert: %v", err)
	}
	var logBuf syncBuf
	srv := New(Config{
		RegKey:    testRegKey,
		TlsConfig: &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12},
		Logger:    log.New(&logBuf, "", 0),
	})
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	go srv.Serve(ln) //nolint:errcheck // 测试中忽略 accept 错误
	t.Cleanup(func() { srv.Close(); ln.Close() })
	addr := ln.Addr().String()

	// 攻击者：裸 TCP 发 HTTP 探针后立刻断开（等价于指纹不符的客户端中断）
	bad, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("attacker dial: %v", err)
	}
	_, _ = bad.Write([]byte("GET / HTTP/1.0\r\n\r\n"))
	bad.Close()
	time.Sleep(300 * time.Millisecond) // 等待握手 goroutine 处理完毕

	if !strings.Contains(logBuf.String(), "tls handshake failed") {
		t.Fatalf("handshake failure must be logged, got:\n%s", logBuf.String())
	}

	// 服务器必须仍然存活：合法 TLS 客户端（指纹 pinning，同产品实现）完成双端认证与桥接
	dialTLS := func() net.Conn {
		c, err := tls.Dial("tcp", addr, &tls.Config{
			InsecureSkipVerify: true, // noqa：身份由下方指纹校验保证，非跳过校验
			VerifyPeerCertificate: func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
				return selfcert.VerifyRawCerts(rawCerts, fp)
			},
		})
		if err != nil {
			t.Fatalf("tls dial after attack (server died?): %v", err)
		}
		t.Cleanup(func() { c.Close() })
		return c
	}
	mac := dialTLS()
	authMacReq(t, mac, testRegKey)
	expectFrame(t, mac, protocol.FrameAuthOK, 2*time.Second)
	registerReq(t, mac, "MacBook", "", hashSecret(t, "TLS-1"), time.Now().Add(time.Hour).Unix())

	phone := dialTLS()
	authPhoneReq(t, phone, "TLS-1")
	expectFrame(t, phone, protocol.FrameAuthOK, 2*time.Second)
	expectFrame(t, phone, protocol.FramePeerState, 2*time.Second)
	expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)
}
