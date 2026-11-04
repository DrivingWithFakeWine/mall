#!/usr/bin/env bash
# 压测期间采样 MySQL 关键状态，输出 CSV。
# 用法：DB_HOST=... DB_USER=... DB_PASS=... DB_NAME=doro_mall ./sample-mysql.sh 180 5 mysql-sample.csv
set -u

DURATION=${1:-180}
INTERVAL=${2:-5}
OUT=${3:-mysql-sample.csv}

DB_HOST=${DB_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-3306}
DB_NAME=${DB_NAME:-doro_mall}
DB_USER=${DB_USER:?需要设置 DB_USER}
DB_PASS=${DB_PASS:-}

export MYSQL_PWD="$DB_PASS"

echo "ts,threads_running,threads_connected,row_lock_current_waits,row_lock_waits,row_lock_time_avg,questions,com_select,com_update,com_insert" > "$OUT"
END=$((SECONDS + DURATION))

while [ $SECONDS -lt $END ]; do
    mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -N -B "$DB_NAME" -e "
        SELECT CONCAT_WS(',', NOW(),
          (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_running'),
          (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_connected'),
          (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_current_waits'),
          (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_waits'),
          (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_time_avg'),
          (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Questions'),
          (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Com_select'),
          (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Com_update'),
          (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Com_insert'));
    " >> "$OUT" 2>/dev/null || echo "sample failed at $(date '+%F %T')" >&2
    sleep "$INTERVAL"
done

echo "写入 $OUT"
