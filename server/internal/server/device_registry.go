package server

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const deviceRegistryVersion = 1

// deviceRecord 是 server 保存的 Mac 身份记录。
// 只保存设备凭据哈希，不保存客户端传来的设备凭据原文。
type deviceRecord struct {
	CredentialHash string `json:"credential_hash"`
	CreatedAt      int64  `json:"created_at"`
}

type deviceRegistryFile struct {
	Version int                     `json:"version"`
	Devices map[string]deviceRecord `json:"devices"`
}

// deviceRegistry 管理多台 Mac 的持久身份。
// 调用方必须在 Server.mu 已持有时调用 verifyOrEnroll，保证检查、登记和在线占位原子化。
type deviceRegistry struct {
	path    string
	devices map[string]deviceRecord
	loadErr error
}

func newDeviceRegistry(path string) (*deviceRegistry, error) {
	r := &deviceRegistry{path: path, devices: make(map[string]deviceRecord)}
	if path == "" {
		return r, nil
	}
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return r, nil
	}
	if err != nil {
		return nil, fmt.Errorf("读取设备身份文件: %w", err)
	}
	if err := os.Chmod(path, 0o600); err != nil {
		return nil, fmt.Errorf("设置设备身份文件权限: %w", err)
	}
	var file deviceRegistryFile
	if err := json.Unmarshal(b, &file); err != nil {
		return nil, fmt.Errorf("解析设备身份文件: %w", err)
	}
	if file.Version != deviceRegistryVersion {
		return nil, fmt.Errorf("设备身份文件版本不支持: %d", file.Version)
	}
	for id, record := range file.Devices {
		if !validDeviceID(id) || !validCredentialHash(record.CredentialHash) {
			return nil, fmt.Errorf("设备身份文件包含非法记录: %q", id)
		}
		r.devices[id] = record
	}
	return r, nil
}

// verifyOrEnroll 首次见到设备 ID 时登记凭据，已存在时校验凭据。
// 返回值 true 表示认证通过；新记录只有在持久化成功后才写入内存。
func (r *deviceRegistry) verifyOrEnroll(deviceID, deviceKey string) (bool, error) {
	if r.loadErr != nil {
		return false, r.loadErr
	}
	digest := credentialDigest(deviceKey)
	if old, ok := r.devices[deviceID]; ok {
		return constantTimeEqual(old.CredentialHash, digest), nil
	}
	record := deviceRecord{CredentialHash: digest, CreatedAt: time.Now().Unix()}
	updated := make(map[string]deviceRecord, len(r.devices)+1)
	for id, old := range r.devices {
		updated[id] = old
	}
	updated[deviceID] = record
	if err := r.persist(updated); err != nil {
		return false, err
	}
	r.devices = updated
	return true, nil
}

func (r *deviceRegistry) persist(devices map[string]deviceRecord) error {
	if r.path == "" {
		return nil
	}
	dir := filepath.Dir(r.path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fmt.Errorf("创建设备身份目录: %w", err)
	}
	tmp, err := os.CreateTemp(dir, ".devices-*.tmp")
	if err != nil {
		return fmt.Errorf("创建设备身份临时文件: %w", err)
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName) // 成功 rename 后此处为幂等清理
	if err := tmp.Chmod(0o600); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("设置设备身份文件权限: %w", err)
	}
	file := deviceRegistryFile{Version: deviceRegistryVersion, Devices: devices}
	enc := json.NewEncoder(tmp)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(file); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("写入设备身份文件: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("同步设备身份文件: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("关闭设备身份文件: %w", err)
	}
	if err := os.Rename(tmpName, r.path); err != nil {
		return fmt.Errorf("替换设备身份文件: %w", err)
	}
	return nil
}

func credentialDigest(deviceKey string) string {
	sum := sha256.Sum256([]byte(strings.ToLower(deviceKey)))
	return hex.EncodeToString(sum[:])
}

func validCredentialHash(value string) bool {
	if len(value) != sha256.Size*2 {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}

func validDeviceKey(value string) bool {
	return validCredentialHash(value)
}

func validDeviceID(value string) bool {
	if len(value) < 8 || len(value) > 128 {
		return false
	}
	for _, r := range value {
		if !(r >= 'a' && r <= 'z') && !(r >= 'A' && r <= 'Z') &&
			!(r >= '0' && r <= '9') && !strings.ContainsRune("-_.:", r) {
			return false
		}
	}
	return true
}

// constantTimeEqual 比较固定长度的十六进制摘要，避免身份存在性泄露过多时序信息。
func constantTimeEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	var diff byte
	for i := range a {
		diff |= a[i] ^ b[i]
	}
	return diff == 0
}
