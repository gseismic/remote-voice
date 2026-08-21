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
type Server struct {
	cfg Config
	mu  sync.Mutex
	// roles: role → 当前占用该角色的会话；注销时校验身份防止误删重连后的新会话
	roles map[string]*session
	ln    net.Listener
	quit  chan struct{}
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
	s.ln = ln
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

// Close 优雅退出：关闭 listener 并断开全部在线会话。
func (s *Server) Close() {
	select {
	case <-s.quit:
		return
	default:
		close(s.quit)
	}
	if s.ln != nil {
		s.ln.Close()
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, sess := range s.roles {
		sess.close()
	}
}

// handleConn 处理单个连接的完整生命周期：认证 → 注册 → 读循环 → 注销通知。
func (s *Server) handleConn(conn net.Conn) {
	defer conn.Close()
	setNoDelay(conn)

	sess := &session{srv: s, conn: conn}
	if !s.authenticate(sess) {
		return
	}

	peerAtStart := s.register(sess)
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

// authenticate 读取并校验 AUTH 帧。失败时发送 AUTH_ERR 并返回 false。
func (s *Server) authenticate(sess *session) bool {
	sess.conn.SetReadDeadline(time.Now().Add(s.cfg.AuthTimeout))
	typ, payload, err := protocol.ReadFrame(sess.conn)
	if err != nil {
		s.cfg.Logger.Printf("auth read error: %v", err)
		return false
	}
	if typ != protocol.FrameAuth {
		sess.reject("first frame must be AUTH")
		return false
	}
	var req protocol.AuthRequest
	if err := json.Unmarshal(payload, &req); err != nil {
		sess.reject("malformed AUTH payload")
		return false
	}
	if req.Proto != protocol.ProtoVersion {
		sess.reject(fmt.Sprintf("unsupported proto version %d", req.Proto))
		return false
	}
	if req.Role != protocol.RolePhone && req.Role != protocol.RoleMac {
		sess.reject("invalid role")
		return false
	}
	// 常量时间比较，避免 token 逐字节探测
	if subtle.ConstantTimeCompare([]byte(req.Token), []byte(s.cfg.Token)) != 1 {
		s.cfg.Logger.Printf("auth failed role=%s (bad token)", req.Role)
		sess.reject("invalid token")
		return false
	}
	s.mu.Lock()
	if _, taken := s.roles[req.Role]; taken {
		s.mu.Unlock()
		sess.reject("role in use")
		return false
	}
	sess.role = req.Role
	s.mu.Unlock()
	s.cfg.Logger.Printf("authenticated role=%s", sess.role)
	return true
}

// register 登记会话；若对端已在线则返回对端（此时两端桥接就绪）。
func (s *Server) register(sess *session) *session {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.roles[sess.role] = sess
	return s.roles[otherRole(sess.role)]
}

// unregister 注销会话，返回对端会话（可能为 nil）。
// 仅当槽位仍是本会话时才删除：防止误删断线期间同角色重连上来的新会话。
func (s *Server) unregister(sess *session) *session {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.roles[sess.role] == sess {
		delete(s.roles, sess.role)
	}
	return s.roles[otherRole(sess.role)]
}

func otherRole(role string) string {
	if role == protocol.RolePhone {
		return protocol.RoleMac
	}
	return protocol.RolePhone
}

// setNoDelay 降低小帧转发延迟（设计文档 §2.2）。
func setNoDelay(conn net.Conn) {
	type tcpConn interface{ SetNoDelay(bool) error }
	if tc, ok := conn.(tcpConn); ok {
		tc.SetNoDelay(true)
	}
}
