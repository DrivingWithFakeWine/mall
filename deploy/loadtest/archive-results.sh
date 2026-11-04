#!/usr/bin/env bash
# 从压测机下载指定场景的报告，按“日期_git版本”归档并生成 MANIFEST。
# 用法：deploy/loadtest/archive-results.sh <remote> <label> <run1> [run2 ...]
# 例：  deploy/loadtest/archive-results.sh loadtest 2026-11-04 s0-final s1 s3 s4b s4c
# 依赖：本机 git、ssh/scp、sha256sum；压测机上有 /opt/loadtest/{results,reports}
set -euo pipefail

if [ $# -lt 3 ]; then
    echo "用法: $0 <remote> <label> <run1> [run2 ...]" >&2
    exit 2
fi

REMOTE=$1
LABEL=$2
shift 2
RUNS=("$@")

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"

VERSION=$(git describe --always --dirty)
BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ -n "$(git status --porcelain)" ]; then
    DIRTY_DESC="有未提交改动"
else
    DIRTY_DESC="干净"
fi
OUT="loadtest-reports/${LABEL}_${VERSION}"
REMOTE_DIR=${REMOTE_DIR:-/opt/loadtest}
JMETER_BIN=${JMETER_BIN:-/opt/apache-jmeter-5.6.3/bin/jmeter}
SSH_KEY=${SSH_KEY:-$HOME/.ssh/loadtest.pem}

SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=10)
[ -f "$SSH_KEY" ] && SSH_OPTS+=(-i "$SSH_KEY")

PATHS=()
for run in "${RUNS[@]}"; do
    PATHS+=("results/${run}.jtl" "reports/${run}")
done

TAR="/tmp/doroshop-loadtest-${LABEL}_${VERSION}.tgz"
echo "归档版本: ${VERSION}  ->  ${OUT}"

ssh "${SSH_OPTS[@]}" "$REMOTE" "cd '$REMOTE_DIR' && tar -czf '$TAR' ${PATHS[*]}"
mkdir -p "$OUT"
scp "${SSH_OPTS[@]}" "${REMOTE}:${TAR}" "$OUT/bundle.tgz"
tar -xzf "$OUT/bundle.tgz" -C "$OUT"
rm -f "$OUT/bundle.tgz"

JMETER_VER=$(ssh "${SSH_OPTS[@]}" "$REMOTE" "$JMETER_BIN --version 2>&1 | grep -oE '[0-9]+\\.[0-9]+\\.[0-9]+' | head -1" || true)
[ -n "$JMETER_VER" ] || JMETER_VER="unknown"
JAR_SHA=$(sha256sum Shop/target/Shop-0.0.1-SNAPSHOT.jar 2>/dev/null | awk '{print $1}' || true)
[ -n "$JAR_SHA" ] || JAR_SHA="未找到本地构建产物"

{
    echo "# 压测报告 ${LABEL}"
    echo
    echo "- 代码版本：\`${VERSION}\`（分支 \`${BRANCH}\`，工作区${DIRTY_DESC}）"
    echo "- 本地 JAR sha256：\`${JAR_SHA}\`"
    echo "- 归档时间：$(date '+%Y-%m-%d %H:%M:%S')"
    echo "- 压测机：\`${REMOTE}\`，JMeter：\`${JMETER_VER}\`"
    echo "- 应用/数据库：应用 2C4G + RDS（拓扑见 HANDOFF）"
    echo "- 场景：${RUNS[*]}"
    echo
    echo "## 产物"
    echo
    echo "- \`results/*.jtl\`：JMeter 原始结果"
    echo "- \`reports/*\`：JMeter HTML 报告"
    echo "- \`summary.csv\`：分标签汇总（如该次归档有生成）"
    echo
    echo "## 口径"
    echo
    echo "- 吞吐基线关闭 JFR/GC 日志，预热约 12 分钟后再测量"
    echo "- S4 混合的 burst 窗口定义为测试开始后前 15 秒"
    echo "- 结论与下一步见 \`DECISIONS.md\` 第 7 节"
    echo
    echo "## 文件校验"
    echo
    echo '```'
    (cd "$OUT" && find results -name '*.jtl' -exec sha256sum {} \; | sort)
    echo '```'
} > "$OUT/MANIFEST.md"

echo "完成：$OUT"
echo "  $(find "$OUT" -name '*.jtl' | wc -l) 个 JTL，$(find "$OUT/reports" -name 'index.html' | wc -l) 个 HTML 报告"
