// Package server 实现 relay 中转服务器（协议 v4 服务器密码 + Mac 目录）：
// TLS 接入 → Mac 以本机设备身份认证并经 REGISTER 上报设备名/服务器密码哈希；
// 手机带服务器密码哈希认证（target 空=登录会话，非空=按 device_id 路由到目标 Mac
// 建立 1:1 桥接，AUDIO 帧双向透传）→ LIST 帧返回目录（含离线已登记 Mac）→
// 心跳超时管理 → 断线解桥通知 → 按 IP 防爆破限速。
// 服务器不接触密码原文（两端只传 SHA-256 哈希），也不理解音频内容。
package server

import (
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"sync"
	"time"

	"remote-voice/server/internal/protocol"
)

// 握手与统计相关的常量。
const (
	tlsHandshakeTimeout = 10 * time.Second       // TLS 握手截止，防慢速连接占住 goroutine
	acceptRetryDelay    = 100 * time.Millisecond // Accept 暂时性错误后的退避
	statsInterval       = 10 * time.Second       // 音频流量统计窗口
	registerTimeout     = 10 * time.Second       // Mac 认证后等待 REGISTER 帧时限
)

// Config 服务器配置。零值字段使用默认值。
type Config struct {
	DeviceStorePath string        // v3+ Mac 身份与服务器密码文件；空值表示仅内存测试仓库
	ReadTimeout     time.Duration // 读超时（心跳判定），默认 40s
	AuthTimeout     time.Duration // 等待 AUTH 帧超时，默认 10s
	TlsConfig       *tls.Config   // 非nil 时对每条连接执行 TLS 握手；nil 则按裸 TCP 处理（测试用）
	Logger          *log.Logger
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
	sess     *session
	deviceID string
	name     string // 目录展示名（REGISTER 上报；持久化在 deviceRegistry）
}

// bridge 一座 1:1 桥接（Mac↔手机）。全局不超过 macs 数量。
// 由 Server.mu 保护；持有方通过会话的 bridge 字段感知。
type bridge struct {
	mac   *session
	phone *session
}

// Server 持有全部会话状态。
// 并发契约：mu 保护 macs/devicesOnline/ln/closed；session 写路径由各自 writeMu 保护。
type Server struct {
	cfg Config
	mu  sync.Mutex
	// macs: 在线 Mac 会话 → 其注册状态；devicesOnline: device_id → 在线会话
	macs          map[*session]*macReg
	devicesOnline map[string]*session
	deviceStore   *deviceRegistry
	rate          *rateLimiter
	ln            net.Listener
	closed        bool
	quit          chan struct{}
}

// New 创建 server。生产入口应优先使用 NewWithError，以便在设备身份文件损坏时启动即失败；
// 旧调用方使用本函数时，身份仓库错误会被保留并在 Mac 认证时拒绝。
func New(cfg Config) *Server {
	s, err := NewWithError(cfg)
	if err == nil {
		return s
	}
	cfg = cfg.withDefaults()
	cfg.Logger.Printf("设备身份仓库不可用（Mac 认证将被拒绝）: %v", err)
	return newServer(cfg, &deviceRegistry{
		path: cfg.DeviceStorePath, devices: make(map[string]deviceRecord), loadErr: err,
	})
}

// NewWithError 创建并加载 server 的设备身份仓库。
func NewWithError(cfg Config) (*Server, error) {
	cfg = cfg.withDefaults()
	store, err := newDeviceRegistry(cfg.DeviceStorePath)
	if err != nil {
		return nil, err
	}
	return newServer(cfg, store), nil
}

func newServer(cfg Config, store *deviceRegistry) *Server {
	return &Server{
		cfg:           cfg,
		macs:          make(map[*session]*macReg),
		devicesOnline: make(map[string]*session),
		deviceStore:   store,
		rate:          newRateLimiter(),
		quit:          make(chan struct{}),
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
// v4 Mac 使用设备身份自助入网；手机（服务器密码哈希 + 可选 target）认证后
// 按需建桥。在锁外完成全部网络 IO，注册/建桥在临界区内原子完成。
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
		return s.authMacDevice(sess, req)
	case protocol.RolePhone:
		return s.authPhone(sess, req)
	default:
		sess.reject(protocol.ReasonBadRequest, "invalid role")
		return false
	}
}

// authMacDevice 完成 v3 Mac 的首次自助登记、凭据校验和在线会话占位。
// 首次登记必须在 Server.mu 内完成，避免同一 device_id 并发抢占。
func (s *Server) authMacDevice(sess *session, req protocol.AuthRequest) bool {
	if !validDeviceID(req.DeviceID) || !validDeviceKey(req.DeviceKey) {
		s.cfg.Logger.Printf("auth failed peer=%s role=mac (%s)", sess.peer, protocol.ReasonInvalidDevice)
		sess.reject(protocol.ReasonInvalidDevice, "invalid device identity")
		return false
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return false
	}
	ok, err := s.deviceStore.verifyOrEnroll(req.DeviceID, req.DeviceKey)
	if err != nil {
		s.mu.Unlock()
		s.cfg.Logger.Printf("auth failed peer=%s role=mac (%s): %v", sess.peer, protocol.ReasonDeviceStoreUnavailable, err)
		sess.reject(protocol.ReasonDeviceStoreUnavailable, "device store unavailable")
		return false
	}
	if !ok {
		s.mu.Unlock()
		s.cfg.Logger.Printf("auth failed peer=%s role=mac (%s)", sess.peer, protocol.ReasonInvalidDevice)
		sess.reject(protocol.ReasonInvalidDevice, "invalid device identity")
		return false
	}
	if existing := s.devicesOnline[req.DeviceID]; existing != nil {
		s.mu.Unlock()
		s.cfg.Logger.Printf("auth failed peer=%s role=mac (%s)", sess.peer, protocol.ReasonDeviceBusy)
		sess.reject(protocol.ReasonDeviceBusy, "device already online")
		return false
	}
	sess.role = protocol.RoleMac
	sess.deviceID = req.DeviceID
	s.macs[sess] = &macReg{sess: sess, deviceID: req.DeviceID}
	s.devicesOnline[req.DeviceID] = sess
	s.mu.Unlock()
	s.cfg.Logger.Printf("mac authed device=%s peer=%s (等待 REGISTER)", req.DeviceID, sess.peer)
	return true
}

// authPhone 手机认证（协议 v4）：
// 1) 服务器密码哈希常时比较（服务端未设密码一律拒绝）；失败计入 IP 防爆破。
// 2) target 为空 = 登录会话：仅目录（LIST）与在线状态，不建桥。
// 3) target 非空 = 与目标 Mac 建桥（check-and-reserve 原子完成，Mac 已有桥则拒绝）。
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
	expected := s.deviceStore.PasswordHash()
	if expected == "" || !constantTimeEqual(expected, secret) {
		s.mu.Unlock()
		s.cfg.Logger.Printf("auth failed peer=%s role=phone (%s)", sess.peer, protocol.ReasonInvalidSecret)
		s.rate.fail(ip, now)
		sess.reject(protocol.ReasonInvalidSecret, "invalid secret")
		return false
	}
	sess.role = protocol.RolePhone
	if req.Target == "" {
		s.mu.Unlock()
		s.rate.clear(ip)
		s.cfg.Logger.Printf("phone logged in peer=%s (登录会话)", sess.peer)
		okPayload, _ := json.Marshal(protocol.AuthOKPayload{})
		sess.writeFrame(protocol.FrameAuthOK, okPayload)
		return true
	}
	macSess := s.devicesOnline[req.Target]
	if macSess == nil {
		s.mu.Unlock()
		// 密码已验证成功，target 离线不是密码试探：不计入限速
		s.cfg.Logger.Printf("auth failed peer=%s role=phone (%s target=%s)",
			sess.peer, protocol.ReasonInvalidTarget, req.Target)
		sess.reject(protocol.ReasonInvalidTarget, "target not online")
		return false
	}
	reg := s.macs[macSess]
	if reg == nil || reg.sess != macSess {
		// 在线表指向已断开会话（理论竞态窗口）：按离线处理
		s.mu.Unlock()
		s.cfg.Logger.Printf("auth failed peer=%s role=phone (%s target=%s)",
			sess.peer, protocol.ReasonInvalidTarget, req.Target)
		sess.reject(protocol.ReasonInvalidTarget, "target not online")
		return false
	}
	if macSess.bridge != nil {
		// 该 Mac 已有桥接：1:1 语义，拒绝新手机而不计入限速
		s.mu.Unlock()
		s.cfg.Logger.Printf("auth failed peer=%s role=phone (%s)", sess.peer, protocol.ReasonPeerBusy)
		sess.reject(protocol.ReasonPeerBusy, "peer busy")
		return false
	}
	// check-and-reserve：建桥并挂到两端的会话上（同一临界区，无竞态窗口）
	b := &bridge{mac: macSess, phone: sess}
	sess.bridge = b
	macSess.bridge = b
	macName := reg.name
	s.mu.Unlock()

	s.rate.clear(ip)
	s.cfg.Logger.Printf("bridged role=phone peer=%s ↔ mac=%q device=%s peer=%s",
		sess.peer, macName, req.Target, macSess.peer)

	// 双方 PEER_STATE(online)；AUTH_OK 先于状态帧发出（客户端契约），附设备名；
	// 目标 Mac 收 auth-ok 事件。全部网络 IO 在锁外完成。
	okPayload, _ := json.Marshal(protocol.AuthOKPayload{Mac: macName})
	sess.writeFrame(protocol.FrameAuthOK, okPayload)
	sess.writeFrame(protocol.FramePeerState, []byte{protocol.PeerOnline})
	macSess.writeFrame(protocol.FramePeerState, []byte{protocol.PeerOnline})
	s.notifyMac(macSess, protocol.EventNotify{
		Event: protocol.EventAuthOK, IP: ip,
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

// detach 会话断开时的清理：注销在线占位并解桥，返回需要收到 offline 事件的幸存端。
func (s *Server) detach(sess *session) *session {
	s.mu.Lock()
	defer s.mu.Unlock()

	if sess.role == protocol.RoleMac {
		if reg, ok := s.macs[sess]; ok {
			delete(s.macs, sess)
			if reg.deviceID != "" && s.devicesOnline[reg.deviceID] == sess {
				delete(s.devicesOnline, reg.deviceID)
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

// handleRegister 处理 Mac 的 REGISTER 帧（协议 v4：设备名 + 可选服务器密码热更）。
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
	passwordHash := ""
	if req.PasswordHash != "" {
		req.PasswordHash = lowerHex(req.PasswordHash)
		if len(req.PasswordHash) != 64 {
			s.cfg.Logger.Printf("register bad password_hash peer=%s", sess.peer)
		} else {
			passwordHash = req.PasswordHash
		}
	}

	// 持久化在 Server.mu 内完成（与 verifyOrEnroll 相同的并发契约），防止并发写互相覆盖
	s.mu.Lock()
	reg, ok := s.macs[sess]
	if !ok {
		s.mu.Unlock()
		return // 会话已注销（理论竞态窗口）
	}
	reg.name = req.Name
	deviceID := reg.deviceID
	if err := s.deviceStore.SetDeviceName(deviceID, req.Name); err != nil {
		s.cfg.Logger.Printf("register persist name failed device=%s: %v", deviceID, err)
	}
	if passwordHash != "" {
		if err := s.deviceStore.SetPasswordHash(passwordHash); err != nil {
			s.mu.Unlock()
			s.cfg.Logger.Printf("register persist password failed device=%s: %v", deviceID, err)
			return
		}
	}
	s.mu.Unlock()

	s.cfg.Logger.Printf("mac registered name=%q device=%s peer=%s password=%t",
		req.Name, deviceID, sess.peer, passwordHash != "")
}

// handleList 回复 Mac 目录（LIST 帧，登录/桥接会话均可查询）。
// 目录含离线已登记设备：名字取自 deviceRegistry 持久化记录。
func (s *Server) handleList(sess *session) {
	s.mu.Lock()
	online := make(map[string]bool, len(s.devicesOnline))
	infos := make([]protocol.MacInfo, 0, len(s.devicesOnline))
	for id, macSess := range s.devicesOnline {
		online[id] = true
		name := ""
		if reg := s.macs[macSess]; reg != nil {
			name = reg.name
		}
		infos = append(infos, protocol.MacInfo{
			DeviceID: id,
			Name:     name,
			Online:   true,
			Busy:     macSess.bridge != nil,
		})
	}
	for id, rec := range s.deviceStore.Devices() {
		if online[id] {
			continue
		}
		infos = append(infos, protocol.MacInfo{DeviceID: id, Name: rec.Name})
	}
	s.mu.Unlock()

	payload, err := json.Marshal(protocol.ListPayload{Macs: infos})
	if err != nil {
		return
	}
	sess.writeFrame(protocol.FrameList, payload)
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
