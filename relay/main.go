// relay 中转服务器入口。
// token 来源优先级：-tokenfile > 环境变量 RELAY_TOKEN > -token
// （命令行传 token 会泄露到 ps/shell history，仅限本地调试）。
package main

import (
	"context"
	"crypto/tls"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	_ "net/http/pprof"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"remote-voice/relay/internal/selfcert"
	"remote-voice/relay/internal/server"
)

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)

	addr := flag.String("addr", ":9432", "TLS 监听地址")
	dataDir := flag.String("data", "./data", "证书与密钥存放目录")
	tokenFile := flag.String("tokenfile", "", "从文件读取 token（首行，自动去空白）")
	tokenFlag := flag.String("token", "", "直接指定 token（仅限本地调试）")
	pprofAddr := flag.String("pprof", "", "可选：pprof 监听地址，如 127.0.0.1:6060")
	flag.Parse()

	token := resolveToken(*tokenFile, *tokenFlag)
	if token == "" {
		fmt.Fprintln(os.Stderr, "错误：未提供 token。可用方式：\n"+
			"  1) -tokenfile /path/to/token 文件（推荐）\n"+
			"  2) 环境变量 RELAY_TOKEN\n"+
			"  3) -token 参数（仅限本地调试，会泄露到 ps/history）")
		os.Exit(1)
	}

	cert, fingerprint, err := selfcert.Ensure(*dataDir)
	if err != nil {
		log.Fatalf("初始化证书失败: %v", err)
	}
	fmt.Println("==================================================")
	fmt.Printf("服务端证书指纹 (SHA-256 hex)，请填入两端客户端配置:\n%s\n", fingerprint)

	// 打印手机端可粘贴的一行配置（host 为空时用占位符提示）
	hostPart, portPart, _ := net.SplitHostPort(*addr)
	if hostPart == "" {
		hostPart = "<服务器IP>"
	}
	fmt.Printf("手机端配置串（App 设置 → 导入配置串）:\n  rv://%s:%s?t=%s&f=%s\n",
		hostPart, portPart, token, fingerprint)
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

	srv := server.New(server.Config{
		Token:     token,
		TlsConfig: tlsCfg,
	})
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go func() {
		<-ctx.Done()
		log.Println("收到退出信号，正在关闭…")
		srv.Close()
	}()

	log.Printf("relay 启动，监听 %s", *addr)
	if err := srv.Serve(ln); err != nil && !errors.Is(err, context.Canceled) {
		log.Fatalf("服务器退出: %v", err)
	}
	log.Println("已退出")
}

func resolveToken(tokenFile, tokenFlag string) string {
	if tokenFile != "" {
		b, err := os.ReadFile(tokenFile)
		if err != nil {
			log.Fatalf("读取 tokenfile 失败: %v", err)
		}
		return strings.TrimSpace(string(b))
	}
	if env := os.Getenv("RELAY_TOKEN"); env != "" {
		return strings.TrimSpace(env)
	}
	return tokenFlag
}
