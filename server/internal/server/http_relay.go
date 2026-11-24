package server

import (
	"bufio"
	"net"
	"net/http"
	"strings"
	"time"
)

const relayUpgrade = "remote-voice"

// RelayHTTPHandler 接收回环上由 TLS 反向代理转发的 HTTP/1.1 升级请求。
func (s *Server) RelayHTTPHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = w.Write([]byte("remote-voice ok\n"))
	})
	mux.HandleFunc("GET /relay", s.upgradeRelay)
	return mux
}

func (s *Server) upgradeRelay(w http.ResponseWriter, r *http.Request) {
	proxy, _, _ := net.SplitHostPort(r.RemoteAddr)
	peerIP := net.ParseIP(r.Header.Get("X-Real-IP"))
	if !net.ParseIP(proxy).IsLoopback() || peerIP == nil || r.Header.Get("Origin") != "" {
		http.Error(w, "relay requires a trusted TLS proxy", http.StatusForbidden)
		return
	}
	if r.Method != http.MethodGet || r.ProtoMajor != 1 || r.ProtoMinor != 1 ||
		r.ContentLength != 0 || r.URL.RawQuery != "" ||
		!strings.EqualFold(r.Header.Get("Upgrade"), relayUpgrade) ||
		!hasHeaderToken(r.Header.Values("Connection"), "upgrade") {
		w.Header().Set("Upgrade", relayUpgrade)
		http.Error(w, "relay upgrade required", http.StatusUpgradeRequired)
		return
	}
	conn, buffered, err := http.NewResponseController(w).Hijack()
	if err != nil {
		http.Error(w, "relay upgrade failed", http.StatusInternalServerError)
		return
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(tlsHandshakeTimeout))
	if _, err := buffered.WriteString("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: " + relayUpgrade + "\r\n\r\n"); err != nil {
		return
	}
	if err := buffered.Flush(); err != nil {
		return
	}
	_ = conn.SetDeadline(time.Time{})
	setNoDelay(conn)
	peer := peerIP.String()
	s.cfg.Logger.Printf("https relay accepted peer=%s", peer)
	s.handleSession(&relayHTTPConn{Conn: conn, reader: buffered.Reader}, peer, false)
}

func hasHeaderToken(values []string, token string) bool {
	for _, value := range values {
		for _, part := range strings.Split(value, ",") {
			if strings.EqualFold(strings.TrimSpace(part), token) {
				return true
			}
		}
	}
	return false
}

// 保留 HTTP 层可能已读入缓冲区的首个 v4 帧。
type relayHTTPConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *relayHTTPConn) Read(p []byte) (int, error) {
	return c.reader.Read(p)
}
