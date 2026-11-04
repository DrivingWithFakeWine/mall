#!/usr/bin/env bash
# 压测期间采样应用指标（Actuator metrics），输出 CSV。
# 需要：应用已暴露 metrics 端点，且能通过 Nginx 内网白名单访问。
# 用法：TOKEN=<登录 token> BASE=http://127.0.0.1 ./sample-app.sh 180 2 app-sample.csv
set -u

DURATION=${1:-180}
INTERVAL=${2:-2}
OUT=${3:-app-sample.csv}

TOKEN=${TOKEN:?需要设置 TOKEN}
BASE=${BASE:-http://127.0.0.1}

metric() {
    curl -s -H "Authorization: Bearer $TOKEN" "$BASE/actuator/metrics/$1" \
        | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2
}

echo "ts,tomcat_busy_threads,tomcat_current_threads,hikari_active,hikari_pending,hikari_max,jvm_heap_used" > "$OUT"
END=$((SECONDS + DURATION))

while [ $SECONDS -lt $END ]; do
    echo "$(date '+%F %T'),$(metric tomcat.threads.busy),$(metric tomcat.threads.current),$(metric hikaricp.connections.active),$(metric hikaricp.connections.pending),$(metric hikaricp.connections.max),$(metric 'jvm.memory.used?tag=area:heap')" >> "$OUT"
    sleep "$INTERVAL"
done

echo "写入 $OUT"
