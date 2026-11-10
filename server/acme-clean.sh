#!/usr/bin/env bash
. /opt/icq-server/icq.env
redis-cli -u "$ICQ_REDIS_URL" --no-auth-warning DEL "icq:acme:$CERTBOT_TOKEN" >/dev/null || true
