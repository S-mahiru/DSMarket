#!/usr/bin/env bash
# ============================================
# DSMarket 生产数据库初始化脚本（PostgreSQL 16）
# 执行：bash deploy/init_db.sh
# 作用：创建生产库 + 应用 V1 建表 / V2 分类 / V3 演示商品 / V4 店铺 / V5 全文检索 / V6~V9 AI 模块 / V10 商品归属店铺
# 场景一（Docker）：本机模拟生产，PG 跑在容器里（db 目录已挂载）
# 场景二（云服务器裸机 psql）：见脚本末尾注释
# ============================================
set -euo pipefail
# Windows Git Bash 下避免把容器内路径 /docker-... 误转成本地路径；Linux/macOS 无副作用
export MSYS_NO_PATHCONV=1

DB_NAME="${DB_NAME:-dsmarket_prod}"
CONTAINER="${PG_CONTAINER:-dsmarket-postgres}"
SQL_DIR="/docker-entrypoint-initdb.d"

echo "==> 创建生产数据库：${DB_NAME}"
docker exec -i "${CONTAINER}" psql -U postgres \
  -c "CREATE DATABASE ${DB_NAME}" >/dev/null 2>&1 \
  || echo "    数据库已存在，跳过创建"

for sql in V1__init_schema.sql V2__seed_data.sql V3__demo_products.sql V4__merchant_shop.sql V5__zhparser_fulltext.sql V6__ai_knowledge.sql V7__ai_issue_pool.sql V8__ai_support.sql V9__ai_issue_pool_dislike_key.sql V10__product_shop_id.sql; do
  echo "==> 应用 ${sql}"
  docker exec -i "${CONTAINER}" psql -U postgres -d "${DB_NAME}" -v ON_ERROR_STOP=1 \
    -f "${SQL_DIR}/${sql}" >/dev/null
done

echo "==> 校验：表数量 = $(docker exec -i "${CONTAINER}" psql -U postgres -d "${DB_NAME}" -t -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")"
echo "==> 校验：商品数量 = $(docker exec -i "${CONTAINER}" psql -U postgres -d "${DB_NAME}" -t -c "SELECT count(*) FROM dsm_product")"
echo "==> 生产库初始化完成：${DB_NAME}"

# ============================================
# 场景二：云服务器裸机 PostgreSQL（无 Docker）
#   1. 把 db/ 目录下 V1 ~ V10 全部 SQL 传到服务器（顺序不能乱）
#   2. 执行：
#      psql -U postgres -c "CREATE DATABASE dsmarket_prod;"
#      psql -U postgres -d dsmarket_prod -f V1__init_schema.sql
#      psql -U postgres -d dsmarket_prod -f V2__seed_data.sql
#      psql -U postgres -d dsmarket_prod -f V3__demo_products.sql
#      psql -U postgres -d dsmarket_prod -f V4__merchant_shop.sql
#      psql -U postgres -d dsmarket_prod -f V5__zhparser_fulltext.sql
#      psql -U postgres -d dsmarket_prod -f V6__ai_knowledge.sql
#      psql -U postgres -d dsmarket_prod -f V7__ai_issue_pool.sql
#      psql -U postgres -d dsmarket_prod -f V8__ai_support.sql
#      psql -U postgres -d dsmarket_prod -f V9__ai_issue_pool_dislike_key.sql
#      psql -U postgres -d dsmarket_prod -f V10__product_shop_id.sql
#   前置：裸机 PG 需先安装 zhparser + pgvector 插件（见 08-deployment-guide.md 常见问题）
#   3. admin/testuser 由后端 DataInitializer 首次启动时自动播种
# ============================================
