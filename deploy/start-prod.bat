@echo off
REM ============================================
REM DSMarket 后端生产启动脚本 (Windows CMD)
REM 前置：已执行 mvn -DskipTests package 生成 target\dsmarket-backend-1.0.0.jar
REM ============================================
cd /d "%~dp0..\backend"

set SPRING_PROFILES_ACTIVE=prod
REM 生产环境务必覆盖数据库密码（取消下一行注释并修改）：
REM set DB_PASSWORD=your-strong-password

echo ==^> 后端启动 ^(profile=prod, jar=target\dsmarket-backend-1.0.0.jar^)
echo ==^> 日志：logs\dsmarket.log
java -Xms256m -Xmx512m -jar target\dsmarket-backend-1.0.0.jar
