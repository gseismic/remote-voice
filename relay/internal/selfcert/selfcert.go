// Package selfcert 负责 relay 的自签证书管理：
// 首次启动生成自签证书（ECDSA P-256，有效期 3650 天）并落盘；
// 后续启动复用已有证书；对外提供证书 SHA-256 指纹计算与客户端校验辅助。
//
// 安全模型（设计文档 §5）：不使用 CA 信任链，客户端以"整张证书 DER 的
// SHA-256 hex 指纹"固定服务端身份，因此证书级 pinning 即可防中间人。
package selfcert

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	certFile = "cert.pem"
	keyFile  = "key.pem"
	validFor = 3650 * 24 * time.Hour
	orgName  = "remote-voice"
	commonCN = "remote-voice-relay"
)

// Ensure 返回 dataDir 下可用的 tls.Certificate 与指纹（64 位小写 hex）。
// 证书不存在则生成；存在则直接加载复用。
func Ensure(dataDir string) (tls.Certificate, string, error) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return tls.Certificate{}, "", fmt.Errorf("create data dir: %w", err)
	}
	certPath := filepath.Join(dataDir, certFile)
	keyPath := filepath.Join(dataDir, keyFile)

	if _, err := os.Stat(certPath); err == nil {
		if _, err := os.Stat(keyPath); err == nil {
			cert, err := tls.LoadX509KeyPair(certPath, keyPath)
			if err != nil {
				return tls.Certificate{}, "", fmt.Errorf("load existing keypair: %w", err)
			}
			fp, err := FingerprintOfCert(cert)
			return cert, fp, nil
		}
	}
	return generate(certPath, keyPath)
}

// generate 生成新的自签证书并写入磁盘。私钥文件权限固定 0600。
func generate(certPath, keyPath string) (tls.Certificate, string, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, "", fmt.Errorf("generate key: %w", err)
	}
	serialLimit := new(big.Int).Lsh(big.NewInt(1), 128)
	serial, err := rand.Int(rand.Reader, serialLimit)
	if err != nil {
		return tls.Certificate{}, "", fmt.Errorf("generate serial: %w", err)
	}
	tpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{Organization: []string{orgName}, CommonName: commonCN},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(validFor),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		DNSNames:              []string{"localhost"},
		IPAddresses:           []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		return tls.Certificate{}, "", fmt.Errorf("create certificate: %w", err)
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return tls.Certificate{}, "", fmt.Errorf("marshal key: %w", err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})

	// 私钥仅属主可读写
	if err := os.WriteFile(keyPath, keyPEM, 0o600); err != nil {
		return tls.Certificate{}, "", fmt.Errorf("write key: %w", err)
	}
	if err := os.WriteFile(certPath, certPEM, 0o644); err != nil {
		return tls.Certificate{}, "", fmt.Errorf("write cert: %w", err)
	}
	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return tls.Certificate{}, "", fmt.Errorf("parse generated keypair: %w", err)
	}
	fp, err := FingerprintOfCert(cert)
	return cert, fp, nil
}

// FingerprintOfCert 计算证书叶子 DER 的 SHA-256 指纹（小写 hex）。
func FingerprintOfCert(cert tls.Certificate) (string, error) {
	if len(cert.Certificate) == 0 {
		return "", errors.New("empty certificate chain")
	}
	sum := sha256.Sum256(cert.Certificate[0])
	return hex.EncodeToString(sum[:]), nil
}

// NormalizeFingerprint 规整用户输入的指纹：去除冒号/空格并转小写。
func NormalizeFingerprint(s string) string {
	s = strings.ReplaceAll(s, ":", "")
	s = strings.ReplaceAll(s, " ", "")
	return strings.ToLower(strings.TrimSpace(s))
}

// VerifyRawCerts 供 tls.Config.VerifyPeerCertificate 使用：
// 对服务端下发的首张证书 DER 计算 SHA-256 并与期望指纹比对。
func VerifyRawCerts(rawCerts [][]byte, expectedHex string) error {
	if len(rawCerts) == 0 {
		return errors.New("server did not present certificate")
	}
	want := NormalizeFingerprint(expectedHex)
	if want == "" {
		return errors.New("fingerprint not configured")
	}
	sum := sha256.Sum256(rawCerts[0])
	got := hex.EncodeToString(sum[:])
	if got != want {
		return fmt.Errorf("certificate fingerprint mismatch: got %s, want %s", got, want)
	}
	return nil
}
