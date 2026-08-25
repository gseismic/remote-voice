// Package server 实现 relay 中转服务器（协议 v2 注册制）：
// TLS 接入 → Mac 以 regkey 认证并注册秘密哈希；手机只带秘密哈希认证 →
// 按哈希路由到归属 Mac 建立 1:1 桥接（AUDIO 帧双向透传）→
// 心跳超时管理 → 断线解桥通知 → 按 IP 防爆破限速。
// 服务器不接触秘密原文（两端只传 SHA-256 哈希），也不理解音频内容。
package server

import (
	"crypto/subtle"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"sync"
	"time"

	"remote-voice/relay/internal/protocol"
)

// 握手与统计相关的常量。
const (
	tlsHandshakeTimeout = 10 * time.Second     // TLS 握手截止，防慢速连接占住 goroutine
	acceptRetryDelay    = 100 * time.Millisecond // Accept 暂时性错误后的退避
	statsInterval       = 10 * time.Second      // 音频流量统计窗口
	registerTimeout     = 10 * time.Second      // Mac 认证后等待 REGISTER 帧时限
)

// Config 服务器配置。零值字段使用默认值。
type Config struct {
	RegKey      string        // Mac 注册密钥（必填），如文件/环境变量注入
	ReadTimeout time.Duration // 读超时（心跳判定），默认 40s
	AuthTimeout time.Duration // 等待 AUTH 帧超时，默认 10s
	TlsConfig   *tls.Config   // 非nil 时对每条连接执行 TLS 握手；nil 则按裸 TCP 处理（测试用）
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

// macReg 一台在线 Mac 的注册状态。由 Server.mu 保护。
type macReg struct {
	sess    *session
	name    string
	perm    string // 永久秘密哈希（""=未注册）
	temp    string // 临时秘密哈希（""=未注册）
	tempExp int64  // 临时秘密过期时刻（unix 秒）
}

// bridge 一座 1:1 桥接（Mac↔手机）。全局不超过 macs 数量。
// 由 Server.mu 保护；持有方通过会话的 bridge 字段感知。
type bridge struct {
	mac   *session
	phone *session
}

// Server 持有全部会话状态。
// 并发契约：mu 保护 macs/regs/ln/closed；session 写路径由各自 writeMu 保护。
type Server struct {
	cfg Config
	mu  sync.Mutex
	// macs: 在线 Mac 会话 → 其注册状态；regs: 秘密哈希 → 归属条目（注册表）
	macs  map[*session]*macReg
	regs  map[string]*regEntry
	rate  *rateLimiter
	ln    net.Listener
	closed bool
	quit   chan struct{}
}

// regEntry 秘密哈希的注册表条目：该哈希属于哪台 Mac、什么类型、何时过期。
type regEntry struct {
	mac     *session
	kind    string // protocol.SecretKindPerm / SecretKindTemp
	tempExp int64  // temp 类才有效
}

func New(cfg Config) *Server {
	return &Server{
		cfg:   cfg.withDefaults(),
		macs:  make(map[*session]*macReg),
		regs:  make(map[string]*regEntry),
		rate:  newRateLimiter(),
		quit:  make(chan struct{}),
	}
}

// Serve 在 ln 上接受连接并逐个处理，阻塞直到 ln 关闭或 Close 被调用。
func (s *Server) Serve(ln net.Listener) error {
	// ln 在锁内发布：Close 可能与 Serve 并发启动（PLAN-002 实证）。
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
			}
			// 监听器已被外部关闭（Close 与 Serve 并发的常规路径）
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			// 暂时性错误（如 fd 临时耗尽）：记录后退避重试，绝不因此终止服务。
			s.cfg.Logger.Printf("accept error (继续运行): %v", err)
			time.Sleep(acceptRetryDelay)
			continue
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
	live := make([]*session, 0, len(s.macs))
	for _, reg := range s.macs {
		live = append(live, reg.sess)
	}
	seen := map[*session]bool{}
	for _, e := range s.regs {
		if !seen[e.mac] {
			live = append(live, e.mac)
			seen[e.mac] = true
		}
	}
	s.mu.Unlock()

	if ln != nil {
		ln.Close()
	}
	for _, sess := range live {
		sess.close()
	}
}

// handleConn 处理单个连接的完整生命周期：
// 记录接入 → TLS 握手（可选，失败仅断本连接）→ 认证/注册/建桥 →
// 流量统计 → 读循环（音频转发、REGISTER 热更）→ 解桥注销通知。
func (s *Server) handleConn(conn net.Conn) {
	defer conn.Close()
	peer := conn.RemoteAddr().String()
	s.cfg.Logger.Printf("accepted peer=%s", peer)

	if s.cfg.TlsConfig != nil {
		tc := tls.Server(conn, s.cfg.TlsConfig)
		tc.SetDeadline(time.Now().Add(tlsHandshakeTimeout))
		if err := tc.Handshake(); err != nil {
			s.cfg.Logger.Printf("tls handshake failed peer=%s: %v", peer, err)
			return
		}
		tc.SetDeadline(time.Time{}) // 清除握手截止，后续读写由各自超时管理
		conn = tc
		s.cfg.Logger.Printf("tls handshake ok peer=%s", peer)
	}
	setNoDelay(conn)

	sess := &session{srv: s, conn: conn, peer: peer}
	done := make(chan struct{})
	defer close(done) // 终止 statsLoop，防 goroutine 泄漏

	if !s.authenticate(sess) {
		return
	}
	if sess.role == protocol.RoleMac {
		// Mac 认证后需在限时内发送 REGISTER；未注册不报错（无害，注册表无其秘密）
		okPayload, _ := json.Marshal(protocol.AuthOKPayload{})
		sess.writeFrame(protocol.FrameAuthOK, okPayload)
		sess.conn.SetReadDeadline(time.Now().Add(s.cfg.AuthTimeout))
	}

	s.cfg.Logger.Printf("authenticated role=%s peer=%s", sess.role, peer)

	go s.statsLoop(sess, done)
	s.readLoop(sess)

	if offPeer := s.detach(sess); offPeer != nil {
		offPeer.writeFrame(protocol.FramePeerState, []byte{protocol.PeerOffline})
		s.cfg.Logger.Printf("peer notified offline role=%s peer=%s", sess.role, offPeer.peer)
	}
	s.cfg.Logger.Printf("disconnected role=%s peer=%s", sess.role, peer)
}

// authenticate 读取并校验 AUTH 帧。
// Mac（regkey 常量时间比较）与手机（秘密哈希查注册表 + 桥接 check-and-reserve）
// 在锁外完成全部网络 IO，注册/建桥在临界区内原子完成。
// 返回 ok 表示认证成功（Mac 尚待 REGISTER）。
func (s *Server) authenticate(sess *session) bool {
	sess.conn.SetReadDeadline(time.Now().Add(s.cfg.AuthTimeout))
	typ, payload, err := protocol.ReadFrame(sess.conn)
	if err != nil {
		s.cfg.Logger.Printf("auth read error peer=%s: %v", sess.peer, err)
		return false
	}
	if typ != protocol.FrameAuth {
		sess.reject(protocol.ReasonBadRequest, "first frame must be AUTH")
		return false
	}
	var req protocol.AuthRequest
	if err := json.Unmarshal(payload, &req); err != nil {
		sess.reject(protocol.ReasonBadRequest, "malformed AUTH payload")
		return false
	}
	if req.Proto != protocol.ProtoVersion {
		sess.reject(protocol.ReasonUnsupportedVer, fmt.Sprintf("unsupported proto version %d", req.Proto))
		return false
	}
	switch req.Role {
	case protocol.RoleMac:
		return s.authMac(sess, req)
	case protocol.RolePhone:
		return s.authPhone(sess, req)
	default:
		sess.reject(protocol.ReasonBadRequest, "invalid role")
		return false
	}
}

// authMac regkey 常量时间比较；通过后登记在线 Mac（注册表空可待 REGISTER）。
func (s *Server) authMac(sess *session, req protocol.AuthRequest) bool {
	if subtle.ConstantTimeCompare([]byte(req.Key), []byte(s.cfg.RegKey)) != 1 {
		s.cfg.Logger.Printf("auth failed peer=%s role=mac (%s)", sess.peer, protocol.ReasonInvalidKey)
		sess.reject(protocol.ReasonInvalidKey, "invalid key")
		return false
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return false
	}
	if s.macs[sess] != nil {
		s.mu.Unlock()
		return false
	}
	sess.role = protocol.RoleMac
	s.macs[sess] = &macReg{sess: sess}
	s.mu.Unlock()
	s.cfg.Logger.Printf("mac authed peer=%s (等待 REGISTER)", sess.peer)
	return true
}

// authPhone 手机认证：查注册表 → 过期判定 → 桥接 check-and-reserve（原子）。
// 失败计数仅对 invalid-secret / secret-expired（I6），成功后清 IP 计数。
func (s *Server) authPhone(sess *session, req protocol.AuthRequest) bool {
	ip := hostOnly(sess.peer)
	now := time.Now()

	if s.rate.locked(ip, now) {
		s.cfg.Logger.Printf("auth failed peer=%s role=phone (%s)", sess.peer, protocol.ReasonRateLimited)
		sess.reject(protocol.ReasonRateLimited, "rate limited")
		return false
	}
	if req.Secret == "" || len(req.Secret) != 64 {
		s.cfg.Logger.Printf("auth failed peer=%s role=phone (%s)", sess.peer, protocol.ReasonInvalidSecret)
		s.rate.fail(ip, now)
		sess.reject(protocol.ReasonInvalidSecret, "invalid secret")
		return false
	}
	secret := lowerHex(req.Secret)

	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return false
	}
	entry, ok := s.regs[secret]
	if !ok || entry.mac == nil {
		s.mu.Unlock()
		s.cfg.Logger.Printf("auth failed peer=%s role=phone (%s)", sess.peer, protocol.ReasonInvalidSecret)
		s.rate.fail(ip, now)
		sess.reject(protocol.ReasonInvalidSecret, "invalid secret")
		return false
	}
	if entry.kind == protocol.SecretKindTemp && now.Unix() >= entry.tempExp {
		// 已知过期秘密：告知归属 Mac（失败事件），并计入限速
		mac := entry.mac
		s.mu.Unlock()
		s.cfg.Logger.Printf("auth failed peer=%s role=phone (%s)", sess.peer, protocol.ReasonSecretExpired)
		s.rate.fail(ip, now)
		sess.reject(protocol.ReasonSecretExpired, "secret expired")
		s.notifyMac(mac, protocol.EventNotify{
			Event: protocol.EventAuthFail, IP: ip, Reason: protocol.ReasonSecretExpired,
		})
		return false
	}
	reg := s.macs[entry.mac]
	if reg == nil || reg.sess != entry.mac {
		// 注册表指向的 Mac 已断开且尚未清理（理论竞态窗口）：按失效处理
		s.mu.Unlock()
		s.cfg.Logger.Printf("auth failed peer=%s role=phone (%s)", sess.peer, protocol.ReasonInvalidSecret)
		s.rate.fail(ip, now)
		sess.reject(protocol.ReasonInvalidSecret, "invalid secret")
		return false
	}
	macSess := entry.mac
	if macSess.bridge != nil {
		// 该 Mac 已有桥接：1:1 语义，拒绝新手机而不计入限速
		s.mu.Unlock()
		s.cfg.Logger.Printf("auth failed peer=%s role=phone (%s)", sess.peer, protocol.ReasonPeerBusy)
		sess.reject(protocol.ReasonPeerBusy, "peer busy")
		return false
	}
	// check-and-reserve：建桥并挂到两端的会话上（同一临界区，无竞态窗口）
	sess.role = protocol.RolePhone
	b := &bridge{mac: macSess, phone: sess}
	sess.bridge = b
	macSess.bridge = b
	s.mu.Unlock()

	s.rate.clear(ip)
	s.cfg.Logger.Printf("bridged role=phone peer=%s ↔ mac=%s peer=%s",
		sess.peer, reg.name, macSess.peer)

	// 双方 PEER_STATE(online)；AUTH_OK 先于状态帧发出（客户端契约），附设备名；
	// 归属 Mac 收 auth-ok 事件。全部网络 IO 在锁外完成。
	okPayload, _ := json.Marshal(protocol.AuthOKPayload{Mac: reg.name})
	sess.writeFrame(protocol.FrameAuthOK, okPayload)
	sess.writeFrame(protocol.FramePeerState, []byte{protocol.PeerOnline})
	macSess.writeFrame(protocol.FramePeerState, []byte{protocol.PeerOnline})
	s.notifyMac(macSess, protocol.EventNotify{
		Event: protocol.EventAuthOK, IP: ip, Kind: entry.kind,
	})
	return true
}

// notifyMac 向指定 Mac 会话发事件（不在锁内做网络 IO：调用方必须已释放 mu）。
func (s *Server) notifyMac(mac *session, ev protocol.EventNotify) {
	payload, err := json.Marshal(ev)
	if err != nil {
		return
	}
	mac.writeFrame(protocol.FrameEvent, payload)
}

// detach 会话断开时的清理：释放注册哈希/解桥，返回需要收到 offline 事件的幸存端。
// 仅当注册表中哈希仍指向本会话时才删除，防止误删新会话的注册。
func (s *Server) detach(sess *session) *session {
	s.mu.Lock()
	defer s.mu.Unlock()

	if sess.role == protocol.RoleMac {
		if reg, ok := s.macs[sess]; ok {
			delete(s.macs, sess)
			if e, ok := s.regs[reg.perm]; ok && e.mac == sess {
				delete(s.regs, reg.perm)
			}
			if e, ok := s.regs[reg.temp]; ok && e.mac == sess {
				delete(s.regs, reg.temp)
			}
		}
	}
	b := sess.bridge
	if b == nil {
		return nil
	}
	// 解桥：只有桥的另一端在线才需要通知
	sess.bridge = nil
	switch {
	case b.mac == sess:
		if b.phone != nil {
			b.phone.bridge = nil
			return b.phone
		}
	case b.phone == sess:
		if b.mac != nil {
			if reg, ok := s.macs[b.mac]; ok && reg.sess == b.mac {
				reg.sess.bridge = nil
				return reg.sess
			}
		}
	}
	return nil
}

// handleRegister 处理 Mac 的 REGISTER 帧（注册/热更秘密）。
// 在调用方临界区外已做 JSON 校验与语义检查。
func (s *Server) handleRegister(sess *session, payload []byte) {
	var req protocol.RegisterRequest
	if err := json.Unmarshal(payload, &req); err != nil {
		s.cfg.Logger.Printf("register malformed peer=%s: %v", sess.peer, err)
		return // 不拒绝连接：REGISTER 只是外设消息，错误帧丢弃
	}
	if req.Name == "" {
		s.cfg.Logger.Printf("register missing name peer=%s", sess.peer)
		req.Name = "未命名"
	}
	if req.Perm == "" && req.Temp == "" {
		// 解注册：清空本 Mac 全部秘密（e.g. 停用永久秘密且无临时秘密）
		s.reapplyRegister(sess, req.Name, "", "", 0)
		return
	}
	if req.Perm != "" {
		req.Perm = lowerHex(req.Perm)
		if len(req.Perm) != 64 {
			s.cfg.Logger.Printf("register bad perm peer=%s", sess.peer)
			return
		}
	}
	if req.Temp != "" {
		req.Temp = lowerHex(req.Temp)
		if len(req.Temp) != 64 || req.TempExp <= time.Now().Unix() {
			s.cfg.Logger.Printf("register bad temp peer=%s", sess.peer)
			return
		}
	}
	s.reapplyRegister(sess, req.Name, req.Perm, req.Temp, req.TempExp)
	s.cfg.Logger.Printf("mac registered name=%q peer=%s perm=%t temp=%t",
		req.Name, sess.peer, req.Perm != "", req.Temp != "")
}

// reapplyRegister 原子替换某 Mac 的注册内容：先摘旧哈希再挂新哈希。
func (s *Server) reapplyRegister(sess *session, name, perm, temp string, tempExp int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	reg, ok := s.macs[sess]
	if !ok {
		return // 会话已注销（理论竞态窗口）
	}
	if reg.perm != "" {
		if e, ok := s.regs[reg.perm]; ok && e.mac == sess {
			delete(s.regs, reg.perm)
		}
	}
	if reg.temp != "" {
		if e, ok := s.regs[reg.temp]; ok && e.mac == sess {
			delete(s.regs, reg.temp)
		}
	}
	reg.name = name
	reg.perm, reg.temp, reg.tempExp = perm, temp, tempExp
	if perm != "" {
		s.regs[perm] = &regEntry{mac: sess, kind: protocol.SecretKindPerm}
	}
	if temp != "" {
		s.regs[temp] = &regEntry{mac: sess, kind: protocol.SecretKindTemp, tempExp: tempExp}
	}
}

// statsLoop 周期性输出音频流量统计。仅当窗口内有增量才打日志，
// 避免逐帧刷屏（50 帧/s）也避免空闲连接制造噪音。
func (s *Server) statsLoop(sess *session, done <-chan struct{}) {
	ticker := time.NewTicker(statsInterval)
	defer ticker.Stop()
	var lastFrames, lastBytes int64
	for {
		select {
		case <-done:
			return
		case <-ticker.C:
			frames := sess.audioFrames.Load()
			if delta := frames - lastFrames; delta > 0 {
				s.cfg.Logger.Printf("stats role=%s audio_frames=%d audio_bytes=%d (窗口 %v)",
					sess.role, delta, sess.audioBytes.Load()-lastBytes, statsInterval)
			}
			lastFrames = frames
			lastBytes = sess.audioBytes.Load()
		}
	}
}

// hostOnly 从 net.Addr 字符串中抽出主机部分（限速键不含端口）。
// 极端畸形输入返回原始字符串（退化为整串计键，不影响正确性）。
func hostOnly(addr string) string {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	return host
}

// lowerHex 归一化 hex（大写 → 小写）。秘密规范化已在客户端完成，此处只是统一 strcmp。
func lowerHex(in string) string {
	b := []byte(in)
	for i, c := range b {
		if c >= 'A' && c <= 'F' {
			b[i] = c + ('a' - 'A')
		}
	}
	return string(b)
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
