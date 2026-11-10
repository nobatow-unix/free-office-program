#!/usr/bin/env bash
# PostgreSQL (5432) and Redis (6379) of the main server: open ONLY for the servers of the cluster
# (the list: /opt/icq-server/cluster.allow, one IP per line). Runs at boot (icq-cluster-fw.service).
ALLOW=/opt/icq-server/cluster.allow
iptables -N ICQ_CLUSTER 2>/dev/null || iptables -F ICQ_CLUSTER
iptables -A ICQ_CLUSTER -s 127.0.0.1 -j ACCEPT
while read -r ip; do
  ip="${ip%%#*}"; ip="$(echo "$ip" | tr -d ' ')"
  [ -n "$ip" ] && iptables -A ICQ_CLUSTER -s "$ip" -j ACCEPT
done < <(cat "$ALLOW" 2>/dev/null)
iptables -A ICQ_CLUSTER -j DROP
for p in 5432 6379; do
  iptables -C INPUT -p tcp --dport $p -j ICQ_CLUSTER 2>/dev/null || iptables -I INPUT 1 -p tcp --dport $p -j ICQ_CLUSTER
done
echo "cluster firewall: $(grep -cv '^\s*$' "$ALLOW" 2>/dev/null || echo 0) server(s) allowed"
