#!/usr/bin/env bash
# TÄZE serwerde: klastere goşulýar (baza we Redis esasy serwerde, bu serwer adamlara hyzmat edýär).
#   bash cluster-join.sh KOD      (KOD-y esasy serwerdäki cluster-add.sh görkezýär)
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "root bilen işlet"; exit 1; }
SRC="$(cd "$(dirname "$0")" && pwd)"
DEST=/opt/icq-server
ENVF="$DEST/icq.env"
TOKEN="${1:?Ulanylyşy: bash cluster-join.sh KOD}"
IFS='|' read -r MAIN_IP PG_PASS REDIS_PASS CLUSTER_SECRET ADMIN_PASS <<<"$(echo "$TOKEN" | base64 -d)"
[ -n "$ADMIN_PASS" ] || { echo "Kod nädogry"; exit 1; }

set_env() { touch "$ENVF"; grep -v "^$1=" "$ENVF" > "$ENVF.tmp" || true; echo "$1=$2" >> "$ENVF.tmp"; mv "$ENVF.tmp" "$ENVF"; }

export DEBIAN_FRONTEND=noninteractive
apt-get update -y >/dev/null
apt-get install -y redis-tools postgresql-client curl >/dev/null
PUBIP="$(curl -4 -fsS -m 5 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')"

echo "==> Esasy serwer barlanýar ($MAIN_IP)"
if ! PGPASSWORD="$PG_PASS" psql -h "$MAIN_IP" -U icq -d icq -tAc "SELECT 1" >/dev/null 2>&1; then
  echo "❌ Baza açylmady. ESASY serwerde ilki işlet:  bash cluster-add.sh $PUBIP"; exit 1
fi
if [ "$(redis-cli -u "redis://:$REDIS_PASS@$MAIN_IP:6379/0" --no-auth-warning PING 2>/dev/null)" != "PONG" ]; then
  echo "❌ Redis açylmady. ESASY serwerde ilki işlet:  bash cluster-add.sh $PUBIP"; exit 1
fi
echo "   baza ✔  redis ✔"

mkdir -p "$DEST"
set_env ICQ_ADMIN_PASSWORD "$ADMIN_PASS"
set_env ICQ_DB_URL "postgresql://icq:$PG_PASS@$MAIN_IP:5432/icq"
set_env ICQ_REDIS_URL "redis://:$REDIS_PASS@$MAIN_IP:6379/0"
set_env ICQ_NODE_ID "srv-$(echo "$PUBIP" | tr '.:' '--')"
set_env ICQ_NODE_URL "http://$PUBIP:8000"
set_env ICQ_CLUSTER_SECRET "$CLUSTER_SECRET"

echo "==> Programma gurulýar (install.sh)"
bash "$SRC/install.sh"

cat <<DONE

============================================================
 ✔ $PUBIP klastere goşuldy.
   Admin panel → «Серверы»: bu serwer «işleýär» görünmeli.
   Soňky ädim — domen panelinde (DNS) goş:
      A   icq-new.online       $PUBIP
      A   www.icq-new.online   $PUBIP
   (köne A ýazgylar galýar: adamlar serwerlere paýlanýar,
    bir serwer öçse programma beýlekisine geçýär)
============================================================
DONE
