@echo off
REM ============================================
REM DSMarket 后端生产启动脚本 (Windows CMD)
REM 前置：已执行 mvn -DskipTests package 生成 target\dsmarket-backend-1.0.0.jar
REM ============================================
cd /d "%~dp0..\backend"

set SPRING_PROFILES_ACTIVE=prod

REM 【安全】未注入 JWT_SECRET 时拒绝启动。
REM application-prod.yml 里该占位符没有默认值，缺了它后端本来也起不来；
REM 这里提前拦一道是为了把原因说清楚，而不是甩一句 Could not resolve placeholder。
if "%JWT_SECRET%"=="" (
  echo 错误：未设置 JWT_SECRET，拒绝启动。
  echo   生产密钥必须由环境变量注入（仓库里那个 dev 兜底值已公开，不可作生产密钥）。
  echo   生成：openssl rand -base64 48
  echo   然后：set JWT_SECRET=上一步生成的密钥
  exit /b 1
)

REM 【安全】未注入 DB_PASSWORD 时拒绝启动，理由同 JWT_SECRET。
REM 改动前 application-prod.yml 里默认是 postgres —— 照本脚本启动即等于用公开已知的口令连库。
if "%DB_PASSWORD%"=="" (
  echo 错误：未设置 DB_PASSWORD，拒绝启动。
  echo   该占位符在 application-prod.yml 里没有默认值（原先默认 postgres，等于无口令）。
  echo   设置后重试：set DB_PASSWORD=你的强口令
  exit /b 1
)

REM 【安全】未注入 REDIS_PASSWORD 时拒绝启动。
REM 改动前 application-prod.yml 的 redis 段整段没有 password —— 生产 Redis 无认证，
REM 而它存着 JWT 黑名单（决定"已登出的 token 还算不算数"）与 AI 会话原文。
if "%REDIS_PASSWORD%"=="" (
  echo 错误：未设置 REDIS_PASSWORD，拒绝启动。
  echo   该占位符在 application-prod.yml 里没有默认值。
  echo   生成：openssl rand -base64 32
  echo   然后：set REDIS_PASSWORD=上一步生成的口令
  exit /b 1
)

REM 【安全】未注入 AI_EVAL_HASH_SALT 时拒绝启动。
REM 该盐是 AI 评估留痕里 userIdHash 假名化的【唯一】前提：userId 是自增主键
REM （取值空间 1..N 的小整数），盐若是已知值，日志里的哈希整张表毫秒级可反查。
REM application.yml 的缺省值已随公开仓库泄露，生产不可用它。
if "%AI_EVAL_HASH_SALT%"=="" (
  echo 错误：未设置 AI_EVAL_HASH_SALT，拒绝启动。
  echo   盐已公开 ^=^> 用它算出的 userIdHash 可被反查，伪匿名失效。
  echo   生成：openssl rand -base64 32
  echo   然后：set AI_EVAL_HASH_SALT=上一步生成的值
  echo   注意：定了就别改，改盐会让历史 userIdHash 与新日志对不上。
  exit /b 1
)

echo ==^> 后端启动 ^(profile=prod, jar=target\dsmarket-backend-1.0.0.jar^)
echo ==^> 日志：logs\dsmarket.log
java -Xms256m -Xmx512m -jar target\dsmarket-backend-1.0.0.jar
