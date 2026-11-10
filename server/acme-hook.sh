#!/usr/bin/env bash
# certbot --manual-auth-hook: the answer for Let's Encrypt goes to Redis, so ANY server of the cluster can show it
set -e
. /opt/icq-server/icq.env
redis-cli -u "$ICQ_REDIS_URL" --no-auth-warning SET "icq:acme:$CERTBOT_TOKEN" "$CERTBOT_VALIDATION" EX 900 >/dev/null
sleep 2
