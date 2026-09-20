#!/usr/bin/env bash
# ============================================
# DSMarket 数据库初始化脚本（PostgreSQL 16）
# 执行：bash deploy/init_db.sh                              → 建 dsmarket_prod（默认，生产）
#       DB_NAME=dsmarket_test bash deploy/init_db.sh        → 建任意其它库
#       bash deploy/init_test_db.sh                         → 建集成测试库（带自检，推荐用这个）
# 作用：创建目标库 + 应用 V01 建表 / V02 分类 / V03 演示商品 / V04 店铺 / V05 全文检索 / V06~V09 AI 模块 / V10 商品归属店铺
# 场景一（Docker）：本机模拟生产，PG 跑在容器里（db 目录已挂载）
# 场景二（云服务器裸机 psql）：见脚本末尾注释
#
# 注意本脚本**本身不校验结果**（只打印计数）；要断言"建成了"请走 init_test_db.sh，
# 它在委托本脚本之后会再查一次表数量并失败退出。
# ============================================
set -euo pipefail
# Windows Git Bash 下避免把容器内路径 /docker-... 误转成本地路径；Linux/macOS 无副作用
export MSYS_NO_PATHCONV=1

DB_NAME="${DB_NAME:-dsmarket_prod}"
CONTAINER="${PG_CONTAINER:-dsmarket-postgres}"
SQL_DIR="/docker-entrypoint-initdb.d"

# ---- 已初始化过就直接跳过 ----
# ★ 迁移脚本**不是幂等的**（实测：对已存在的库重放，V01 就报
#   `relation "dsm_user" already exists`，psql 退出码 3）。所以这里不能无脑重放，
#   否则"再跑一次"的形态是"报一堆 already exists"，读者会以为脚本坏了。
#   判据用"表数量 > 0"而不是"库存在"：库存在但 0 张表（建到一半失败）应继续补跑。
if [[ "$(docker exec -i "${CONTAINER}" psql -U postgres -t -A \
      -c "SELECT 1 FROM pg_database WHERE datname = '${DB_NAME}'" 2>/dev/null | tr -d '[:space:]')" == "1" ]]; then
  EXISTING="$(docker exec -i "${CONTAINER}" psql -U postgres -d "${DB_NAME}" -t -A \
    -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'" 2>/dev/null | tr -d '[:space:]')"
  if [[ "${EXISTING}" =~ ^[0-9]+$ ]] && [[ "${EXISTING}" -ge 1 ]]; then
    echo "==> ${DB_NAME} 已存在且有 ${EXISTING} 张表 —— 跳过初始化。"
    echo "    （迁移脚本不是幂等的，重放会报 already exists，故此处不重放。）"
    echo "    要全新重建："
    echo "      docker exec -i ${CONTAINER} psql -U postgres -c 'DROP DATABASE ${DB_NAME}'"
    echo "      bash deploy/init_db.sh   （或 init_test_db.sh）"
    exit 0
  fi
  echo "==> ${DB_NAME} 已存在但是空库（0 张表），继续补跑迁移"
else
  echo "==> ${DB_NAME} 不存在，新建"
fi

echo "==> 创建数据库：${DB_NAME}"
# 只在"库已存在"（SQLSTATE 42P04）时放行；其余失败一律响亮退出。
# 原先写的是 `>>> /dev/null 2>&1 || echo "数据库已存在，跳过创建"` —— 那个 `||` 把**所有**
# 失败都报成了"已存在"：容器没起、容器名写错、口令不对，屏幕上都是同一句"已存在，跳过创建"，
# 直到第一条迁移才炸，且炸出来的原因跟真实原因对不上。（反向对照 B 实测踩到。）
if ! CREATE_OUT="$(docker exec -i "${CONTAINER}" psql -U postgres -v VERBOSITY=verbose \
      -c "CREATE DATABASE ${DB_NAME}" 2>&1)"; then
  if grep -qE "42P04|already exists" <<<"${CREATE_OUT}"; then
    echo "    数据库已存在，跳过创建"
  else
    echo "错误：创建数据库 ${DB_NAME} 失败。" >&2
    echo "  ${CREATE_OUT}" >&2
    echo "  容器：${CONTAINER}（可用 PG_CONTAINER 覆盖）" >&2
    exit 1
  fi
fi

# 文件名已补零为两位数（V01~V10）：原 V1__..V9__ 在 glob 字节序里会把 V10__ 排到最前。
# 本清单是"生产路径的第二处事实来源"，加文件时必须与本文件末尾注释一起改（两处）。
for sql in V01__init_schema.sql V02__seed_data.sql V03__demo_products.sql V04__merchant_shop.sql V05__zhparser_fulltext.sql V06__ai_knowledge.sql V07__ai_issue_pool.sql V08__ai_support.sql V09__ai_issue_pool_dislike_key.sql V10__product_shop_id.sql; do
  echo "==> 应用 ${sql}"
  docker exec -i "${CONTAINER}" psql -U postgres -d "${DB_NAME}" -v ON_ERROR_STOP=1 \
    -f "${SQL_DIR}/${sql}" >/dev/null
done

echo "==> 校验：表数量 = $(docker exec -i "${CONTAINER}" psql -U postgres -d "${DB_NAME}" -t -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")"
echo "==> 校验：商品数量 = $(docker exec -i "${CONTAINER}" psql -U postgres -d "${DB_NAME}" -t -c "SELECT count(*) FROM dsm_product")"
echo "==> 数据库初始化完成：${DB_NAME}"

# ============================================
# 场景二：云服务器裸机 PostgreSQL（无 Docker）
#   1. 把 db/ 目录下 V01 ~ V10 全部 SQL 传到服务器（顺序不能乱）
#   2. 执行：
#      psql -U postgres -c "CREATE DATABASE dsmarket_prod;"
#      psql -U postgres -d dsmarket_prod -f V01__init_schema.sql
#      psql -U postgres -d dsmarket_prod -f V02__seed_data.sql
#      psql -U postgres -d dsmarket_prod -f V03__demo_products.sql
#      psql -U postgres -d dsmarket_prod -f V04__merchant_shop.sql
#      psql -U postgres -d dsmarket_prod -f V05__zhparser_fulltext.sql
#      psql -U postgres -d dsmarket_prod -f V06__ai_knowledge.sql
#      psql -U postgres -d dsmarket_prod -f V07__ai_issue_pool.sql
#      psql -U postgres -d dsmarket_prod -f V08__ai_support.sql
#      psql -U postgres -d dsmarket_prod -f V09__ai_issue_pool_dislike_key.sql
#      psql -U postgres -d dsmarket_prod -f V10__product_shop_id.sql
#   ⚠ 顺序不能乱，且这两处清单必须一致（本地 for 循环与本节）；漏一处即假绿。
#   前置：裸机 PG 需先安装 zhparser + pgvector 插件（见 08-deployment-guide.md 常见问题）
#   3. 管理员账号【不会】被自动播种：DataInitializer 已限定 @Profile("dev")，prod 不创建任何账号。
#      （改动前 prod 在空库上启动会播种 admin/admin123 —— 口令公开可猜，属安全缺口。）
#      首个管理员需自行创建，注意 password 字段必须是 BCrypt 哈希，不是明文。
# ============================================
