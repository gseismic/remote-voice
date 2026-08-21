// Package protocol 定义 remote-voice v1 线路协议：
// 所有帧统一为 [1B type][4B length BigEndian][payload(length 字节)]。
// 本包只负责帧的编解码与常量定义，不包含任何会话语义。
package protocol

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
)

// 帧类型常量（设计文档 §4.1）
const (
	FrameAuth      byte = 0x01 // C→S 认证请求，payload 为 JSON
	FrameAuthOK    byte = 0x02 // S→C 认证成功
	FrameAuthErr   byte = 0x03 // S→C 认证失败，payload 为 UTF-8 错误描述，随后关闭连接
	FrameAudio     byte = 0x04 // 桥接后双向透传的音频帧（服务器不解析内容）
	FramePing      byte = 0x05 // 心跳请求（双向）
	FramePong      byte = 0x06 // 心跳应答（双向）
	FramePeerState byte = 0x07 // S→C 对端状态通知，payload 1 字节：0x01 上线 / 0x00 掉线
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

// 协议版本与角色取值（AUTH payload）
const (
	ProtoVersion = 1
	RolePhone    = "phone"
	RoleMac      = "mac"
)

// AuthRequest AUTH 帧 payload 结构
type AuthRequest struct {
	Role  string `json:"role"`
	Token string `json:"token"`
	Proto int    `json:"proto"`
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
