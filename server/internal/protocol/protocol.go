// Package protocol 定义 remote-voice 线路协议：
// 所有帧统一为 [1B type][4B length BigEndian][payload(length 字节)]。
// v3（设备自助入网）：手机只带配对秘密哈希认证，Mac 以本机设备身份认证后注册/热更秘密，
// relay 按秘密哈希路由、按 IP 限速防爆破。本包只负责帧的编解码与常量定义。
package protocol

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
)

// 帧类型常量（设计文档 §4.1；v3 沿用既有帧类型）
const (
	FrameAuth      byte = 0x01 // C→S 认证请求，payload 为 JSON
	FrameAuthOK    byte = 0x02 // S→C 认证成功，payload 为 JSON（手机侧含 mac 设备名）
	FrameAuthErr   byte = 0x03 // S→C 认证失败，payload 为 UTF-8 错误描述，随后关闭连接
	FrameAudio     byte = 0x04 // 桥接后双向透传的音频帧（服务器不解析内容）
	FramePing      byte = 0x05 // 心跳请求（双向）
	FramePong      byte = 0x06 // 心跳应答（双向）
	FramePeerState byte = 0x07 // S→C 对端状态通知，payload 1 字节：0x01 上线 / 0x00 掉线
	FrameRegister  byte = 0x08 // Mac→S 注册/热更秘密（可随时重发实现热更）
	FrameEvent     byte = 0x09 // S→Mac 事件通知（认证成功/对秘密试探失败），仅元数据
	FrameTalk      byte = 0x0A // 手机→Mac 说话状态（PTT 按下/松开），payload 1 字节，桥接透传不解析
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

// 协议版本与角色取值（AUTH payload）。v2 保留给旧 Mac 的可选兼容路径。
const (
	LegacyProtoVersion = 2
	ProtoVersion       = 3
	RolePhone          = "phone"
	RoleMac            = "mac"
)

// AUTH_ERR 原因枚举（ReasonAuthErr 的取值，客户端按此分支处理）
const (
	ReasonInvalidKey             = "invalid-key"              // 旧 v2 Mac 的 regkey 错误
	ReasonInvalidDevice          = "invalid-device"           // v3 Mac 设备身份错误
	ReasonDeviceBusy             = "device-busy"              // 同一设备已有在线会话
	ReasonDeviceStoreUnavailable = "device-store-unavailable" // 设备身份无法持久化
	ReasonInvalidSecret          = "invalid-secret"           // 秘密哈希查无此条目
	ReasonSecretExpired          = "secret-expired"           // 临时秘密已过期
	ReasonPeerBusy               = "peer-busy"                // 目标 Mac 已有桥接
	ReasonRateLimited            = "rate-limited"             // IP 处于防爆破锁定，恒定排除进一步探测
	ReasonBadRequest             = "bad-request"              // 请求格式/语义非法
	ReasonUnsupportedVer         = "unsupported-proto"
)

// Event 事件类型（FrameEvent 的 event 字段取值）
const (
	EventAuthOK   = "auth-ok"   // 手机携带本 Mac 的秘密认证成功并建立桥接
	EventAuthFail = "auth-fail" // 针对本 Mac 秘密的失败试探（原因见 reason）
)

// 秘密类型（EventNotify.Kind 的取值）
const (
	SecretKindPerm = "perm" // 永久秘密
	SecretKindTemp = "temp" // 临时秘密
)

// AuthRequest AUTH 帧 payload 结构。
// v3 Mac 用 DeviceID + DeviceKey 认证；v2 Mac 可用 Key（旧全局 regkey）兼容认证；
// 手机用 Secret（规范化后 SHA-256 hex）认证。
type AuthRequest struct {
	Role      string `json:"role"`
	Proto     int    `json:"proto"`
	Key       string `json:"key,omitempty"`        // v2 role=mac：旧全局注册密钥
	DeviceID  string `json:"device_id,omitempty"`  // v3 role=mac：本机持久设备 ID
	DeviceKey string `json:"device_key,omitempty"` // v3 role=mac：本机随机设备凭据
	Secret    string `json:"secret,omitempty"`     // role=phone：配对秘密哈希（hex）
}

// AuthOKPayload 认证成功应答（FrameAuthOK）。手机侧通过 mac 字段获得对端设备名。
type AuthOKPayload struct {
	Mac string `json:"mac,omitempty"`
}

// RegisterRequest REGISTER 帧 payload 结构（Mac→S）。
// perm / temp 均为规范化秘密的 SHA-256 hex；不注册的字段传空串即可，
// 同一 Mac 重复发送即热更（改名/换秘密/延长有效期）。
type RegisterRequest struct {
	Name    string `json:"name"`
	Perm    string `json:"perm"`
	Temp    string `json:"temp"`
	TempExp int64  `json:"temp_exp"` // unix 秒；temp 非空时必填
}

// EventNotify 事件通知（FrameEvent，方向 S→Mac）：
// auth-ok（ip/kind）与 auth-fail（ip/reason）两类，不含秘密原文。
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
