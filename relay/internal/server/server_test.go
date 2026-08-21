package server

import (
	"encoding/json"
	"io"
	"log"
	"net"
	"strings"
	"testing"
	"time"

	"remote-voice/relay/internal/protocol"
)

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
