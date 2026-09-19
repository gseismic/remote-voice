package server

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
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

var testDeviceSeq atomic.Uint64

// hashPassword 测试内复刻客户端规范化+SHA-256（与客户端/fakephone 同规格）。
func hashPassword(t *testing.T, raw string) string {
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

// authPhoneReq 发送手机 AUTH（password=服务器密码原文；target 可空=登录会话）。
func authPhoneReq(t *testing.T, conn net.Conn, password, target string) {
	t.Helper()
	payload, _ := json.Marshal(protocol.AuthRequest{
		Role: protocol.RolePhone, Proto: protocol.ProtoVersion,
		Secret: hashPassword(t, password), Target: target,
	})
	if err := protocol.WriteFrame(conn, protocol.FrameAuth, payload); err != nil {
		t.Fatalf("write AUTH(phone): %v", err)
	}
}

func authMacReq(t *testing.T, conn net.Conn, deviceID, deviceKey string) {
	t.Helper()
	payload, _ := json.Marshal(protocol.AuthRequest{
		Role: protocol.RoleMac, Proto: protocol.ProtoVersion,
		DeviceID: deviceID, DeviceKey: deviceKey,
	})
	if err := protocol.WriteFrame(conn, protocol.FrameAuth, payload); err != nil {
		t.Fatalf("write AUTH(mac): %v", err)
	}
}

func registerReq(t *testing.T, conn net.Conn, name, password string) {
	t.Helper()
	hash := ""
	if password != "" {
		hash = hashPassword(t, password)
	}
	payload, _ := json.Marshal(protocol.RegisterRequest{Name: name, PasswordHash: hash})
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

// mustRegMac 连接并完成 Mac 认证+REGISTER（设备名 + 服务器密码）。
// 返回 Mac 连接与 device_id（手机建桥的 target）。
func mustRegMac(t *testing.T, addr, name, password string) (net.Conn, string) {
	t.Helper()
	mac := dial(t, addr)
	seq := testDeviceSeq.Add(1)
	deviceID := fmt.Sprintf("test-device-%d", seq)
	deviceKey := fmt.Sprintf("%064x", seq)
	authMacReq(t, mac, deviceID, deviceKey)
	expectFrame(t, mac, protocol.FrameAuthOK, 2*time.Second)
	registerReq(t, mac, name, password)
	return mac, deviceID
}

// mustAuthPhone 手机认证成功。target 空=登录会话（仅 AUTH_OK）；
// 非空=建桥（消费 PEER_STATE(online)）。返回手机连接与 AUTH_OK payload。
func mustAuthPhone(t *testing.T, addr, password, target string) (net.Conn, []byte) {
	t.Helper()
	phone := dial(t, addr)
	authPhoneReq(t, phone, password, target)
	okPayload := expectFrame(t, phone, protocol.FrameAuthOK, 3*time.Second)
	if target != "" {
		expectFrame(t, phone, protocol.FramePeerState, 3*time.Second)
	}
	return phone, okPayload
}

// mustRequestList 在已认证的登录/桥接会话上发送 LIST 并解析应答。
func mustRequestList(t *testing.T, phone net.Conn) protocol.ListPayload {
	t.Helper()
	if err := protocol.WriteFrame(phone, protocol.FrameList, nil); err != nil {
		t.Fatalf("write LIST: %v", err)
	}
	payload := expectFrame(t, phone, protocol.FrameList, 2*time.Second)
	var list protocol.ListPayload
	if err := json.Unmarshal(payload, &list); err != nil {
		t.Fatalf("LIST unmarshal: %v", err)
	}
	return list
}

// ===== 认证 =====

func TestMacInvalidDeviceCredentialRejected(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	good := dial(t, addr)
	deviceID := "fixed-test-device"
	goodKey := fmt.Sprintf("%064x", 1)
	authMacReq(t, good, deviceID, goodKey)
	expectFrame(t, good, protocol.FrameAuthOK, 2*time.Second)

	bad := dial(t, addr)
	authMacReq(t, bad, deviceID, fmt.Sprintf("%064x", 2))
	reason := expectAuthErr(t, bad)
	if !strings.Contains(string(reason), protocol.ReasonInvalidDevice) {
		t.Fatalf("want %q, got %q", protocol.ReasonInvalidDevice, reason)
	}
}

func TestDeviceRegistryPersistsAndVerifies(t *testing.T) {
	path := filepath.Join(t.TempDir(), "devices.json")
	first, err := newDeviceRegistry(path)
	if err != nil {
		t.Fatalf("new registry: %v", err)
	}
	key := strings.Repeat("ab", 32)
	ok, err := first.verifyOrEnroll("persisted-device", key)
	if err != nil || !ok {
		t.Fatalf("first enrollment failed: ok=%v err=%v", ok, err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat device registry: %v", err)
	}
	if got := info.Mode().Perm(); got != 0o600 {
		t.Fatalf("device registry permissions = %o, want 600", got)
	}
	second, err := newDeviceRegistry(path)
	if err != nil {
		t.Fatalf("reload registry: %v", err)
	}
	ok, err = second.verifyOrEnroll("persisted-device", key)
	if err != nil || !ok {
		t.Fatalf("persisted credential should verify: ok=%v err=%v", ok, err)
	}
	ok, err = second.verifyOrEnroll("persisted-device", fmt.Sprintf("%064x", 10))
	if err != nil {
		t.Fatalf("wrong credential check: %v", err)
	}
	if ok {
		t.Fatal("wrong credential must be rejected")
	}
	upperKey := strings.ToUpper(key)
	ok, err = second.verifyOrEnroll("persisted-device", upperKey)
	if err != nil || !ok {
		t.Fatalf("hex credential case should be normalized: ok=%v err=%v", ok, err)
	}
}

func TestDeviceRegistryPasswordPersists(t *testing.T) {
	// 测试目的：服务器密码哈希落盘且重启后仍可校验；设备名随 REGISTER 持久化。
	path := filepath.Join(t.TempDir(), "devices.json")
	first, err := newDeviceRegistry(path)
	if err != nil {
		t.Fatalf("new registry: %v", err)
	}
	want := hashPassword(t, "SERVER-PW-1")
	if err := first.SetPasswordHash(want); err != nil {
		t.Fatalf("set password: %v", err)
	}
	if err := first.SetDeviceName("persisted-device", "书房 Mac"); err != nil {
		t.Fatalf("set name before enroll: %v", err)
	}
	second, err := newDeviceRegistry(path)
	if err != nil {
		t.Fatalf("reload registry: %v", err)
	}
	if second.PasswordHash() != want {
		t.Fatal("password hash should survive reload")
	}
	// 未设密码时 PasswordHash 为空
	empty, err := newDeviceRegistry(filepath.Join(t.TempDir(), "none.json"))
	if err != nil {
		t.Fatalf("new empty registry: %v", err)
	}
	if empty.PasswordHash() != "" {
		t.Fatal("fresh registry must have empty password hash")
	}
}

func TestMacDeviceAuthSurvivesServerRestart(t *testing.T) {
	// 测试目的：确认 Mac 首次认证落盘的设备凭据，server 重启后仍可恢复认证。
	path := filepath.Join(t.TempDir(), "devices.json")
	const deviceID = "restart-device"
	const deviceKey = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

	start := func() (*Server, net.Listener) {
		t.Helper()
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatalf("listen: %v", err)
		}
		srv, err := NewWithError(Config{
			DeviceStorePath: path,
			ReadTimeout:     5 * time.Second,
			AuthTimeout:     2 * time.Second,
			Logger:          log.New(io.Discard, "", 0),
		})
		if err != nil {
			ln.Close()
			t.Fatalf("new server: %v", err)
		}
		go srv.Serve(ln) //nolint:errcheck // 测试中忽略 listener 关闭返回值
		return srv, ln
	}

	srv1, ln1 := start()
	conn1, err := net.Dial("tcp", ln1.Addr().String())
	if err != nil {
		srv1.Close()
		ln1.Close()
		t.Fatalf("dial first server: %v", err)
	}
	authMacReq(t, conn1, deviceID, deviceKey)
	expectFrame(t, conn1, protocol.FrameAuthOK, 2*time.Second)
	conn1.Close()
	srv1.Close()
	ln1.Close()

	srv2, ln2 := start()
	t.Cleanup(func() {
		srv2.Close()
		ln2.Close()
	})
	conn2, err := net.Dial("tcp", ln2.Addr().String())
	if err != nil {
		t.Fatalf("dial restarted server: %v", err)
	}
	t.Cleanup(func() { conn2.Close() })
	authMacReq(t, conn2, deviceID, deviceKey)
	expectFrame(t, conn2, protocol.FrameAuthOK, 2*time.Second)
}

func TestMacSameDeviceOnlineRejected(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	deviceID := "online-device"
	deviceKey := fmt.Sprintf("%064x", 77)
	first := dial(t, addr)
	authMacReq(t, first, deviceID, deviceKey)
	expectFrame(t, first, protocol.FrameAuthOK, 2*time.Second)

	second := dial(t, addr)
	authMacReq(t, second, deviceID, deviceKey)
	reason := expectAuthErr(t, second)
	if !strings.Contains(string(reason), protocol.ReasonDeviceBusy) {
		t.Fatalf("want %q, got %q", protocol.ReasonDeviceBusy, reason)
	}
}

func TestPhoneWrongSecretRejected(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	// 服务器未设密码：任何密码都被拒
	conn := dial(t, addr)
	authPhoneReq(t, conn, "any-password", "")
	payload := expectAuthErr(t, conn)
	if !strings.Contains(string(payload), protocol.ReasonInvalidSecret) {
		t.Fatalf("want %q in error, got %q", protocol.ReasonInvalidSecret, payload)
	}
}

func TestPhoneBadHashFormatRejected(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
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

func TestPhoneTargetOfflineRejected(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	// 密码正确但 target 对应 Mac 不在线 → invalid-target（不计入限速语义）
	mac, deviceID := mustRegMac(t, addr, "MacBook", "PW-1")
	mac.Close()
	time.Sleep(150 * time.Millisecond)

	conn := dial(t, addr)
	authPhoneReq(t, conn, "PW-1", deviceID)
	payload := expectAuthErr(t, conn)
	if !strings.Contains(string(payload), protocol.ReasonInvalidTarget) {
		t.Fatalf("offline target want %q, got %q", protocol.ReasonInvalidTarget, payload)
	}
	// 同一 IP 紧接着仍可正常登录（invalid-target 不应触发防爆破锁定）
	phone, _ := mustAuthPhone(t, addr, "PW-1", "")
	if list := mustRequestList(t, phone); len(list.Macs) != 1 {
		t.Fatalf("want 1 mac in list, got %d", len(list.Macs))
	}
}

// ===== 目录（LIST） =====

func TestLoginSessionAndList(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	mac, deviceID := mustRegMac(t, addr, "书房-Mac", "PW-1")
	defer mac.Close()

	// 登录会话：AUTH_OK 后无 PEER_STATE，LIST 返回在线 Mac
	phone, okPayload := mustAuthPhone(t, addr, "PW-1", "")
	var ok protocol.AuthOKPayload
	if err := json.Unmarshal(okPayload, &ok); err != nil || ok.Mac != "" {
		t.Fatalf("login session AUTH_OK should carry no mac name, got %q err=%v", ok.Mac, err)
	}
	list := mustRequestList(t, phone)
	if len(list.Macs) != 1 {
		t.Fatalf("want 1 mac, got %d", len(list.Macs))
	}
	got := list.Macs[0]
	if got.DeviceID != deviceID || got.Name != "书房-Mac" || !got.Online || got.Busy {
		t.Fatalf("list entry mismatch: %+v (want device=%s)", got, deviceID)
	}
}

func TestListShowsOfflineDeviceWithPersistedName(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	mac, deviceID := mustRegMac(t, addr, "离线也可见", "PW-1")
	mac.Close()
	time.Sleep(150 * time.Millisecond)

	phone, _ := mustAuthPhone(t, addr, "PW-1", "")
	list := mustRequestList(t, phone)
	if len(list.Macs) != 1 {
		t.Fatalf("want 1 offline mac, got %d", len(list.Macs))
	}
	got := list.Macs[0]
	if got.DeviceID != deviceID || got.Name != "离线也可见" || got.Online || got.Busy {
		t.Fatalf("offline entry mismatch: %+v", got)
	}
}

// ===== 桥接 =====

func TestBridgeFullDuplex(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	// 同一服务器密码下的两台 Mac（v4 语义：密码=服务器级，target 区分设备）
	mac1, device1 := mustRegMac(t, addr, "MacBook-Pro", "PW-42")
	mac2, device2 := mustRegMac(t, addr, "Studio-Mac", "PW-42")

	// target 建桥：AUTH_OK 应回传目标设备名
	phone1, okPayload := mustAuthPhone(t, addr, "PW-42", device1)
	var ok protocol.AuthOKPayload
	if err := json.Unmarshal(okPayload, &ok); err != nil || ok.Mac != "MacBook-Pro" {
		t.Fatalf("want mac name in AUTH_OK, got %q err=%v", ok.Mac, err)
	}
	expectFrame(t, mac1, protocol.FramePeerState, 2*time.Second)

	// phone → mac 透传
	frameA := make([]byte, 1920)
	frameA[0] = 0x12
	if err := protocol.WriteFrame(phone1, protocol.FrameAudio, frameA); err != nil {
		t.Fatalf("phone send audio: %v", err)
	}
	gotA := expectFrame(t, mac1, protocol.FrameAudio, 2*time.Second)
	if string(gotA) != string(frameA) {
		t.Fatal("phone→mac payload mismatch")
	}

	// mac → phone 透传
	frameB := []byte{0x99, 0x88}
	if err := protocol.WriteFrame(mac1, protocol.FrameAudio, frameB); err != nil {
		t.Fatalf("mac send audio: %v", err)
	}
	gotB := expectFrame(t, phone1, protocol.FrameAudio, 2*time.Second)
	if string(gotB) != string(frameB) {
		t.Fatal("mac→phone payload mismatch")
	}

	// 同一密码的第二台手机建桥到另一台 Mac（多 Mac 并存）
	phone2, ok2 := mustAuthPhone(t, addr, "PW-42", device2)
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

func TestBridgeTalkForwardAndUnbridgedDrop(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	mac, deviceID := mustRegMac(t, addr, "MacBook", "TALK-1")

	phone, _ := mustAuthPhone(t, addr, "TALK-1", deviceID)
	expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)
	// Mac 掉线 → 桥溶解 → 手机收 offline；此时发 TALK 不致死、不转发
	mac.Close()
	expectFrame(t, phone, protocol.FramePeerState, 3*time.Second)
	if err := protocol.WriteFrame(phone, protocol.FrameTalk, []byte{0x01}); err != nil {
		t.Fatalf("unbridged talk should be accepted (dropped): %v", err)
	}
	// 会话仍存活：PING→PONG 证明连接未被 TALK 误杀
	if err := protocol.WriteFrame(phone, protocol.FramePing, nil); err != nil {
		t.Fatal(err)
	}
	expectFrame(t, phone, protocol.FramePong, 2*time.Second)
	phone.Close()

	// 新 Mac（同密码新 device_id）注册后，手机携新 target 重连并透传 TALK
	mac2, device2 := mustRegMac(t, addr, "MacBook", "TALK-1")
	_ = mac2
	phone2, _ := mustAuthPhone(t, addr, "TALK-1", device2)
	expectFrame(t, mac2, protocol.FramePeerState, 2*time.Second)
	if err := protocol.WriteFrame(phone2, protocol.FrameTalk, []byte{0x01}); err != nil {
		t.Fatalf("phone send talk: %v", err)
	}
	got := expectFrame(t, mac2, protocol.FrameTalk, 2*time.Second)
	if len(got) != 1 || got[0] != 0x01 {
		t.Fatalf("want talk-on payload [0x01], got %v", got)
	}
	if err := protocol.WriteFrame(phone2, protocol.FrameTalk, []byte{0x00}); err != nil {
		t.Fatalf("phone send talk off: %v", err)
	}
	got = expectFrame(t, mac2, protocol.FrameTalk, 2*time.Second)
	if len(got) != 1 || got[0] != 0x00 {
		t.Fatalf("want talk-off payload [0x00], got %v", got)
	}
}

func TestPeerBusyRejected(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	mac, deviceID := mustRegMac(t, addr, "MacBook", "BUSY-1")
	_ = mac
	phone1, _ := mustAuthPhone(t, addr, "BUSY-1", deviceID)
	expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)

	// 第二台手机建桥到同一台 Mac → peer-busy（密码正确）
	phone2 := dial(t, addr)
	authPhoneReq(t, phone2, "BUSY-1", deviceID)
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
	mac, deviceID := mustRegMac(t, addr, "MacBook", "DRAW-1")
	phone, _ := mustAuthPhone(t, addr, "DRAW-1", deviceID)
	expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)

	// phone 掉线 → mac 收 offline
	phone.Close()
	offline := expectFrame(t, mac, protocol.FramePeerState, 3*time.Second)
	if len(offline) != 1 || offline[0] != protocol.PeerOffline {
		t.Fatalf("want PeerOffline, got %v", offline)
	}
	// mac 还能重振：新手机再次建桥成功
	phone2, _ := mustAuthPhone(t, addr, "DRAW-1", deviceID)
	expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)
	phone2.Close()
	time.Sleep(100 * time.Millisecond)
}

func TestRegisterLiveUpdatePassword(t *testing.T) {
	// 测试目的：REGISTER 热更服务器密码（last-write-wins）：旧密码立即失效、新密码可用。
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	mac, deviceID := mustRegMac(t, addr, "MacBook", "PW-V1")

	phone1, _ := mustAuthPhone(t, addr, "PW-V1", "")
	_ = phone1
	macOffline := deviceID

	// 同一 Mac 热更密码为 V2
	registerReq(t, mac, "MacBook", "PW-V2")

	// 登录会话不依赖桥，直接验证旧密码失效/新密码生效
	oldConn := dial(t, addr)
	authPhoneReq(t, oldConn, "PW-V1", "")
	payload := expectAuthErr(t, oldConn)
	if !strings.Contains(string(payload), protocol.ReasonInvalidSecret) {
		t.Fatalf("old password should be invalid after live update, got %q", payload)
	}
	phone2, _ := mustAuthPhone(t, addr, "PW-V2", "")
	list := mustRequestList(t, phone2)
	if len(list.Macs) != 1 || list.Macs[0].DeviceID != macOffline {
		t.Fatalf("list should show the mac after password update: %+v", list.Macs)
	}
}

func TestAuthOKEventDeliveredToMac(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	mac, deviceID := mustRegMac(t, addr, "MacBook", "EVT-1")
	phone, _ := mustAuthPhone(t, addr, "EVT-1", deviceID)
	_ = phone
	ev := expectFrame(t, mac, protocol.FrameEvent, 2*time.Second)
	var n protocol.EventNotify
	if err := json.Unmarshal(ev, &n); err != nil {
		t.Fatalf("EVENT unmarshal: %v", err)
	}
	if n.Event != protocol.EventAuthOK || n.Kind != "" {
		t.Fatalf("want auth-ok without kind, got %+v", n)
	}
	if !strings.Contains(n.IP, "127.0.0.1") {
		t.Fatalf("want client ip, got %q", n.IP)
	}
	phone.Close()
}

// ===== 防爆破限速 =====

func TestRateLimitLocksIP(t *testing.T) {
	addr := startTestServer(t, 5*time.Second, 2*time.Second)
	// 连续 5 次错误密码：第 5 次触发锁定（依旧是 invalid-secret 应答）
	for i := 0; i < 5; i++ {
		conn := dial(t, addr)
		authPhoneReq(t, conn, "WRONG-X", "")
		payload := expectAuthErr(t, conn)
		if !strings.Contains(string(payload), protocol.ReasonInvalidSecret) {
			t.Fatalf("attempt %d want %q, got %q", i+1, protocol.ReasonInvalidSecret, payload)
		}
		conn.Close()
	}
	// 第 6 次：立即 rate-limited（无需再等滑窗）
	conn6 := dial(t, addr)
	authPhoneReq(t, conn6, "WRONG-X", "")
	payload6 := expectAuthErr(t, conn6)
	if !strings.Contains(string(payload6), protocol.ReasonRateLimited) {
		t.Fatalf("want %q after lock, got %q", protocol.ReasonRateLimited, payload6)
	}
	// 锁定期间即使正确密码也被拒绝（恒定排除）
	mac, _ := mustRegMac(t, addr, "MacBook", "GOOD-1")
	_ = mac
	conn7 := dial(t, addr)
	authPhoneReq(t, conn7, "GOOD-1", "")
	payload7 := expectAuthErr(t, conn7)
	if !strings.Contains(string(payload7), protocol.ReasonRateLimited) {
		t.Fatalf("locked IP must reject valid password, got %q", payload7)
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
	if !rl.locked(ip, base.Add(3*time.Minute)) {
		t.Fatal("2nd lock should be 5m, still locked at +3m")
	}
	if rl.locked(ip, base.Add(6*time.Minute)) {
		t.Fatal("2nd lock expired at +5m")
	}
	// 三级（封顶 30m）：再一轮窗口失败
	base2 := base.Add(6 * time.Minute)
	for i := 0; i < 5; i++ {
		rl.fail(ip, base2)
	}
	if !rl.locked(ip, base2.Add(10*time.Minute)) {
		t.Fatal("3rd lock should be 30m, still locked at +10m")
	}
	if !rl.locked(ip, base2.Add(10*time.Minute)) {
		t.Fatal("3rd lock should be 30m")
	}
	if rl.locked(ip, base2.Add(31*time.Minute)) {
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

// mustAuthPhoneOK 造一台在线 Mac（注册密码 KEEP-1）后以登录会话认证成功，
// 返回可直接操作的手机连接。Mac 连接由后台 goroutine 持续 PING 保活，
// 防止短读超时下 Mac 会话先死导致手机误收 offline 通知。
func mustAuthPhoneOK(t *testing.T, addr string) net.Conn {
	t.Helper()
	mac, _ := mustRegMac(t, addr, "K", "KEEP-1")
	go func() {
		for {
			if err := protocol.WriteFrame(mac, protocol.FramePing, nil); err != nil {
				return // 连接已结束
			}
			time.Sleep(100 * time.Millisecond)
		}
	}()
	phone := dial(t, addr)
	authPhoneReq(t, phone, "KEEP-1", "")
	expectFrame(t, phone, protocol.FrameAuthOK, 3*time.Second)
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
// tls.Listener.Accept 内，单个失败（如客户端证书不符主动断开、扫描器探针）
// 会沿 Accept 错误路径导致整个进程退出。现握手移入每连接 goroutine。
func TestTLSHandshakeFailureDoesNotKillServer(t *testing.T) {
	dir := t.TempDir()
	cert, fp, err := selfcert.Ensure(dir)
	if err != nil {
		t.Fatalf("ensure cert: %v", err)
	}
	var logBuf syncBuf
	srv := New(Config{
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

	// 攻击者：裸 TCP 发 HTTP 探针后立刻断开（等价于证书不符的客户端中断）
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
	deviceID := "tls-after-attack"
	authMacReq(t, mac, deviceID, fmt.Sprintf("%064x", 1003))
	expectFrame(t, mac, protocol.FrameAuthOK, 2*time.Second)
	registerReq(t, mac, "MacBook", "TLS-1")

	phone := dialTLS()
	authPhoneReq(t, phone, "TLS-1", deviceID)
	expectFrame(t, phone, protocol.FrameAuthOK, 2*time.Second)
	expectFrame(t, phone, protocol.FramePeerState, 2*time.Second)
	expectFrame(t, mac, protocol.FramePeerState, 2*time.Second)
}
