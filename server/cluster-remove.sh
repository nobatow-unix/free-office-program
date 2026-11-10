#!/usr/bin/env bash
# ESASY serwerde: serweri klasterden aýyrýar (bazany onuň üçin ýapýar).
#   bash cluster-remove.sh 203.0.113.7
set -euo pipefail
DEST=/opt/icq-server
IP="${1:?IP gerek}"
grep -vx "$IP" "$DEST/cluster.allow" > "$DEST/cluster.allow.tmp" || true
mv "$DEST/cluster.allow.tmp" "$DEST/cluster.allow"
"$DEST/cluster-fw.sh"
echo "✔ $IP aýryldy. DNS-den hem onuň A ýazgysyny poz."
