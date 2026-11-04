#!/usr/bin/env bash
# 压测期间采样 Nginx stub_status，输出 CSV。
# 用法：./sample-nginx.sh 180 2 nginx-sample.csv
set -u

DURATION=${1:-180}
INTERVAL=${2:-2}
OUT=${3:-nginx-sample.csv}
STATUS_URL=${STATUS_URL:-http://127.0.0.1:8081/nginx_status}

echo "ts,active,reading,writing,waiting" > "$OUT"
END=$((SECONDS + DURATION))

while [ $SECONDS -lt $END ]; do
    BODY=$(curl -s "$STATUS_URL" || true)
    ACTIVE=$(echo "$BODY" | awk '/Active connections/{print $3}')
    RWW=$(echo "$BODY" | awk -F'[ :]+' '/Reading/{print $2","$4","$6}')
    echo "$(date '+%F %T'),${ACTIVE:-0},${RWW:-0,0,0}" >> "$OUT"
    sleep "$INTERVAL"
done

echo "写入 $OUT"
