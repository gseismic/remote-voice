use serde::{Deserialize, Serialize};
use std::io;
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const FRAME_AUTH: u8 = 0x01;
pub const FRAME_AUTH_OK: u8 = 0x02;
pub const FRAME_AUTH_ERR: u8 = 0x03;
pub const FRAME_AUDIO: u8 = 0x04;
pub const FRAME_PING: u8 = 0x05;
pub const FRAME_PONG: u8 = 0x06;
pub const FRAME_PEER_STATE: u8 = 0x07;
pub const FRAME_REGISTER: u8 = 0x08;
pub const FRAME_EVENT: u8 = 0x09;
/// 手机→Mac 说话状态（PTT 按下/松开），payload 1 字节（0x01/0x00），server 透传
pub const FRAME_TALK: u8 = 0x0A;

pub const PEER_OFFLINE: u8 = 0x00;
pub const PEER_ONLINE: u8 = 0x01;
pub const MAX_PAYLOAD: usize = 65_536;
pub const PROTO_VERSION: i32 = 4;

pub const REASON_INVALID_DEVICE: &str = "invalid-device";
pub const REASON_DEVICE_BUSY: &str = "device-busy";
pub const REASON_DEVICE_STORE_UNAVAILABLE: &str = "device-store-unavailable";

#[derive(Debug, Error)]
pub enum ProtocolError {
    #[error("协议 I/O 失败: {0}")]
    Io(#[from] io::Error),
    #[error("帧长度超过上限")]
    FrameTooLarge,
    #[error("协议 JSON 无效: {0}")]
    Json(#[from] serde_json::Error),
    #[error("协议文本不是 UTF-8")]
    Utf8(#[from] std::string::FromUtf8Error),
}

#[derive(Debug, Clone, Serialize)]
pub struct AuthRequest<'a> {
    pub role: &'a str,
    pub proto: i32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_id: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_key: Option<&'a str>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AuthOkPayload {
    #[serde(default)]
    pub mac: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct RegisterRequest<'a> {
    pub name: &'a str,
    /// 服务器密码的规范化 SHA-256 hex（空=不改动服务器密码，只更新设备名）
    #[serde(skip_serializing_if = "str::is_empty")]
    pub password_hash: &'a str,
}

#[derive(Debug, Clone, Deserialize)]
pub struct EventNotify {
    #[serde(default)]
    pub event: String,
    #[serde(default)]
    pub ip: String,
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub reason: String,
}

pub async fn read_frame<R: AsyncRead + Unpin>(
    reader: &mut R,
) -> Result<(u8, Vec<u8>), ProtocolError> {
    let mut header = [0_u8; 5];
    reader.read_exact(&mut header).await?;
    let length = u32::from_be_bytes([header[1], header[2], header[3], header[4]]) as usize;
    if length > MAX_PAYLOAD {
        return Err(ProtocolError::FrameTooLarge);
    }
    let mut payload = vec![0_u8; length];
    reader.read_exact(&mut payload).await?;
    Ok((header[0], payload))
}

pub async fn write_frame<W: AsyncWrite + Unpin>(
    writer: &mut W,
    frame_type: u8,
    payload: &[u8],
) -> Result<(), ProtocolError> {
    if payload.len() > MAX_PAYLOAD {
        return Err(ProtocolError::FrameTooLarge);
    }
    let mut header = [0_u8; 5];
    header[0] = frame_type;
    header[1..].copy_from_slice(&(payload.len() as u32).to_be_bytes());
    writer.write_all(&header).await?;
    writer.write_all(payload).await?;
    writer.flush().await?;
    Ok(())
}

pub fn auth_payload(device_id: &str, device_key: &str) -> Result<Vec<u8>, ProtocolError> {
    Ok(serde_json::to_vec(&AuthRequest {
        role: "mac",
        proto: PROTO_VERSION,
        device_id: Some(device_id),
        device_key: Some(device_key),
    })?)
}

pub fn register_payload(name: &str, password_hash: &str) -> Result<Vec<u8>, ProtocolError> {
    Ok(serde_json::to_vec(&RegisterRequest {
        name,
        password_hash,
    })?)
}

pub fn parse_auth_ok(payload: &[u8]) -> Result<AuthOkPayload, ProtocolError> {
    Ok(serde_json::from_slice(payload)?)
}

pub fn parse_event(payload: &[u8]) -> Result<EventNotify, ProtocolError> {
    Ok(serde_json::from_slice(payload)?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::duplex;

    #[tokio::test]
    async fn frame_roundtrip_and_json_contract() {
        let (mut left, mut right) = duplex(1024);
        let payload = b"hello".to_vec();
        let writer = tokio::spawn(async move {
            write_frame(&mut left, FRAME_AUTH, &payload).await.unwrap();
        });
        let got = read_frame(&mut right).await.unwrap();
        writer.await.unwrap();
        assert_eq!(got, (FRAME_AUTH, b"hello".to_vec()));
        let json = auth_payload("mac-id", &"a".repeat(64)).unwrap();
        assert!(String::from_utf8(json).unwrap().contains("\"proto\":4"));
    }

    #[tokio::test]
    async fn oversized_frame_is_rejected_before_allocation() {
        let (mut left, mut right) = duplex(64);
        let writer = tokio::spawn(async move {
            // 测试目的：超过 65536 字节时，在分配 payload 前拒绝帧。
            left.write_all(&[FRAME_AUDIO, 0, 1, 0, 1]).await.unwrap();
        });
        let error = read_frame(&mut right).await.unwrap_err();
        writer.await.unwrap();
        assert!(matches!(error, ProtocolError::FrameTooLarge));
    }
}
