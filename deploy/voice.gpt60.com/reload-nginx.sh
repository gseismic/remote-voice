#!/bin/sh
# 仅在此域名的证书换新时重载 Nginx。
set -eu

if [ "${RENEWED_LINEAGE:-}" = /etc/letsencrypt/live/voice.gpt60.com ]; then
    /usr/sbin/nginx -t
    systemctl reload nginx
fi
