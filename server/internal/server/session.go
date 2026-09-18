package server

import (
	"net"
	"sync"
	"sync/atomic"
	"time"

	"remote-voice/server/internal/protocol"
)

// session 表示一个已接入的客户端连接。
// 并发模型（内部接口契约）：每个会话恰好一个读 goroutine；
// 所有写操作必须经 writeFrame 串行化，保证帧头与 payload 不被并发写撕裂。
type session struct {
	srv      *Server
	conn     net.Conn
	role     string     // AUTH 通过后才有值；mac 或 phone
	deviceID string     // v3 Mac 设备身份；由 Server.mu 保护其关联索引
	peer     string     // 远端地址字符串，仅用于日志展示
	writeMu  sync.Mutex // 串行化全部帧写
	closed   atomic.Bool
	bridge   *bridge // 已通过认证的手机/Mac 持有其桥接；由 Server.mu 读写，读取在锁内
	// 音频接收计数（readLoop 单线程累加，statsLoop 原子读取）
	audioFrames atomic.Int64
	audioBytes  atomic.Int64
}

// writeFrame 串行化写出一帧。写失败视为连接已死，主动关闭以唤醒读循环。
func (s *session) writeFrame(typ byte, payload []byte) {
	if s.closed.Load() {
		return
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	if err := protocol.WriteFrame(s.conn, typ, payload); err != nil {
		s.close()
	}
}

// reject 发送 AUTH_ERR（带原因码，供客户端分支处理）并记录拒绝原因；调用方随后关闭连接。
func (s *session) reject(code, msg string) {
	s.srv.cfg.Logger.Printf("rejected peer=%s reason=%q", s.peer, msg)
	s.writeFrame(protocol.FrameAuthErr, []byte(code))
}

// close 幂等关闭底层连接。
func (s *session) close() {
	if s.closed.CompareAndSwap(false, true) {
		s.conn.Close()
	}
}

// readLoop 逐帧读取并分发：
// PING→PONG；AUDIO→经桥接转发对端（未架桥时丢弃但计数）；
// REGISTER→Mac 注册/热更；未知类型丢弃（向前兼容）。
// 读超时（心跳判定）由每次读取前的 SetReadDeadline 实现。
func (s *Server) readLoop(sess *session) {
	for {
		sess.conn.SetReadDeadline(time.Now().Add(s.cfg.ReadTimeout))
		typ, payload, err := protocol.ReadFrame(sess.conn)
		if err != nil {
			return
		}
		switch typ {
		case protocol.FramePing:
			sess.writeFrame(protocol.FramePong, nil)
		case protocol.FramePong:
			// 收到即证明对端活性，读超时已在读取时刷新
		case protocol.FrameAudio:
			sess.audioFrames.Add(1)
			sess.audioBytes.Add(int64(len(payload)))
			// 计数含未桥接时被丢弃的帧：反映的是"对端发来了什么"
			if target := s.peerTarget(sess); target != nil {
				target.writeFrame(protocol.FrameAudio, payload)
			}
		case protocol.FrameTalk:
			// 说话状态（PTT 按下/松开）与音频同路径透传，不计数不解析。
			// 手机侧持有当前状态并在重桥时重发，服务器丢弃不缓存。
			if target := s.peerTarget(sess); target != nil {
				target.writeFrame(protocol.FrameTalk, payload)
			}
		case protocol.FrameRegister:
			if sess.role == protocol.RoleMac {
				s.handleRegister(sess, payload)
			}
			// 手机发 REGISTER 非法：丢弃不拒绝（向前兼容）
		default:
			// 未知类型：payload 已被 ReadFrame 消费，继续运行（向前兼容）
		}
	}
}

// peerTarget 返回某会话应转发到的桥接对端（音频/说话状态等双向载荷）；
// 无桥接时返回 nil。
// 在锁内解引用桥接的读操作，避免与 authPhone/detach 的原子解桥竞态。
func (s *Server) peerTarget(sess *session) *session {
	s.mu.Lock()
	defer s.mu.Unlock()
	b := sess.bridge
	if b == nil {
		return nil
	}
	if b.mac == sess {
		return b.phone
	}
	return b.mac
}
