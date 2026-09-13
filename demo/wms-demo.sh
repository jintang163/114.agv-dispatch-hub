#!/usr/bin/env bash
# WMS 演示脚本：批量下发任务，制造 高优抢占 / 排队 / 拥堵 场景
# 用法：BASE=http://localhost:8080 bash demo/wms-demo.sh
set -e
BASE="${BASE:-http://localhost:8080}"
ts() { date -u +"%Y-%m-%dT%H:%M:%SZ"; }

post() {
  curl -s -X POST "$BASE/api/tasks" -H 'Content-Type: application/json' -d "$1"
  echo
}

echo "== 1) 下发 4 个低/中优任务，占满 AGV =="
post "{\"type\":\"TRANSPORT\",\"priority\":\"LOW\",\"fromNode\":\"P-01\",\"toNode\":\"D-01\",\"deadline\":\"$(date -u -d '+3 hours' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || ts)\"}"
post "{\"type\":\"PICKING\",\"priority\":\"LOW\",\"fromNode\":\"P-02\",\"toNode\":\"D-02\"}"
post "{\"type\":\"TRANSPORT\",\"priority\":\"MEDIUM\",\"fromNode\":\"P-03\",\"toNode\":\"D-03\"}"
post "{\"type\":\"TRANSPORT\",\"priority\":\"MEDIUM\",\"fromNode\":\"P-05\",\"toNode\":\"D-01\"}"
sleep 2

echo "== 2) 下发 1 个高优紧急任务（期望 2 分钟内完成），观察插队与抢占 =="
post "{\"type\":\"TRANSPORT\",\"priority\":\"HIGH\",\"fromNode\":\"P-06\",\"toNode\":\"D-03\",\"deadline\":\"$(date -u -d '+2 minutes' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || ts)\",\"remark\":\"急单-产线缺料\"}"
sleep 4

echo "== 3) 再下发普通任务，观察按 截止时间+老化 排序 =="
post "{\"type\":\"PICKING\",\"priority\":\"LOW\",\"fromNode\":\"P-04\",\"toNode\":\"D-02\"}"
post "{\"type\":\"TRANSPORT\",\"priority\":\"MEDIUM\",\"fromNode\":\"P-01\",\"toNode\":\"D-03\"}"

echo
echo "队列视图："
curl -s "$BASE/api/tasks/queue/view"
echo
