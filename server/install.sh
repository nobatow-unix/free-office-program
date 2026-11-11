#!/usr/bin/env bash
# ICQ New — VPS-e serweri gurmak (Ubuntu/Debian). root bilen işlet:
#   scp -r server root@185.159.128.234:/root/
#   ssh root@185.159.128.234 "cd /root/server && bash install.sh"
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Bu skripti root bilen işlet: sudo bash install.sh"
  exit 1
fi

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST=/opt/icq-server
PORT=8000

echo "==> Paketler gurulýar"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y python3 python3-venv python3-pip curl coturn iptables redis-tools

echo "==> Faýllar $DEST ýerine göçürilýär (baza we faýllar saklanýar)"
mkdir -p "$DEST"
if [ "$SRC" != "$DEST" ]; then
cp "$SRC/server.py" "$SRC/apkdelta.py" "$SRC/db.py" "$SRC/cluster.py" "$SRC/migrate_to_pg.py" \
   "$SRC/requirements.txt" "$SRC/ssl.sh" "$SRC/acme-hook.sh" "$SRC/acme-clean.sh" "$SRC/cluster-fw.sh" "$DEST/"
# klaster skriptleri: admin panel täze serweri şulardan gurýar
cp "$SRC/install.sh" "$SRC/cluster-main.sh" "$SRC/cluster-add.sh" "$SRC/cluster-join.sh" "$SRC/cluster-remove.sh" "$DEST/" 2>/dev/null || true
cp "$SRC/CLUSTER.md" "$DEST/" 2>/dev/null || true
# saýt (icq-new.ru) we web wersiýa (icq-new.online)
rm -rf "$DEST/web" && cp -r "$SRC/web" "$DEST/web"
fi
chmod +x "$DEST"/*.sh 2>/dev/null || true
# programma: server/apk/icq-new.apk goýsaň, saýtdan "Скачать" bilen ýüklenýär
mkdir -p "$DEST/apk/history"
# köne wersiýany saklaýarys: telefonlar täzelenende diňe tapawudy (patch) ýükleýär
if [ -f "$DEST/apk/icq-new.apk" ] && [ -f "$DEST/apk/version.json" ]; then
  OLD_CODE=$(python3 -c 'import json,sys; print(int(json.load(open(sys.argv[1])).get("code") or 0))' "$DEST/apk/version.json" 2>/dev/null || echo 0)
  if [ "$OLD_CODE" != "0" ] && [ ! -f "$DEST/apk/history/$OLD_CODE.apk" ]; then
    cp "$DEST/apk/icq-new.apk" "$DEST/apk/history/$OLD_CODE.apk"
  fi
fi
if [ "$SRC" != "$DEST" ] && [ -f "$SRC/apk/icq-new.apk" ]; then cp "$SRC/apk/icq-new.apk" "$DEST/apk/icq-new.apk"; fi
# täze wersiýa barada maglumat (programma aşakda "Обновить" görkezýär)
if [ "$SRC" != "$DEST" ] && [ -f "$SRC/apk/version.json" ]; then cp "$SRC/apk/version.json" "$DEST/apk/version.json"; fi

echo "==> Python gurşawy"
python3 -m venv "$DEST/.venv"
"$DEST/.venv/bin/pip" install -q --upgrade pip
"$DEST/.venv/bin/pip" install -q -r "$DEST/requirements.txt"

# Islege görä Telegram habary: $DEST/icq.env faýlyna ýaz:
#   ICQ_TG_TOKEN=bot_token
#   ICQ_TG_CHAT=chat_id
[ -f "$DEST/icq.env" ] || touch "$DEST/icq.env"

# Firebase: firebase.json (service account) şu bukja goýulan bolsa ulanylýar
if [ "$SRC" != "$DEST" ] && [ -f "$SRC/firebase.json" ]; then
  cp "$SRC/firebase.json" "$DEST/firebase.json"
fi
if [ -f "$DEST/firebase.json" ] && ! grep -q ICQ_FCM_JSON "$DEST/icq.env"; then
  echo "ICQ_FCM_JSON=$DEST/firebase.json" >> "$DEST/icq.env"
fi

echo "==> TURN serwer (jaňlar üçin, coturn)"
PUBIP="$(curl -fsS -m 5 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')"
if ! grep -q ICQ_TURN_PASS "$DEST/icq.env"; then
  TURN_PASS="$(head -c 12 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 16)"
  {
    echo "ICQ_TURN_URL=turn:$PUBIP:3478?transport=udp,turn:$PUBIP:3478?transport=tcp"
    echo "ICQ_TURN_USER=icq"
    echo "ICQ_TURN_PASS=$TURN_PASS"
  } >> "$DEST/icq.env"
fi
TURN_PASS="$(grep ICQ_TURN_PASS "$DEST/icq.env" | cut -d= -f2)"
cat > /etc/turnserver.conf <<TURN
listening-port=3478
fingerprint
lt-cred-mech
user=icq:$TURN_PASS
realm=icq
external-ip=$PUBIP
min-port=49160
max-port=49200
no-cli
no-tlsv1
no-tlsv1_1
TURN
sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn 2>/dev/null || true
systemctl enable coturn >/dev/null 2>&1 || true
systemctl restart coturn || echo "coturn başlamady (jaňlar diňe STUN bilen işlär)"

echo "==> systemd hyzmaty"
cat > /etc/systemd/system/icq.service <<UNIT
[Unit]
Description=ICQ New server
After=network.target

[Service]
WorkingDirectory=$DEST
Environment=ICQ_DB=$DEST/icq_v2.db
EnvironmentFile=-$DEST/icq.env
ExecStart=$DEST/.venv/bin/uvicorn server:app --host 0.0.0.0 --port $PORT --timeout-graceful-shutdown 5
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable icq >/dev/null
systemctl restart icq

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  echo "==> ufw: $PORT/tcp açylýar"
  ufw allow "$PORT"/tcp
  ufw allow 3478/udp
  ufw allow 3478/tcp
  ufw allow 49160:49200/udp
fi

DOMAIN="${ICQ_DOMAIN:-icq-new.online}"        # programma + web wersiýa
SITE_DOMAIN="${ICQ_SITE_DOMAIN:-icq-new.ru}"   # saýt (programmany ýüklemek)
echo "==> Domen $DOMAIN: nginx (80/443) -> $PORT"
# köne iptables 80->8000 geçirmesi (3.5) aýrylýar, indi nginx işleýär
if [ -f /etc/systemd/system/icq-port80.service ]; then
  systemctl disable --now icq-port80 >/dev/null 2>&1 || true
  rm -f /etc/systemd/system/icq-port80.service
  systemctl daemon-reload
fi
BUSY80="$(ss -ltnp '( sport = :80 )' 2>/dev/null | grep LISTEN | grep -v nginx || true)"
if [ -n "$BUSY80" ]; then
  echo "   80-nji port başga programma bilen eýelenen, nginx goýulmady:"
  echo "$BUSY80"
else
  apt-get install -y nginx certbot python3-certbot-nginx
  # her domen üçin aýry server bloky (certbot her birine öz sertifikatyny goýar) + IP üçin default
  icq_block() {
    cat <<BLK
server {
    listen 80$2;
    listen [::]:80$2;
    server_name $1;
    client_max_body_size 4200m;      # faýllar 4 GB çenli
    proxy_request_buffering off;     # uly faýllar göni serwere akýar
    proxy_buffering off;
    location / {
        proxy_pass http://127.0.0.1:$PORT;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$icq_conn_upgrade;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
BLK
  }
  {
    echo 'map $http_upgrade $icq_conn_upgrade { default upgrade; '"''"' close; }'
    icq_block "_" " default_server"
    icq_block "$DOMAIN www.$DOMAIN" ""
    icq_block "$SITE_DOMAIN www.$SITE_DOMAIN" ""
  } > /etc/nginx/sites-available/icq.conf
  rm -f /etc/nginx/sites-enabled/default
  ln -sf /etc/nginx/sites-available/icq.conf /etc/nginx/sites-enabled/icq.conf
  nginx -t && systemctl enable --now nginx && systemctl reload nginx
  if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
    ufw allow 80/tcp; ufw allow 443/tcp
  fi
  # HTTPS sertifikaty (Let's Encrypt)
  bash "$SRC/ssl.sh" "$DOMAIN" "$SITE_DOMAIN" || true
fi

echo "==> Barlag"
sleep 2
if curl -fsS "http://127.0.0.1:$PORT/api/ping"; then
  echo
  echo "SERWER IŞLÄÄR: http://$(curl -fsS -m 5 https://api.ipify.org 2>/dev/null || echo VPS_IP):$PORT"
  echo "Eger daşyndan açylmasa: hosting panelinde TCP $PORT portuny aç."
  echo "Jaňlar üçin hem aç: UDP+TCP 3478 we UDP 49160-49200."
  if grep -q ICQ_FCM_JSON "$DEST/icq.env"; then echo "Firebase: GOŞULDY"; else echo "Firebase: ýok (firebase.json goýup install.sh-y gaýtadan işlet)"; fi
  echo "Saýt:        https://$SITE_DOMAIN/   (programmany ýüklemek)"
  echo "Programma:   https://$DOMAIN/   (web wersiýa: QR ýa-da nomer bilen)"
  if [ ! -f "$DEST/apk/icq-new.apk" ]; then echo "APK entek ýok: scp app-debug.apk root@IP:$DEST/apk/icq-new.apk"; fi
  echo "Admin panel: http://IP:$PORT/admin   (login: admin)"
  echo "Admin paroly: $(cat "$DEST/admin_password.txt" 2>/dev/null || echo 'icq.env-de ICQ_ADMIN_PASSWORD bellen')"
  echo "SMS kody: admin panelde görünýär, ony ulanyja özüň nomeriňden ugrat."
  echo "Loglar: journalctl -u icq -f"
else
  echo "Serwer başlamady. Logy gör: journalctl -u icq -n 50 --no-pager"
  exit 1
fi
