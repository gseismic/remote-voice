package server

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"encoding/json"
	"io"
	"log"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"remote-voice/relay/internal/protocol"
	"remote-voice/relay/internal/selfcert"
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

// 回归(H-1)：authenticate 必须在认证通过时原子占位——同 role 的第二次认证
// 立即被拒，且首次会话保持占用（旧缺陷：检查与写入分离导致双双通过）。
func TestAuthenticateReservesRoleAtomically(t *testing.T) {
	srv := New(Config{
		Token:       "secret",
		ReadTimeout: 5 * time.Second,
		AuthTimeout: 2 * time.Second,
		Logger:      log.New(io.Discard, "", 0),
	})

	authPayload, _ := json.Marshal(protocol.AuthRequest{
		Role: protocol.RolePhone, Token: "secret", Proto: protocol.ProtoVersion,
	})
	// 按线路协议加帧头后写入（authenticate 读的是帧而非裸 JSON）
	authFrame := make([]byte, 5+len(authPayload))
	authFrame[0] = protocol.FrameAuth
	binary.BigEndian.PutUint32(authFrame[1:], uint32(len(authPayload)))
	copy(authFrame[5:], authPayload)

	mkSession := func() (*session, net.Conn) {
		serverEnd, clientEnd := net.Pipe()
		t.Cleanup(func() { serverEnd.Close(); clientEnd.Close() })
		return &session{srv: srv, conn: serverEnd}, clientEnd
	}

	sess1, client1 := mkSession()
	go func() { // net.Pipe 同步管道：写端需独立 goroutine，避免阻塞
		client1.Write(authFrame) //nolint:errcheck // 测试辅助
	}()
	peer1, ok := srv.authenticate(sess1)
	if !ok || sess1.role != protocol.RolePhone || peer1 != nil {
		t.Fatalf("first auth should succeed and reserve: ok=%v role=%q peer=%v", ok, sess1.role, peer1)
	}

	sess2, client2 := mkSession()
	rejected := make(chan []byte, 1)
	go func() {
		client2.Write(authFrame) //nolint:errcheck // 测试辅助
		buf := make([]byte, 64)
		n, _ := client2.Read(buf) // 读取 AUTH_ERR 文本
		rejected <- buf[:n]
	}()
	if _, ok := srv.authenticate(sess2); ok {
		t.Fatal("second auth for same role must be rejected")
	}
	select {
	case msg := <-rejected:
		if !strings.Contains(string(msg), "role in use") {
			t.Fatalf("want 'role in use', got %q", msg)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no AUTH_ERR reached client")
	}
	if srv.roles[protocol.RolePhone] != sess1 {
		t.Fatal("first session must keep the reserved slot")
	}
}

// 回归(H-1 配套)：陈旧会话注销时不通知对端，防止虚假掉线。
func TestUnregisterStaleSessionSkipsNotify(t *testing.T) {
	srv := New(Config{Token: "x"})
	c := func() *tls.Conn {
		a, b := net.Pipe()
		t.Cleanup(func() { a.Close(); b.Close() })
		return tls.Client(a, &tls.Config{})
	}
	stale := &session{srv: srv, conn: c(), role: protocol.RolePhone}
	current := &session{srv: srv, conn: c(), role: protocol.RolePhone}

	srv.mu.Lock()
	srv.roles[protocol.RolePhone] = current // 槽位已被新连接占据
	srv.mu.Unlock()

	if got := srv.unregister(stale); got != nil {
		t.Fatal("stale session unregister must not trigger offline notify")
	}
	if srv.roles[protocol.RolePhone] != current {
		t.Fatal("current session must stay registered")
	}
}

// startTestServer 在 127.0.0.1 随机端口起一个纯 TCP 测试服务器（单元测试不涉 TLS）。
func startTestServer(t *testing.T, token string, readTimeout, authTimeout time.Duration) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	srv := New(Config{
		Token:       token,
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

func authReq(t *testing.T, conn net.Conn, role, token string) {
	t.Helper()
	payload, _ := json.Marshal(protocol.AuthRequest{Role: role, Token: token, Proto: protocol.ProtoVersion})
	if err := protocol.WriteFrame(conn, protocol.FrameAuth, payload); err != nil {
		t.Fatalf("write AUTH: %v", err)
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

// expectAuthErr 读取并校验 AUTH_ERR 帧，返回原因 payload。
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

func mustAuth(t *testing.T, addr, role, token string) net.Conn {
	t.Helper()
	conn := dial(t, addr)
	authReq(t, conn, role, token)
	expectFrame(t, conn, protocol.FrameAuthOK, 2*time.Second)
	return conn
}

func TestAuthWrongTokenRejected(t *testing.T) {
	addr := startTestServer(t, "secret", 5*time.Second, 2*time.Second)
	conn := dial(t, addr)
	authReq(t, conn, protocol.RolePhone, "wrong")
	payload := expectAuthErr(t, conn)
	if len(payload) == 0 {
		t.Fatal("AUTH_ERR payload should carry reason")
	}
	// 服务器随后关闭连接
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := protocol.ReadFrame(conn); err == nil {
		t.Fatal("connection should be closed after AUTH_ERR")
	}
}

func TestAuthTimeoutClosesConnection(t *testing.T) {
	addr := startTestServer(t, "secret", 5*time.Second, 150*time.Millisecond)
	conn := dial(t, addr) // 连上但不发 AUTH
	start := time.Now()
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, _, err := protocol.ReadFrame(conn); err == nil {
		t.Fatal("expect close on auth timeout")
	} else if elapsed := time.Since(start); elapsed > 2500*time.Millisecond {
		t.Fatalf("auth timeout too late: %v", elapsed)
	}
}

func TestRoleOccupiedRejected(t *testing.T) {
	addr := startTestServer(t, "secret", 5*time.Second, 2*time.Second)
	mustAuth(t, addr, protocol.RolePhone, "secret")
	second := dial(t, addr)
	authReq(t, second, protocol.RolePhone, "secret")
	payload := expectAuthErr(t, second)
	if !strings.Contains(string(payload), "role in use") {
		t.Fatalf("want %q in error, got %q", "role in use", payload)
	}
}

func TestPairingAndBidirectionalForwarding(t *testing.T) {
	addr := startTestServer(t, "secret", 5*time.Second, 2*time.Second)
	phone := mustAuth(t, addr, protocol.RolePhone, "secret")
	mac := mustAuth(t, addr, protocol.RoleMac, "secret")

	// 后注册的 mac 触发桥接：两端都应收到 PEER_STATE(online)
	online := expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)
	if len(online) != 1 || online[0] != protocol.PeerOnline {
		t.Fatalf("mac want PeerOnline, got %v", online)
	}
	online = expectFrame(t, phone, protocol.FramePeerState, 2*time.Second)
	if len(online) != 1 || online[0] != protocol.PeerOnline {
		t.Fatalf("phone want PeerOnline, got %v", online)
	}

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
}

func TestPeerOfflineNotifyAndReconnect(t *testing.T) {
	addr := startTestServer(t, "secret", 5*time.Second, 2*time.Second)
	phone := mustAuth(t, addr, protocol.RolePhone, "secret")
	mac := mustAuth(t, addr, protocol.RoleMac, "secret")
	expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)
	expectFrame(t, phone, protocol.FramePeerState, 2*time.Second)

	// phone 掉线 → mac 收 PEER_STATE(offline)
	phone.Close()
	offline := expectFrame(t, mac, protocol.FramePeerState, 3*time.Second)
	if len(offline) != 1 || offline[0] != protocol.PeerOffline {
		t.Fatalf("want PeerOffline, got %v", offline)
	}

	// phone 重连（新连接重新认证）→ mac 收 PEER_STATE(online)，转发恢复
	phone2 := mustAuth(t, addr, protocol.RolePhone, "secret")
	online := expectFrame(t, mac, protocol.FramePeerState, 3*time.Second)
	if len(online) != 1 || online[0] != protocol.PeerOnline {
		t.Fatalf("want PeerOnline after reconnect, got %v", online)
	}
	payload := []byte{0x01, 0x02, 0x03}
	if err := protocol.WriteFrame(phone2, protocol.FrameAudio, payload); err != nil {
		t.Fatalf("send audio: %v", err)
	}
	got := expectFrame(t, mac, protocol.FrameAudio, 2*time.Second)
	if string(got) != string(payload) {
		t.Fatal("forwarding broken after reconnect")
	}
}

func TestHeartbeatPingPongKeepsAlive(t *testing.T) {
	// 读超时 300ms；每 100ms 发一次 PING 应能存活远超 300ms
	addr := startTestServer(t, "secret", 300*time.Millisecond, 2*time.Second)
	conn := mustAuth(t, addr, protocol.RolePhone, "secret")

	deadline := time.Now().Add(1500 * time.Millisecond)
	for time.Now().Before(deadline) {
		if err := protocol.WriteFrame(conn, protocol.FramePing, nil); err != nil {
			t.Fatalf("ping write: %v", err)
		}
		conn.SetReadDeadline(time.Now().Add(time.Second))
		typ, _, err := protocol.ReadFrame(conn)
		if err != nil {
			t.Fatalf("connection died despite pings: %v", err)
		}
		if typ != protocol.FramePong {
			t.Fatalf("want PONG, got 0x%02x", typ)
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func TestHeartbeatTimeoutDisconnects(t *testing.T) {
	addr := startTestServer(t, "secret", 300*time.Millisecond, 2*time.Second)
	conn := mustAuth(t, addr, protocol.RolePhone, "secret")
	// 不发任何心跳：应在 ~300ms 内被断开
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	start := time.Now()
	if _, _, err := protocol.ReadFrame(conn); err == nil {
		t.Fatal("expect disconnect on heartbeat timeout")
	}
	if elapsed := time.Since(start); elapsed > 2500*time.Millisecond {
		t.Fatalf("disconnect too late: %v", elapsed)
	}
}

func TestUnknownFrameTypeDropped(t *testing.T) {
	addr := startTestServer(t, "secret", 5*time.Second, 2*time.Second)
	conn := mustAuth(t, addr, protocol.RolePhone, "secret")
	// 发送未知类型帧，连接应继续工作（向前兼容）
	if err := protocol.WriteFrame(conn, 0x7F, []byte("whatever")); err != nil {
		t.Fatalf("write unknown frame: %v", err)
	}
	if err := protocol.WriteFrame(conn, protocol.FramePing, nil); err != nil {
		t.Fatalf("write ping: %v", err)
	}
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	typ, _, err := protocol.ReadFrame(conn)
	if err != nil || typ != protocol.FramePong {
		t.Fatalf("conn should survive unknown frame and reply PONG, got typ=0x%02x err=%v", typ, err)
	}
}

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
		Token:     "secret",
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
	phone := dialTLS()
	authReq(t, phone, protocol.RolePhone, "secret")
	expectFrame(t, phone, protocol.FrameAuthOK, 2*time.Second)

	mac := dialTLS()
	authReq(t, mac, protocol.RoleMac, "secret")
	expectFrame(t, mac, protocol.FrameAuthOK, 2*time.Second)

	// 桥接建立：mac 作为后到者，两端都应收到 PEER_STATE(online)
	expectFrame(t, phone, protocol.FramePeerState, 2*time.Second)
	expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)
}
