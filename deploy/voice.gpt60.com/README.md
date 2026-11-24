# voice.gpt60.com 部署

此站点使用已有 Nginx 和 Certbot。Go 中继与 Nginx 通过 `127.0.0.1:19432` 通信，原生 TLS 入口 `127.0.0.1:9432` 供管理员登记 Mac。服务器密码和设备身份位于 `server/data/devices.json`，部署时保留 `server/data/`。

## 初次启用 HTTPS

1. 确认域名指向目标主机，公网 80 和 443 到达 Nginx，并构建 `server-linux`。
2. 创建证书验证目录，首次签发前仅启用配置中的 80 端口虚拟主机。

   ```sh
   sudo install -d -m 755 /var/lib/letsencrypt-remote-voice/.well-known/acme-challenge
   sed '/^# 证书签发后启用/,$d' nginx.conf | sudo tee /etc/nginx/sites-available/voice.gpt60.com.conf >/dev/null
   sudo ln -s ../sites-available/voice.gpt60.com.conf /etc/nginx/sites-enabled/voice.gpt60.com.conf
   sudo nginx -t && sudo systemctl reload nginx
   sudo certbot certonly --webroot -w /var/lib/letsencrypt-remote-voice --cert-name voice.gpt60.com -d voice.gpt60.com
   ```

3. 安装 systemd 配置和完整的 Nginx 虚拟主机。确认进程启动后再重载 Nginx。

   ```sh
   sudo install -D -m 644 remote-voice-server.conf /etc/systemd/system/remote-voice-server.service.d/https.conf
   sudo systemctl daemon-reload
   sudo systemctl restart remote-voice-server
   curl -fsS http://127.0.0.1:19432/healthz

   sudo install -m 644 nginx.conf /etc/nginx/sites-available/voice.gpt60.com.conf
   sudo install -m 755 reload-nginx.sh /etc/letsencrypt/renewal-hooks/deploy/remote-voice-reload-nginx.sh
   sudo nginx -t && sudo systemctl reload nginx
   curl -fsS https://voice.gpt60.com/healthz
   sudo certbot renew --cert-name voice.gpt60.com --dry-run --run-deploy-hooks
   ```

## 登记 Mac

已有登记随 `server/data/devices.json` 加载。新增 Mac 时，在该 Mac 经运营 SSH 连接转发私有登记入口，例如 `ssh -N -L 19432:127.0.0.1:9432 ubuntu@118.193.40.160`。在 Mac App 的连接设置使用 `127.0.0.1:19432` 完成登记。多台 Mac 连接同一中继时使用同一个服务器密码。登记后把 Mac 地址切到 `https://voice.gpt60.com`，连接后出示二维码。

## 回滚

保留更新前的中继二进制和独立的 `https.conf`。需要回退时先停用此站点的 Nginx 链接并重载，移出该 systemd drop-in，恢复旧二进制，执行 `daemon-reload` 和服务重启。证书与设备身份文件可供后续重新启用。
