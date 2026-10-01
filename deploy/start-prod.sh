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

# 【安全】四个生产机密在 application-prod.yml 里都【没有默认值】，缺了后端本来也起不来
# （Spring 报 Could not resolve placeholder）。这里提前拦一道，是为了把原因和生成方法说清楚，
# 而不是甩一句占位符解析失败。写成函数而非多份拷贝：漏加一个变量的代价是静默回落，不该靠人盯。
require_env() {
  local name="$1" hint="$2"
  if [[ -z "${!name:-}" ]]; then
    echo "错误：未设置 ${name}，拒绝启动。" >&2
    echo "  ${hint}" >&2
    exit 1
  fi
}

require_env JWT_SECRET \
  "生产密钥必须由环境变量注入（仓库里那个 dev 兜底值已公开，不可作生产密钥）。
  生成并导出：export JWT_SECRET=\"\$(openssl rand -base64 48)\""
require_env DB_PASSWORD \
  "原先默认 postgres，照脚本启动即等于用公开已知的口令连库。
  导出后重试：export DB_PASSWORD='你的强口令'"
require_env REDIS_PASSWORD \
  "原先整段没有 password，生产 Redis 处于无认证状态（里面存着 JWT 黑名单与 AI 会话原文）。
  生成并导出：export REDIS_PASSWORD=\"\$(openssl rand -base64 32)\""
# 审计 §2.8②：这个盐是 AI 评估留痕里 userIdHash 假名化的【唯一】前提 ——
# userId 是自增主键（取值空间 1..N 的小整数），盐若是已知值，日志里的哈希
# 整张表毫秒级可反查。application.yml 的缺省值已随公开仓库泄露，生产不可用它。
require_env AI_EVAL_HASH_SALT \
  "盐已公开 ⇒ 用它算出的 userIdHash 可被反查，伪匿名失效。
  生成并导出：export AI_EVAL_HASH_SALT=\"\$(openssl rand -base64 32)\"
  ⚠ 定了就别改：改盐会让历史 userIdHash 与新日志对不上（论文按用户 join 断链）。"

# 其余可按需覆盖：
# export DB_HOST=your-db-host  export DB_PORT=5432  export DB_NAME=dsmarket_prod
# export REDIS_HOST=your-redis-host  export REDIS_PORT=6379

echo "==> 后端启动（profile=${SPRING_PROFILES_ACTIVE}，jar=target/dsmarket-backend-1.0.0.jar）"
echo "==> 日志：logs/dsmarket.log"
cd "${BACKEND_DIR}"
exec java -Xms256m -Xmx512m -jar target/dsmarket-backend-1.0.0.jar
