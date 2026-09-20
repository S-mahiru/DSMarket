#!/usr/bin/env bash
# ============================================
# DSMarket 集成测试库初始化（默认 dsmarket_test）
#
# 为什么单独有这个入口：
#   README 让读者跑 `cd backend && mvn test`，而集成测试连的是 dsmarket_test
#   （见 backend/src/test/resources/application-test.yml）。该库**不在** compose 的
#   POSTGRES_DB 里（那是 dsmarket），官方 entrypoint 不会建它 —— 干净机器上直接
#   mvn test 会失败，且失败信息不会告诉你要先建库。本脚本就是补这一段。
#
# 用法：bash deploy/init_test_db.sh
#   Windows Git Bash 直接用；Linux / macOS 亦可。
#   目标库**已建好**（表数 > 0）时直接跳过，不会重放迁移 —— 迁移脚本不是幂等的，
#   重放会在 V01 报 `relation "dsm_user" already exists`。要全新重建，按脚本提示先 DROP。
#
# 迁移清单**不在本文件重复**：整个委托给 init_db.sh（它认 DB_NAME 环境变量）。
# 抄一份清单就会分叉，而分叉的表现是"测试库少了某张表"这种静默现象，不是报错。
# ============================================
set -euo pipefail

DB_NAME="${DB_NAME:-dsmarket_test}"
CONTAINER="${PG_CONTAINER:-dsmarket-postgres}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> 初始化集成测试库：${DB_NAME}（容器 ${CONTAINER}）"

# 传 DB_NAME 进去，让 init_db.sh 用同一份清单跑到这个库上。
DB_NAME="${DB_NAME}" PG_CONTAINER="${CONTAINER}" bash "${HERE}/init_db.sh"

# ---- 自检 ----
# init_db.sh 只打印计数、不判断；这里把"建成了"证出来。
# 要防的失败态是：psql 每条都没报错，但表其实一张没建（挂载目录空、连错实例等）。
TABLES="$(docker exec -i "${CONTAINER}" psql -U postgres -d "${DB_NAME}" -t -A \
  -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")"
TABLES="${TABLES//[[:space:]]/}"

if [[ ! "${TABLES}" =~ ^[0-9]+$ ]] || [[ "${TABLES}" -lt 1 ]]; then
  echo "错误：${DB_NAME} 里一张表都没有，初始化没有生效。" >&2
  echo "  1. 确认容器在跑：docker ps --filter name=${CONTAINER}" >&2
  echo "  2. 确认 db 目录已挂载：docker exec ${CONTAINER} ls /docker-entrypoint-initdb.d" >&2
  exit 1
fi

echo "==> 自检通过：${DB_NAME} 有 ${TABLES} 张表"
echo "==> 现在可以跑集成测试：cd backend && mvn test"
