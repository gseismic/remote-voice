// Package protocol 定义 remote-voice 线路协议：
// 所有帧统一为 [1B type][4B length BigEndian][payload(length 字节)]。
// v4（服务器密码 + Mac 目录）：手机带服务器密码哈希认证（target 为空=登录会话，
// 非空=与指定 Mac 建桥）；Mac 以本机设备身份认证后经 REGISTER 上报设备名与服务器密码哈希
// （服务器持久化，last-write-wins）；LIST 帧返回目录（含离线已登记 Mac）。
// 服务器不接触密码原文（两端只传 SHA-256 哈希），也不理解音频内容。
package protocol

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
)

// 帧类型常量（设计文档 §4.1；v4 新增 LIST）
const (
	FrameAuth      byte = 0x01 // C→S 认证请求，payload 为 JSON
	FrameAuthOK    byte = 0x02 // S→C 认证成功，payload 为 JSON（建桥时含 mac 设备名）
	FrameAuthErr   byte = 0x03 // S→C 认证失败，payload 为 UTF-8 错误描述，随后关闭连接
	FrameAudio     byte = 0x04 // 桥接后双向透传的音频帧（服务器不解析内容）
	FramePing      byte = 0x05 // 心跳请求（双向）
	FramePong      byte = 0x06 // 心跳应答（双向）
	FramePeerState byte = 0x07 // S→C 对端状态通知，payload 1 字节：0x01 上线 / 0x00 掉线
	FrameRegister  byte = 0x08 // Mac→S 注册/热更（设备名 + 服务器密码哈希，可随时重发）
	FrameEvent     byte = 0x09 // S→Mac 事件通知（认证成功/密码试探失败），仅元数据
	FrameTalk      byte = 0x0A // 手机→Mac 说话状态（PTT 按下/松开），payload 1 字节，桥接透传不解析
	FrameList      byte = 0x0B // 手机↔S 目录查询：C→S 空 payload；S→C 为 ListPayload JSON
)

// 对端状态字节值（FramePeerState 的 payload）
const (
	PeerOnline  byte = 0x01
	PeerOffline byte = 0x00
)

// MaxPayloadSize 单帧 payload 上限，超限视为恶意流量立即断开（设计文档 §4.1）
const MaxPayloadSize = 65536

// HeaderLen 帧头长度：1 字节 type + 4 字节 length
const HeaderLen = 5

// 协议版本与角色取值（AUTH payload）。v2/v3 为历史版本（v2 全局 regkey、
// v3 per-Mac 秘密），v4 起不再提供兼容路径。
const (
	LegacyProtoVersion = 2
	ProtoVersion       = 4
	RolePhone          = "phone"
	RoleMac            = "mac"
)

// AUTH_ERR 原因枚举（ReasonAuthErr 的取值，客户端按此分支处理）
const (
	ReasonInvalidKey             = "invalid-key"              // 旧 v2 Mac 的 regkey 错误（历史）
	ReasonInvalidDevice          = "invalid-device"           // v3+ Mac 设备身份错误
	ReasonDeviceBusy             = "device-busy"              // 同一设备已有在线会话
	ReasonDeviceStoreUnavailable = "device-store-unavailable" // 设备身份无法持久化
	ReasonInvalidSecret          = "invalid-secret"           // 手机：服务器密码错误 / 服务器未设密码
	ReasonInvalidTarget          = "invalid-target"           // 手机：target 指向的 Mac 不存在或离线
	ReasonPeerBusy               = "peer-busy"                // 目标 Mac 已有桥接
	ReasonRateLimited            = "rate-limited"             // IP 处于防爆破锁定，恒定排除进一步探测
	ReasonBadRequest             = "bad-request"              // 请求格式/语义非法
	ReasonUnsupportedVer         = "unsupported-proto"
)

// Event 事件类型（FrameEvent 的 event 字段取值）
const (
	EventAuthOK   = "auth-ok"   // 手机认证成功并建立桥接（target 指向本 Mac）
	EventAuthFail = "auth-fail" // 针对服务器密码的失败试探（原因见 reason）
)

// AuthRequest AUTH 帧 payload 结构。
// v4 Mac 用 DeviceID + DeviceKey 认证；手机用 Secret（服务器密码的规范化 SHA-256 hex），
// Target 为目标 Mac 的 device_id：空=登录会话（仅 LIST/状态），非空=建立到该 Mac 的桥。
type AuthRequest struct {
	Role      string `json:"role"`
	Proto     int    `json:"proto"`
	Key       string `json:"key,omitempty"`        // 历史 v2 role=mac：全局注册密钥
	DeviceID  string `json:"device_id,omitempty"`  // role=mac：本机持久设备 ID
	DeviceKey string `json:"device_key,omitempty"` // role=mac：本机随机设备凭据
	Secret    string `json:"secret,omitempty"`     // role=phone：服务器密码哈希（hex）
	Target    string `json:"target,omitempty"`     // role=phone：目标 Mac device_id
}

// AuthOKPayload 认证成功应答（FrameAuthOK）。建桥时手机侧通过 mac 字段获得对端设备名。
type AuthOKPayload struct {
	Mac string `json:"mac,omitempty"`
}

// RegisterRequest REGISTER 帧 payload 结构（Mac→S，可重发热更）。
// Name 为目录展示名（持久化）；PasswordHash 为服务器密码的 SHA-256 hex（空=不改密码）。
type RegisterRequest struct {
	Name         string `json:"name"`
	PasswordHash string `json:"password_hash,omitempty"`
}

// MacInfo 目录条目（FrameList 的 S→C payload）。
// Online=当前有在线会话；Busy=当前已被桥接占用。
type MacInfo struct {
	DeviceID string `json:"device_id"`
	Name     string `json:"name"`
	Online   bool   `json:"online"`
	Busy     bool   `json:"busy"`
}

// ListPayload 目录应答（FrameList，方向 S→C）。
type ListPayload struct {
	Macs []MacInfo `json:"macs"`
}

// EventNotify 事件通知（FrameEvent，方向 S→Mac）：
// auth-ok（ip）与 auth-fail（ip/reason）两类，不含密码原文。
type EventNotify struct {
	Event  string `json:"event"`
	IP     string `json:"ip"`
	Kind   string `json:"kind,omitempty"`
	Reason string `json:"reason,omitempty"`
}

// ErrFrameTooLarge 表示对端发送了超过上限的帧，调用方必须断开连接
var ErrFrameTooLarge = errors.New("frame payload exceeds limit")

// ReadFrame 从 r 读取一帧。返回帧类型与 payload。
// 注意：本函数不区分帧类型合法性，未知类型由上层决定丢弃策略（向前兼容）。
func ReadFrame(r io.Reader) (typ byte, payload []byte, err error) {
	var head [HeaderLen]byte
	if _, err = io.ReadFull(r, head[:]); err != nil {
		return 0, nil, fmt.Errorf("read header: %w", err)
	}
	typ = head[0]
	length := binary.BigEndian.Uint32(head[1:])
	if length > MaxPayloadSize {
		return 0, nil, ErrFrameTooLarge
	}
	payload = make([]byte, length)
	if _, err = io.ReadFull(r, payload); err != nil {
		return 0, nil, fmt.Errorf("read payload: %w", err)
	}
	return typ, payload, nil
}
// WriteFrame 写出一帧。头部与 payload 合并为一次 Write，
// 配合上层写锁保证帧不会被其他并发写撕裂。
func WriteFrame(w io.Writer, typ byte, payload []byte) error {
	if len(payload) > MaxPayloadSize {
		return ErrFrameTooLarge
	}
	buf := make([]byte, HeaderLen+len(payload))
	buf[0] = typ
	binary.BigEndian.PutUint32(buf[1:], uint32(len(payload)))
	copy(buf[HeaderLen:], payload)
	_, err := w.Write(buf)
	return err
}
