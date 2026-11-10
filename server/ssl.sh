#!/usr/bin/env bash
# ICQ New: HTTPS sertifikatlary (Let's Encrypt) + näme üçin alynmaýandygyny görkezmek.
#   bash ssl.sh icq-new.online icq-new.ru
FORCE=""
[ "${1:-}" = "--force" ] && { FORCE="--force-renewal"; shift; }
[ $# -eq 0 ] && set -- "${ICQ_DOMAIN:-icq-new.online}" "${ICQ_SITE_DOMAIN:-icq-new.ru}"
# klaster: domen birnäçe serwere görkezýär — Let's Encrypt barlagy islendik serwere gelip biler,
# şonuň üçin jogap Redis-de saklanýar (acme-hook.sh) we her serwer ony görkezýär
CLUSTER=""
grep -q '^ICQ_REDIS_URL=' /opt/icq-server/icq.env 2>/dev/null && CLUSTER=1
PUBIP="$(curl -4 -fsS -m 5 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')"
echo "==> HTTPS  (serweriň IP-si: $PUBIP)"
command -v certbot >/dev/null || apt-get install -y certbot python3-certbot-nginx >/dev/null
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null; fi

OWNER80="$(ss -ltnp '( sport = :80 )' 2>/dev/null | awk 'NR>1{print $NF}' | head -1)"
echo "   80-nji port: ${OWNER80:-hiç kim diňlemeýär}"
if ! echo "$OWNER80" | grep -q nginx; then
  echo "   ❌ 80-nji porty nginx diňlemeli (systemctl status nginx). Ilki install.sh-y işlet."
  exit 1
fi

RC=0
for DOMAIN in "$@"; do
  echo "--- $DOMAIN"
  A="$(getent ahostsv4 "$DOMAIN" | awk 'NR==1{print $1}')"
  AAAA="$(getent ahostsv6 "$DOMAIN" | awk 'NR==1{print $1}')"
  echo "   DNS A    : ${A:-YOK}"
  [ -n "$AAAA" ] && echo "   DNS AAAA : $AAAA  (bu serwere degişli däl bolsa — poz, ýogsam sertifikat alynmaýar)"
  if [ -z "$A" ]; then
    echo "   ❌ A ýazgy ýok. Domen panelinde goş:  A  @  $PUBIP   (we  A  www  $PUBIP)"
    RC=1; continue
  fi
  if [ -z "$CLUSTER" ]; then
    [ "$A" != "$PUBIP" ] && echo "   ⚠ A ýazgy başga IP görkezýär ($A). Cloudflare 'proxy' bolsa kadaly, ýogsam $PUBIP goý."
  else
    echo "   klaster: DNS-däki serwerler: $(getent ahostsv4 "$DOMAIN" | awk '{print $1}' | sort -u | tr '\n' ' ')"
  fi
  if curl -fsS -m 8 -o /dev/null "http://$DOMAIN/api/ping"; then
    echo "   http://$DOMAIN açylýar ✔"
  else
    echo "   ⚠ http://$DOMAIN açylmady — hostingiň firewall-ynda 80/443 portlaryny aç."
  fi
  DOMS="-d $DOMAIN"
  W="$(getent ahostsv4 "www.$DOMAIN" | awk 'NR==1{print $1}')"
  [ -n "$W" ] && { [ -n "$CLUSTER" ] || [ "$W" = "$A" ]; } && DOMS="$DOMS -d www.$DOMAIN"
  if [ -n "$CLUSTER" ]; then
    AUTH=(-a manual -i nginx --preferred-challenges http
          --manual-auth-hook /opt/icq-server/acme-hook.sh --manual-cleanup-hook /opt/icq-server/acme-clean.sh)
  else
    AUTH=(--nginx)
  fi
  if certbot "${AUTH[@]}" $DOMS --cert-name "$DOMAIN" --non-interactive --agree-tos --register-unsafely-without-email \
       --no-redirect --keep-until-expiring $FORCE; then
    echo "   ✔ https://$DOMAIN taýýar"
  else
    echo "   ❌ $DOMAIN üçin sertifikat alynmady — ýokardaky 'Detail:' setirini iber."
    RC=1
  fi
done
systemctl reload nginx 2>/dev/null || true
exit $RC
