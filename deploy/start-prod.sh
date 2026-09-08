#!/usr/bin/env bash
# ============================================
# DSMarket 后端生产启动脚本（Linux / macOS / Git Bash）
# 用法：bash deploy/start-prod.sh
# 前置：已执行 mvn -DskipTests package 生成 target/dsmarket-backend-1.0.0.jar
# ============================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "${SCRIPT_DIR}/../backend" && pwd)"

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"
# 生产环境务必覆盖数据库密码：
# export DB_PASSWORD='your-strong-password'
# export DB_HOST=your-db-host  export DB_PORT=5432  export DB_NAME=dsmarket_prod
# export REDIS_HOST=your-redis-host  export REDIS_PORT=6379

echo "==> 后端启动（profile=${SPRING_PROFILES_ACTIVE}，jar=target/dsmarket-backend-1.0.0.jar）"
echo "==> 日志：logs/dsmarket.log"
cd "${BACKEND_DIR}"
exec java -Xms256m -Xmx512m -jar target/dsmarket-backend-1.0.0.jar
