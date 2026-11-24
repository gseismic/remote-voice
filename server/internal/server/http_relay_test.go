package server

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"remote-voice/server/internal/protocol"
)

// 公网升级沿用原生目录与桥接，并阻止未经登记的设备取得服务器控制权。
func TestHTTPRelayAuthenticatesAndBridges(t *testing.T) {
	srv := New(Config{Logger: log.New(io.Discard, "", 0)})
	defer srv.Close()
	const deviceID = "known-device"
	const password = "relay-test-password"
	key := strings.Repeat("a", 64)
	if ok, err := srv.deviceStore.verifyOrEnroll(deviceID, key, true); !ok || err != nil {
		t.Fatalf("预登记失败: %v", err)
	}
	proxy := httptest.NewServer(srv.RelayHTTPHandler())
	t.Cleanup(proxy.Close)
	addr := proxy.Listener.Addr().String()

	unknown := dialRelayUpgrade(t, addr, nil)
	authMacReq(t, unknown, "unknown-device", key)
	if reason := string(expectAuthErr(t, unknown)); reason != protocol.ReasonInvalidDevice {
		t.Fatalf("未登记设备的拒绝原因 %q", reason)
	}

	// 模拟代理预读到紧跟升级请求的认证帧，升级后必须完整消费该帧。
	var firstFrame bytes.Buffer
	auth, _ := json.Marshal(protocol.AuthRequest{
		Role: protocol.RoleMac, Proto: protocol.ProtoVersion, DeviceID: deviceID, DeviceKey: key,
	})
	if err := protocol.WriteFrame(&firstFrame, protocol.FrameAuth, auth); err != nil {
		t.Fatal(err)
	}
	mac := dialRelayUpgrade(t, addr, firstFrame.Bytes())
	expectFrame(t, mac, protocol.FrameAuthOK, 2*time.Second)
	registerReq(t, mac, "测试 Mac", password)
	if err := protocol.WriteFrame(mac, protocol.FramePing, nil); err != nil {
		t.Fatal(err)
	}
	expectFrame(t, mac, protocol.FramePong, 2*time.Second)

	phone := dialRelayUpgrade(t, addr, nil)
	authPhoneReq(t, phone, password, deviceID)
	expectFrame(t, phone, protocol.FrameAuthOK, 2*time.Second)
	expectFrame(t, phone, protocol.FramePeerState, 2*time.Second)
	list := mustRequestList(t, phone)
	if len(list.Macs) != 1 || list.Macs[0].DeviceID != deviceID || !list.Macs[0].Busy {
		t.Fatalf("目标目录异常: %+v", list.Macs)
	}
	audio := bytes.Repeat([]byte{0x55}, 1920)
	if err := protocol.WriteFrame(phone, protocol.FrameAudio, audio); err != nil {
		t.Fatal(err)
	}
	if got := expectFrame(t, mac, protocol.FrameAudio, 2*time.Second); !bytes.Equal(got, audio) {
		t.Fatal("音频透传不一致")
	}
}

func dialRelayUpgrade(t *testing.T, addr string, firstFrame []byte) net.Conn {
	t.Helper()
	conn := dial(t, addr)
	request := fmt.Sprintf("GET /relay HTTP/1.1\r\nHost: %s\r\nConnection: Upgrade\r\nUpgrade: remote-voice\r\nX-Real-IP: 192.0.2.1\r\n\r\n", addr)
	if _, err := conn.Write(append([]byte(request), firstFrame...)); err != nil {
		t.Fatal(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	reader := bufio.NewReader(conn)
	res, err := http.ReadResponse(reader, &http.Request{Method: http.MethodGet})
	if err != nil {
		t.Fatal(err)
	}
	if res.StatusCode != http.StatusSwitchingProtocols || res.Header.Get("Upgrade") != relayUpgrade {
		t.Fatalf("升级响应为 %s", res.Status)
	}
	return &relayHTTPConn{Conn: conn, reader: reader}
}
