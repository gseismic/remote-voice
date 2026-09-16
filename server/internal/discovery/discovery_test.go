package discovery

import (
	"encoding/json"
	"net"
	"testing"
	"time"
)

func newResponder(t *testing.T, name string) *Responder {
	t.Helper()
	r, err := Serve("127.0.0.1:0", name)
	if err != nil {
		t.Fatalf("启动发现应答器失败: %v", err)
	}
	t.Cleanup(func() { _ = r.Close() })
	return r
}

func newClient(t *testing.T, r *Responder) *net.UDPConn {
	t.Helper()
	c, err := net.DialUDP("udp", nil,
		&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: r.LocalPort()})
	if err != nil {
		t.Fatalf("客户端拨号失败: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func readWithDeadline(t *testing.T, c *net.UDPConn, d time.Duration) ([]byte, error) {
	t.Helper()
	_ = c.SetReadDeadline(time.Now().Add(d))
	buf := make([]byte, 512)
	n, err := c.Read(buf)
	if err != nil {
		return nil, err
	}
	return buf[:n], nil
}

func TestRespondsToProbe(t *testing.T) {
	r := newResponder(t, "test-node")
	c := newClient(t, r)

	if _, err := c.Write([]byte(ProbeMessage)); err != nil {
		t.Fatalf("发送探测失败: %v", err)
	}
	raw, err := readWithDeadline(t, c, 2*time.Second)
	if err != nil {
		t.Fatalf("未收到应答: %v", err)
	}
	var got reply
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatalf("应答不是合法 JSON: %v (%q)", err, raw)
	}
	if got.V != 1 || got.Name != "test-node" || got.Port != r.LocalPort() {
		t.Fatalf("应答内容不符: %+v (期望 port=%d)", got, r.LocalPort())
	}
}

func TestIgnoresForeignPayload(t *testing.T) {
	r := newResponder(t, "n")
	c := newClient(t, r)

	if _, err := c.Write([]byte("hello")); err != nil {
		t.Fatalf("发送失败: %v", err)
	}
	if raw, err := readWithDeadline(t, c, 300*time.Millisecond); err == nil {
		t.Fatalf("陌生 payload 不应获得应答，却回了 %q", raw)
	}
}

func TestRateLimitsPerSource(t *testing.T) {
	r := newResponder(t, "n")
	c := newClient(t, r)

	if _, err := c.Write([]byte(ProbeMessage)); err != nil {
		t.Fatalf("第一次探测发送失败: %v", err)
	}
	if _, err := readWithDeadline(t, c, 2*time.Second); err != nil {
		t.Fatalf("第一次探测应未答: %v", err)
	}
	// 同源 1 秒内的第二次探测不应获得应答。
	if _, err := c.Write([]byte(ProbeMessage)); err != nil {
		t.Fatalf("第二次探测发送失败: %v", err)
	}
	if raw, err := readWithDeadline(t, c, 300*time.Millisecond); err == nil {
		t.Fatalf("同源节流失效，第二次也回了 %q", raw)
	}
}
