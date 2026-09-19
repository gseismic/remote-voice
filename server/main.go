// relay 中转服务器入口（协议 v3）。
// 新 Mac 首次连接会自助登记设备身份；regkey 仅作为旧 v2 客户端的可选兼容参数。
package main

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	_ "net/http/pprof"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"

	"remote-voice/server/internal/discovery"
	"remote-voice/server/internal/selfcert"
	"remote-voice/server/internal/server"
)

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)

	addr := flag.String("addr", ":9432", "TLS 监听地址")
	dataDir := flag.String("data", "./data", "证书、密钥与设备身份存放目录")
	certFile := flag.String("cert", "", "可选：外部 TLS 证书 PEM（如 Let's Encrypt fullchain.pem）；提供后不再自签")
	keyFile := flag.String("key", "", "可选：外部 TLS 私钥 PEM（如 Let's Encrypt privkey.pem）")
	regkeyFile := flag.String("regkeyfile", "", "旧 v2 兼容：从文件读取 Mac 注册密钥")
	regkeyFlag := flag.String("regkey", "", "旧 v2 兼容：直接指定 regkey（仅限本地调试）")
	pprofAddr := flag.String("pprof", "", "可选：pprof 监听地址，如 127.0.0.1:6060")
	discoveryOn := flag.Bool("discovery", false, "局域网发现应答（UDP 与 -addr 同号端口）；仅本地测试用，公网部署无需开启")
	discoveryName := flag.String("name", "", "局域网发现显示名（默认主机名）")
	flag.Parse()

	regkey := resolveRegkey(*regkeyFile, *regkeyFlag)

	var cert tls.Certificate
	var fingerprint string
	if *certFile != "" || *keyFile != "" {
		// rvs:// 部署模式：加载外部（CA 签发）证书，客户端走标准 CA+主机名验证
		if *certFile == "" || *keyFile == "" {
			log.Fatalf("-cert 与 -key 必须同时提供")
		}
		var err error
		cert, err = tls.LoadX509KeyPair(*certFile, *keyFile)
		if err != nil {
			log.Fatalf("加载外部证书失败: %v", err)
		}
		fingerprint, err = certFingerprint(cert.Certificate)
		if err != nil {
			log.Fatalf("计算证书指纹失败: %v", err)
		}
	} else {
		var err error
		cert, fingerprint, err = selfcert.Ensure(*dataDir)
		if err != nil {
			log.Fatalf("初始化证书失败: %v", err)
		}
	}
	fmt.Println("==================================================")
	fmt.Printf("服务端证书指纹 (SHA-256 hex，仅供诊断；rv:// 客户端首次连接自动信任):\n%s\n", fingerprint)
	if *certFile != "" {
		fmt.Println("已加载外部证书：rvs:// 客户端按标准 CA+主机名验证")
	}

	// 打印手机端可粘贴的一行配置（host 为空时用占位符提示）。
	// 协议 v3：客户端只需服务器地址，证书身份由内部 TOFU 管理。
	hostPart, portPart, _ := net.SplitHostPort(*addr)
	if hostPart == "" {
		hostPart = "<服务器IP>"
	}
	fmt.Printf("客户端配置串（App 设置 → 导入配置串）:\n  rv://%s:%s\n", hostPart, portPart)
	fmt.Println("==================================================")

	if *pprofAddr != "" {
		go func() {
			log.Printf("pprof serving on %s", *pprofAddr)
			log.Printf("pprof exited: %v", http.ListenAndServe(*pprofAddr, nil))
		}()
	}

	tlsCfg := &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
	}
	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		log.Fatalf("监听 %s 失败: %v", *addr, err)
	}

	// 局域网发现：UDP 与 TCP 同号端口应答手机探测（设计：lan-discovery-dual-address §3）。
	// 失败只降级（手机回落手动填地址），绝不影响 TCP 服务。
	if *discoveryOn {
		if d, derr := discovery.Serve(*addr, *discoveryName); derr != nil {
			log.Printf("局域网发现开启失败（不影响 TCP 服务）: %v", derr)
		} else {
			defer d.Close()
			log.Printf("局域网发现已开启（UDP %s，显示名 %s）", *addr, d.DisplayName())
		}
	}

	srv, err := server.NewWithError(server.Config{
		RegKey:          regkey,
		DeviceStorePath: filepath.Join(*dataDir, "devices.json"),
		TlsConfig:       tlsCfg,
	})
	if err != nil {
		log.Fatalf("加载设备身份失败: %v", err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go func() {
		<-ctx.Done()
		log.Println("收到退出信号，正在关闭…")
		srv.Close()
	}()

	log.Printf("server 启动，监听 %s", *addr)
	if err := srv.Serve(ln); err != nil && !errors.Is(err, context.Canceled) {
		log.Fatalf("服务器退出: %v", err)
	}
	log.Println("已退出")
}

func resolveRegkey(regkeyFile, regkeyFlag string) string {
	if regkeyFile != "" {
		b, err := os.ReadFile(regkeyFile)
		if err != nil {
			log.Fatalf("读取 regkeyfile 失败: %v", err)
		}
		return strings.TrimSpace(string(b))
	}
	if env := os.Getenv("RELAY_REGKEY"); env != "" {
		return strings.TrimSpace(env)
	}
	return regkeyFlag
}

// certFingerprint 计算叶子证书 DER 的 SHA-256 hex（与 selfcert 输出格式一致，供诊断）。
func certFingerprint(rawDERs [][]byte) (string, error) {
	if len(rawDERs) == 0 {
		return "", fmt.Errorf("证书链为空")
	}
	sum := sha256.Sum256(rawDERs[0])
	return hex.EncodeToString(sum[:]), nil
}
