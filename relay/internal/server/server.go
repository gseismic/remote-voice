// Package server 实现 relay 中转服务器：
// TLS 接入 → token 认证 → role 独占注册 → 配对桥接（AUDIO 帧双向透传）→
// 心跳超时管理 → 断线通知。服务器不理解音频内容，是设计文档 §3 中的"哑管道"。
package server

import (
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"sync"
	"time"

	"remote-voice/relay/internal/protocol"
)

// Config 服务器配置。零值字段使用默认值。
type Config struct {
	Token       string        // 预共享 token（必填）
	ReadTimeout time.Duration // 读超时（心跳判定），默认 40s
	AuthTimeout time.Duration // 等待 AUTH 帧超时，默认 10s
	Logger      *log.Logger
}

func (c Config) withDefaults() Config {
	if c.ReadTimeout <= 0 {
		c.ReadTimeout = 40 * time.Second
	}
	if c.AuthTimeout <= 0 {
		c.AuthTimeout = 10 * time.Second
	}
	if c.Logger == nil {
		c.Logger = log.Default()
	}
	return c
}

// Server 持有全部会话状态。roles 固定两槽（phone/mac）。
// 并发契约：mu 保护 roles/ln/closed；session 写路径由各自 writeMu 保护。
type Server struct {
	cfg Config
	mu  sync.Mutex
	// roles: role → 当前占用该角色的会话；注销时校验身份防止误删重连后的新会话
	roles  map[string]*session
	ln     net.Listener
	closed bool
	quit   chan struct{}
}

func New(cfg Config) *Server {
	return &Server{
		cfg:   cfg.withDefaults(),
		roles: make(map[string]*session, 2),
		quit:  make(chan struct{}),
	}
}

// Serve 在 ln 上接受连接并逐个处理，阻塞直到 ln 关闭或 Close 被调用。
func (s *Server) Serve(ln net.Listener) error {
	// ln 在锁内发布：Close 可能与 Serve 并发启动（Review 追加轮 -race 实证）。
	// 若 Close 先行，此处直接返回，避免发布后永久阻塞在 Accept。
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.ln = ln
	s.mu.Unlock()
	for {
		conn, err := ln.Accept()
		if err != nil {
			select {
			case <-s.quit:
				return nil
			default:
				return fmt.Errorf("accept: %w", err)
			}
		}
		go s.handleConn(conn)
	}
}

// Close 优雅退出（幂等）：关闭 listener 并断开全部在线会话。
func (s *Server) Close() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	close(s.quit)
	ln := s.ln
	live := make([]*session, 0, len(s.roles))
	for _, sess := range s.roles {
		live = append(live, sess)
	}
	s.mu.Unlock()

	if ln != nil {
		ln.Close()
	}
	for _, sess := range live {
		sess.close()
	}
}

// handleConn 处理单个连接的完整生命周期：认证(含原子占位) → 桥接通知 → 读循环 → 注销通知。
func (s *Server) handleConn(conn net.Conn) {
	defer conn.Close()
	setNoDelay(conn)

	sess := &session{srv: s, conn: conn}
	peerAtStart, ok := s.authenticate(sess)
	if !ok {
		return
	}

	sess.writeFrame(protocol.FrameAuthOK, nil)
	if peerAtStart != nil {
		// 我是后到者：两端同时进入桥接
		sess.writeFrame(protocol.FramePeerState, []byte{protocol.PeerOnline})
		peerAtStart.writeFrame(protocol.FramePeerState, []byte{protocol.PeerOnline})
	}
	s.cfg.Logger.Printf("bridged role=%s", sess.role)

	s.readLoop(sess)

	peer := s.unregister(sess)
	if peer != nil {
		peer.writeFrame(protocol.FramePeerState, []byte{protocol.PeerOffline})
		s.cfg.Logger.Printf("peer notified offline role=%s peer=%s", sess.role, peer.role)
	}
	s.cfg.Logger.Printf("disconnected role=%s", sess.role)
}

// authenticate 读取并校验 AUTH 帧；校验通过后在同一临界区内完成槽位
// check-and-reserve（消除 Review H-1 的检查/写入窗口竞态）。
// 返回值：ok=是否认证成功并已占位；peer=对端会话（nil 表示对端未上线）。
func (s *Server) authenticate(sess *session) (peer *session, ok bool) {
	sess.conn.SetReadDeadline(time.Now().Add(s.cfg.AuthTimeout))
	typ, payload, err := protocol.ReadFrame(sess.conn)
	if err != nil {
		s.cfg.Logger.Printf("auth read error: %v", err)
		return nil, false
	}
	if typ != protocol.FrameAuth {
		sess.reject("first frame must be AUTH")
		return nil, false
	}
	var req protocol.AuthRequest
	if err := json.Unmarshal(payload, &req); err != nil {
		sess.reject("malformed AUTH payload")
		return nil, false
	}
	if req.Proto != protocol.ProtoVersion {
		sess.reject(fmt.Sprintf("unsupported proto version %d", req.Proto))
		return nil, false
	}
	if req.Role != protocol.RolePhone && req.Role != protocol.RoleMac {
		sess.reject("invalid role")
		return nil, false
	}
	// 常量时间比较，避免 token 逐字节探测
	if subtle.ConstantTimeCompare([]byte(req.Token), []byte(s.cfg.Token)) != 1 {
		s.cfg.Logger.Printf("auth failed role=%s (bad token)", req.Role)
		sess.reject("invalid token")
		return nil, false
	}
	s.mu.Lock()
	occupied := s.roles[req.Role] != nil
	if !occupied {
		sess.role = req.Role
		s.roles[req.Role] = sess
	}
	s.cfg.Logger.Printf("authenticated role=%s", sess.role)
	peer = s.roles[otherRole(req.Role)]
	ok = true
	s.mu.Unlock()
	if occupied {
		// 拒绝帧的写在锁外：避免持全局锁做网络 IO
		sess.reject("role in use")
		return nil, false
	}
	return peer, ok
}

// unregister 注销会话，返回需要接收 PEER_STATE(offline) 的对端（可能为 nil）。
// 仅当槽位仍是本会话时才删除：防止误删断线期间同角色重连上来的新会话。
// 防御分支（Review H-1 配套）：若槽位已被其他会话顶替，说明本连接是陈旧连接，
// 此时不通知 offline——否则对端会被虚假掉线误导而暂停收音。
func (s *Server) unregister(sess *session) *session {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.roles[sess.role] != sess {
		return nil
	}
	delete(s.roles, sess.role)
	return s.roles[otherRole(sess.role)]
}

func otherRole(role string) string {
	if role == protocol.RolePhone {
		return protocol.RoleMac
	}
	return protocol.RolePhone
}

// setNoDelay 降低小帧转发延迟（设计文档 §2.2）。
// TLS 连接必须先经 NetConn() 解包到底层 TCP 连接——*tls.Conn 自身不实现
// SetNoDelay，直接断言会静默失效（Review H-2 实证结论）。
func setNoDelay(conn net.Conn) {
	type tlsUnwrapper interface{ NetConn() net.Conn }
	type tcpConn interface{ SetNoDelay(bool) error }
	if u, ok := conn.(tlsUnwrapper); ok {
		conn = u.NetConn()
	}
	if tc, ok := conn.(tcpConn); ok {
		tc.SetNoDelay(true)
	}
}
