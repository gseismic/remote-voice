package server

import (
	"net"
	"sync"
	"sync/atomic"
	"time"

	"remote-voice/relay/internal/protocol"
)

// session 表示一个已接入的客户端连接。
// 并发模型（内部接口契约）：每个会话恰好一个读 goroutine；
// 所有写操作必须经 writeFrame 串行化，保证帧头与 payload 不被并发写撕裂。
type session struct {
	srv     *Server
	conn    net.Conn
	role    string
	peer    string // 远端地址字符串，仅用于日志展示
	writeMu sync.Mutex // 串行化全部帧写
	closed  atomic.Bool
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

// reject 发送 AUTH_ERR 并记录拒绝原因；调用方随后关闭连接。
func (s *session) reject(msg string) {
	s.srv.cfg.Logger.Printf("rejected peer=%s reason=%q", s.peer, msg)
	s.writeFrame(protocol.FrameAuthErr, []byte(msg))
}

// close 幂等关闭底层连接。
func (s *session) close() {
	if s.closed.CompareAndSwap(false, true) {
		s.conn.Close()
	}
}

// readLoop 逐帧读取并分发：PING→PONG、AUDIO→转发对端、其余丢弃（向前兼容）。
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
			s.mu.Lock()
			peer := s.roles[otherRole(sess.role)]
			s.mu.Unlock()
			if peer != nil && peer != sess {
				peer.writeFrame(protocol.FrameAudio, payload)
			}
			// 计数含未桥接时被丢弃的帧：反映的是"对端发来了什么"
			sess.audioFrames.Add(1)
			sess.audioBytes.Add(int64(len(payload)))
			// 未桥接时到达的 AUDIO 直接丢弃（设计文档 §4.1）
		default:
			// 未知类型：payload 已被 ReadFrame 消费，继续运行（向前兼容）
		}
	}
}
