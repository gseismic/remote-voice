// Package discovery 实现 UDP 局域网发现应答：手机在局域网广播探测串，
// 服务器精确匹配后回 JSON（显示名 + TCP 服务端口），供用户直接选择。
// 应答不含任何秘密或证书信息；同源 IP 限速防止探测放大滥用。
package discovery

import (
	"encoding/json"
	"log"
	"net"
	"os"
	"strings"
	"sync"
	"time"
)

// ProbeMessage 是客户端探测包的精确 payload（设计：lan-discovery-dual-address §3）。
const ProbeMessage = "RV-DISCOVER-v1"

const (
	replyInterval = time.Second // 同一源 IP 的最小应答间隔
	maxSources    = 4096        // 节流表上限，达到后整体重置避免无限膨胀
	readBufSize   = 512
)

// reply 是应答 JSON 结构；v 字段便于未来不兼容演进。
type reply struct {
	V    int    `json:"v"`
	Name string `json:"name"`
	Port int    `json:"port"`
}

// Responder 在与 TCP 服务同号的 UDP 端口上应答探测。
type Responder struct {
	conn *net.UDPConn
	port int
	name string

	mu       sync.Mutex
	lastSeen map[string]time.Time
	stop     chan struct{}
	stopped  sync.Once
}

// Serve 监听 addr（如 ":9432"）对应端口的 UDP 并开始应答。
// name 为应答中的显示名，传空则用本机主机名。
func Serve(addr, name string) (*Responder, error) {
	if name == "" {
		if h, err := os.Hostname(); err == nil && h != "" {
			name = h
		} else {
			name = "remote-voice-server"
		}
	}
	udpAddr, err := net.ResolveUDPAddr("udp", addr)
	if err != nil {
		return nil, err
	}
	conn, err := net.ListenUDP("udp", udpAddr)
	if err != nil {
		return nil, err
	}
	r := &Responder{
		conn:     conn,
		port:     conn.LocalAddr().(*net.UDPAddr).Port,
		name:     name,
		lastSeen: make(map[string]time.Time),
		stop:     make(chan struct{}),
	}
	go r.loop()
	return r, nil
}

// LocalPort 返回实际监听的 UDP 端口（支持测试用 ":0" 随机端口）。
func (r *Responder) LocalPort() int { return r.port }

// DisplayName 返回应答中使用的显示名（日志展示用）。
func (r *Responder) DisplayName() string { return r.name }

// Close 停止应答并关闭 UDP socket，可安全多次调用。
func (r *Responder) Close() error {
	var err error
	r.stopped.Do(func() {
		close(r.stop)
		err = r.conn.Close()
	})
	return err
}

func (r *Responder) loop() {
	buf := make([]byte, readBufSize)
	for {
		n, src, err := r.conn.ReadFromUDP(buf)
		if err != nil {
			select {
			case <-r.stop:
				return
			default:
			}
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				continue
			}
			log.Printf("discovery: 读取探测失败: %v", err)
			return
		}
		if strings.TrimSpace(string(buf[:n])) != ProbeMessage {
			continue
		}
		if !r.allow(src.IP.String()) {
			continue
		}
		out, err := json.Marshal(reply{V: 1, Name: r.name, Port: r.port})
		if err != nil {
			continue
		}
		if _, err := r.conn.WriteToUDP(append(out, '\n'), src); err != nil {
			log.Printf("discovery: 应答 %s 失败: %v", src, err)
		}
	}
}

// allow 同源 IP 每秒最多放行一次应答。
func (r *Responder) allow(key string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now()
	if last, ok := r.lastSeen[key]; ok && now.Sub(last) < replyInterval {
		return false
	}
	if len(r.lastSeen) >= maxSources {
		r.lastSeen = make(map[string]time.Time)
	}
	r.lastSeen[key] = now
	return true
}
