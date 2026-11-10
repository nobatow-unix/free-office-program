#!/usr/bin/env bash
# ICQ New — şu serweri klasteriň ESASY serweri edýär: PostgreSQL + Redis şu ýerde durýar,
# häzirki baza (SQLite) doly PostgreSQL-e geçirilýär, soňra islän wagtyň täze serwer goşup bolýar.
#   cd /root/server && bash cluster-main.sh
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "root bilen işlet: sudo bash cluster-main.sh"; exit 1; }
SRC="$(cd "$(dirname "$0")" && pwd)"
DEST=/opt/icq-server
ENVF="$DEST/icq.env"

set_env() {  # set_env KEY VALUE  (icq.env içinde çalyşýar ýa-da goşýar)
  touch "$ENVF"
  grep -v "^$1=" "$ENVF" > "$ENVF.tmp" || true
  echo "$1=$2" >> "$ENVF.tmp"
  mv "$ENVF.tmp" "$ENVF"
}
rnd() { head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c "${1:-28}"; }

echo "==> 1/8 Programma täzelenýär (install.sh)"
bash "$SRC/install.sh" || true

echo "==> 2/8 PostgreSQL we Redis gurulýar"
export DEBIAN_FRONTEND=noninteractive
apt-get install -y postgresql postgresql-contrib redis-server redis-tools iptables >/dev/null

SECRETS="$DEST/cluster.secrets"
if [ ! -f "$SECRETS" ]; then
  { echo "PG_PASS=$(rnd 28)"; echo "REDIS_PASS=$(rnd 28)"; echo "CLUSTER_SECRET=$(rnd 40)"; } > "$SECRETS"
  chmod 600 "$SECRETS"
fi
. "$SECRETS"
PUBIP="$(curl -4 -fsS -m 5 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')"

echo "==> 3/8 PostgreSQL sazlanýar"
PGVER="$(ls /etc/postgresql | sort -V | tail -1)"
PGCONF="/etc/postgresql/$PGVER/main"
mkdir -p "$PGCONF/conf.d"
cat > "$PGCONF/conf.d/icq.conf" <<PGC
listen_addresses = '*'
max_connections = 400
shared_buffers = 256MB
PGC
grep -q "^include_dir = 'conf.d'" "$PGCONF/postgresql.conf" || echo "include_dir = 'conf.d'" >> "$PGCONF/postgresql.conf"
grep -q "icq-cluster" "$PGCONF/pg_hba.conf" || cat >> "$PGCONF/pg_hba.conf" <<HBA
# icq-cluster: klasteriň serwerleri (firewall diňe rugsat berlen IP-leri goýberýär)
host    icq    icq    0.0.0.0/0    scram-sha-256
host    icq    icq    ::/0         scram-sha-256
HBA
systemctl enable postgresql >/dev/null 2>&1 || true
systemctl restart postgresql
su postgres -c "psql -tAc \"SELECT 1 FROM pg_roles WHERE rolname='icq'\"" | grep -q 1 \
  || su postgres -c "psql -qc \"CREATE ROLE icq LOGIN PASSWORD '$PG_PASS'\""
su postgres -c "psql -qc \"ALTER ROLE icq PASSWORD '$PG_PASS'\""
su postgres -c "psql -tAc \"SELECT 1 FROM pg_database WHERE datname='icq'\"" | grep -q 1 \
  || su postgres -c "createdb -O icq icq"
su postgres -c "psql -d icq -qc 'CREATE EXTENSION IF NOT EXISTS citext'"

echo "==> 4/8 Redis sazlanýar"
RCONF=/etc/redis/redis.conf
if grep -qE '^bind ' "$RCONF"; then sed -i -E 's/^bind .*/bind 0.0.0.0/' "$RCONF"; else echo "bind 0.0.0.0" >> "$RCONF"; fi
sed -i -E '/^#?\s*requirepass /d' "$RCONF"; echo "requirepass $REDIS_PASS" >> "$RCONF"
sed -i -E 's/^#?\s*appendonly .*/appendonly yes/' "$RCONF"
systemctl enable redis-server >/dev/null 2>&1 || true
systemctl restart redis-server

echo "==> 5/8 Firewall: 5432/6379 diňe klasteriň serwerleri üçin"
ALLOW="$DEST/cluster.allow"
{ cat "$ALLOW" 2>/dev/null; echo "$PUBIP"; } | sed '/^\s*$/d' | sort -u > "$ALLOW.tmp"; mv "$ALLOW.tmp" "$ALLOW"
cp "$SRC/cluster-fw.sh" "$DEST/cluster-fw.sh"; chmod +x "$DEST/cluster-fw.sh"
cat > /etc/systemd/system/icq-cluster-fw.service <<UNIT
[Unit]
Description=ICQ New cluster firewall (PostgreSQL/Redis only for cluster servers)
After=network.target
[Service]
Type=oneshot
ExecStart=$DEST/cluster-fw.sh
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now icq-cluster-fw >/dev/null 2>&1 || "$DEST/cluster-fw.sh"
systemctl restart icq-cluster-fw || true

echo "==> 6/8 Serwer klaster režimine geçirilýär"
ADMIN_PASS="$(grep '^ICQ_ADMIN_PASSWORD=' "$ENVF" 2>/dev/null | cut -d= -f2- || true)"
[ -z "$ADMIN_PASS" ] && ADMIN_PASS="$(cat "$DEST/admin_password.txt" 2>/dev/null || true)"
[ -z "$ADMIN_PASS" ] && ADMIN_PASS="$(rnd 14)"
set_env ICQ_ADMIN_PASSWORD "$ADMIN_PASS"
set_env ICQ_DB_URL "postgresql://icq:$PG_PASS@127.0.0.1:5432/icq"
set_env ICQ_REDIS_URL "redis://:$REDIS_PASS@127.0.0.1:6379/0"
set_env ICQ_NODE_ID "main"
set_env ICQ_NODE_URL "http://$PUBIP:8000"
set_env ICQ_CLUSTER_SECRET "$CLUSTER_SECRET"

echo "==> 7/8 Häzirki baza PostgreSQL-e göçürilýär"
systemctl stop icq || true
SQLITE="$DEST/icq_v2.db"
if [ -f "$SQLITE" ] && [ ! -f "$DEST/.migrated_to_pg" ]; then
  cp "$SQLITE" "$SQLITE.bak-$(date +%Y%m%d-%H%M)"
  (cd "$DEST" && ICQ_ADMIN_PASSWORD="$ADMIN_PASS" ICQ_UPLOADS="$DEST/uploads" ICQ_AVATARS="$DEST/avatars" \
     "$DEST/.venv/bin/python" "$DEST/migrate_to_pg.py" --sqlite "$SQLITE" --pg "postgresql://icq:$PG_PASS@127.0.0.1:5432/icq")
  touch "$DEST/.migrated_to_pg"
else
  echo "   (eýýäm göçürilen ýa-da SQLite ýok)"
fi
systemctl start icq
sleep 3
curl -fsS "http://127.0.0.1:8000/api/ping" >/dev/null && echo "   serwer işleýär ✔" || { echo "   ❌ serwer başlamady: journalctl -u icq -n 60"; exit 1; }

# her gün baza nusgasy (soňky 7 gün saklanýar)
mkdir -p /var/backups/icq && chown postgres /var/backups/icq
echo '30 3 * * * postgres pg_dump -Fc icq > /var/backups/icq/icq-$(date +\%u).dump' > /etc/cron.d/icq-backup

echo "==> 8/8 HTTPS (klaster režimi: islendik serwer Let's Encrypt barlagyna jogap berýär)"
bash "$SRC/ssl.sh" --force || true

TOKEN="$(printf '%s' "$PUBIP|$PG_PASS|$REDIS_PASS|$CLUSTER_SECRET|$ADMIN_PASS" | base64 -w0)"
echo "$TOKEN" > "$DEST/cluster.token"; chmod 600 "$DEST/cluster.token"
cat <<DONE

============================================================
 ✔ KLASTER TAÝÝAR. Esasy serwer: $PUBIP
   Baza: PostgreSQL (şu serwerde), Redis (şu serwerde)
   Admin panel: https://\${ICQ_DOMAIN:-icq-new.online}/admin  (admin / $ADMIN_PASS)
   Admin paneliň «Серверы» bölüminde ähli serwerler görünýär.

 TÄZE SERWER GOŞMAK (islän wagtyň, islän sanyňda):
   1) ŞU serwerde:      bash cluster-add.sh TÄZE_IP
   2) TÄZE serwerde:    server bukjasyny göçür we:
                        bash cluster-join.sh  (cluster-add.sh görkezen kod)
   3) Domen panelinde (DNS): icq-new.online we www üçin TÄZE_IP bilen
      ýene bir A ýazgy goş (köne A ýazgylar galýar).
============================================================
DONE
