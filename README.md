# DSMarket 黑海商城

多商家 3C 电商平台。含商品检索（PostgreSQL 全文检索 + zhparser 中文分词）、商家入驻与商品管理、
订单与支付、以及 AI 智能客服（双路检索 + 转人工坐席）等模块。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.4.4 / Java 21 / MyBatis-Plus / Spring Security |
| 数据库 | PostgreSQL 16（含 zhparser 中文分词扩展）+ Redis 7 |
| 前端 | React 19 / TypeScript / Vite / Ant Design 5 / Zustand / React Router 7 |
| 测试 | JUnit 5（集成测试跑真实 PostgreSQL）/ Vitest |

## 目录结构

```
DarkSea_Market_Main/
├── backend/                     Spring Boot 后端
│   └── src/main/resources/db/       数据库迁移脚本 V01 ~ V10
├── frontend/                    React 前端
├── deploy/                      部署脚本与配置
│   ├── init_db.sh                   生产库初始化
│   ├── start-prod.sh / .bat         生产启动脚本
│   ├── nginx/                       站点配置
│   └── postgres/                    自定义镜像（postgres + zhparser）
├── docker-compose.yml           开发依赖：PostgreSQL + Redis
└── LICENSE                      MIT
```

## 环境要求

- JDK 21
- Maven
- Node.js（建议当前 LTS）
- Docker（用于起 PostgreSQL 与 Redis）

## 快速开始

### 1. 启动依赖

```bash
docker compose up -d
```

首次启动会自动执行 `backend/src/main/resources/db/` 下的迁移脚本（V01 ~ V10，按文件名顺序）。

> 停止用 `docker compose stop`（保留容器与数据卷，可 `start` 回来）。
> **不要**用 `docker compose down -v` 重置 —— 见下方「数据库迁移约定」。

> **Windows 注意**：若本机装了原生 Redis 服务（`redis-server.exe` / Windows 服务 `Redis`），
> 它会占住 6379，与容器冲突 —— 因为端口只发布到回环，两者**无法共存**，`docker compose up -d`
> 会以 `ports are not available` 报错。停掉它即可（需管理员）：
> `Stop-Service Redis -Force; Set-Service Redis -StartupType Disabled`。
> **别忽略这个冲突**：应用连的是哪个实例不会报错，只会静默读写另一个 Redis，
> 于是"清缓存"清了个空实例、而真数据原封不动。

### 2. 启动后端

```bash
cd backend
mvn spring-boot:run
```

监听 `8080`。默认 profile 为 `dev`（见 `application.yml` 的 `spring.profiles.active`）。

首次在空库上启动会播种两个**仅供本地开发**的账号 —— `DataInitializer` 已限定 `@Profile("dev")`，
生产环境不会创建任何账号：

| 账号 | 口令 | 角色 |
|---|---|---|
| `admin` | `admin123` | 管理员 |
| `testuser` | `test123` | 普通用户 |

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

监听 `5173`，通过 Vite 代理把 `/api` 与 `/uploads` 转发到后端 `8080`。

## 测试

```bash
# 后端（需 PostgreSQL 与 Redis 已启动）
cd backend && mvn test

# 前端
cd frontend && npm test
```

后端集成测试连的是独立的 `dsmarket_test` 库（见 `backend/src/test/resources/application-test.yml`），
不污染开发库。

> ⚠ `dsmarket_test` **不在** compose 的 `POSTGRES_DB` 里（那是 `dsmarket`），官方 entrypoint
> 不会建它 —— 所以在一台干净机器上直接 `mvn test` 会失败。首次运行前先建一次：
>
> ```bash
> bash deploy/init_test_db.sh
> ```
>
> 该脚本委托 `init_db.sh` 应用**同一份**迁移清单（不另抄一份，免得两边分叉），跑完会自检
> 表数量并打印结果；建不起来时直接报错，而不是留你去猜 `mvn test` 为什么红。
> 库已建好（表数 > 0）时**跳过、不重放** —— 迁移脚本不是幂等的；要重建请先 `DROP DATABASE`。

> 集成测试依赖「库里有且仅有自己插入的夹具」，因此部分测试类会清表（在事务内，跑完回滚）。
> 若你在同一个库上同时跑别的测试，注意这一点。

## 环境变量

### 生产必需

这三个在 `application-prod.yml` 里**都没有默认值**，未注入时后端**直接启动失败**（fail-fast），
`deploy/start-prod.sh` / `.bat` 也会在起进程前先拦一道并说明原因：

| 变量 | 说明 |
|---|---|
| `JWT_SECRET` | JWT 签名密钥。不写默认值是为了不静默回落到仓库里公开的 dev 兜底值。生成：`openssl rand -base64 48` |
| `DB_PASSWORD` | 数据库口令。原先默认 `postgres`，等于没有口令。 |
| `REDIS_PASSWORD` | Redis 口令。原先整段没有 `password`，生产 Redis 无认证 —— 而它存着 JWT 黑名单（决定"已登出的 token 还算不算数"）与 AI 会话原文。生成：`openssl rand -base64 32` |

> **开发 / 测试环境不需要设置**：`application-dev.yml` 与 `application-test.yml` 里该口令回落到
> `dsm_dev_redis`，与 `docker-compose.yml` 的默认值读的是**同名**变量 `REDIS_PASSWORD`。
> ⚠ 但**"同名"不等于"必然一致"**：compose 那侧由客户端进程展开 `${VAR:-x}`，后端那侧由自己的
> 进程环境展开 `${VAR:x}`，是**两处独立展开** —— **只给一边设 `REDIS_PASSWORD` 照样错配**。
> 别靠这个名字推断，改了任一侧都要按下面那条实测一遍。
>
> ⚠ **口令配错不会让应用启动失败**：`JwtAuthenticationFilter` 查 Redis 黑名单时异常被空
> `catch` 吞掉，表现是**进程正常起来、日志几乎无痕、所有已登录请求静默 401**
> （实测日志里只有一行 `WRONGPASS`）。所以改完 Redis 配置，验收判据是**"登录后带 token
> 请求成功"**，不是"启动没报错"。

### 可选（AI 模块）

| 变量 | 默认 | 说明 |
|---|---|---|
| `AI_MARKET` | 空 | 对话模型 API Key（未设置时回落 `AI_ASSISTANT_API_KEY`） |
| `DASHSCOPE_API_KEY` | 空 | 向量模型 API Key |
| `AI_LLM_BASE_URL` / `AI_LLM_MODEL` | DeepSeek | 对话模型接入点（OpenAI 兼容） |
| `AI_EVAL_ENABLED` | `true` | AI 效果评估留痕开关 |

未配置 AI Key 时，非 AI 模块照常可用。

## 生产部署

见 `deploy/`：

- `deploy/start-prod.sh`（Linux / macOS / Git Bash）
- `deploy/start-prod.bat`（Windows CMD）
- `deploy/init_db.sh`（生产库初始化）
- `deploy/nginx/`（站点配置）

前置：`mvn -DskipTests package` 生成 `target/dsmarket-backend-1.0.0.jar`。

## 数据库迁移约定

`backend/src/main/resources/db/` 下的 `V01__` ~ `V10__` 按文件名顺序执行，有两条消费路径：

- **开发**：`docker compose` 把该目录挂为 `/docker-entrypoint-initdb.d`，由官方 entrypoint 按
  **shell glob 字节序**执行
- **生产**：`deploy/init_db.sh` 中显式列出的清单（该文件里**两处**都有清单，加文件时都要改）

两条硬约定：

1. **文件名必须零填充为两位**（`V01` 而非 `V1`）。glob 是按字节序比较的，`V10__` 会排在 `V1__`
   **之前**（第 3 位 `'0'`=0x30 < `'_'`=0x5F），补零才能得到自然序。
2. **新增迁移必须幂等，且不得引用更晚的脚本才创建的对象** —— 否则会形成循环依赖，
   **任何**执行顺序都无法满足。本项目踩过：V1 里的全文索引依赖 V5 才建的分词配置，
   而 V5 那段又要 V1 建的表。

> ⚠ `docker compose down -v` 删掉的是 `pgdata` 卷，而该卷里不止 `dsmarket` —— 还有
> `dsmarket_test` 与 `dsmarket_prod`。**`down -v` 之后 `docker compose up` 只会自动重建
> `dsmarket` 一个**（那是 `POSTGRES_DB`）；另外两个虽然都有脚本可建
> （`deploy/init_test_db.sh` 与 `deploy/init_db.sh`），但没有任何东西会自动调用它们。
> 要验证全新初始化，请另起一个一次性容器挂载 `db/` 目录来看，别动这个卷。

## 许可证

MIT，见 [LICENSE](LICENSE)。
