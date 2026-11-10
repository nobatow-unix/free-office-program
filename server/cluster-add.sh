#!/usr/bin/env bash
# ESASY serwerde: täze serwere bazany we Redis-i açýar we goşulmak üçin kody görkezýär.
#   bash cluster-add.sh 203.0.113.7
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "root bilen işlet"; exit 1; }
DEST=/opt/icq-server
IP="${1:-}"
[[ "$IP" =~ ^[0-9a-fA-F:.]+$ ]] || { echo "Ulanylyşy: bash cluster-add.sh TÄZE_SERWERIŇ_IP-si"; exit 1; }
[ -f "$DEST/cluster.token" ] || { echo "Ilki bu serwerde: bash cluster-main.sh"; exit 1; }
{ cat "$DEST/cluster.allow" 2>/dev/null; echo "$IP"; } | sed '/^\s*$/d' | sort -u > "$DEST/cluster.allow.tmp"
mv "$DEST/cluster.allow.tmp" "$DEST/cluster.allow"
"$DEST/cluster-fw.sh"
echo
echo "✔ $IP rugsat berildi. Indi TÄZE serwerde (server bukjasynyň içinde) işlet:"
echo
echo "   bash cluster-join.sh $(cat "$DEST/cluster.token")"
echo
echo "Serwerler: $(tr '\n' ' ' < "$DEST/cluster.allow")"
