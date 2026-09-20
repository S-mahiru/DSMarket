# PG 镜像的三个源码依赖：为什么固定、怎么更新

对应审计 `dev_docks/DSMarket/12-readiness-audit.md` §4.5 第 15 项。
配套文件：`sources.sha256`（纯校验和，被 Dockerfile 在构建期消费）。

## 为什么要有这一套

原先三个依赖的取法各有一个问题，**程度递增**：

| 依赖 | 原先 | 问题 |
|---|---|---|
| pgvector | `refs/tags/v0.8.0` | tag 上游可移动；无校验和 |
| zhparser | `refs/heads/master` | **移动引用** |
| scws | `http://…`（明文） | 明文 HTTP；无校验和 |

**zhparser 那条最要命**：上游往 master 推一次，**本仓库一个提交都没有，镜像内容却变了**。
这是"按构建**时刻**出码"，与 pgvector 那个 SIGILL 缺陷（"按构建**机**出码"）是同一类问题的两个面。

**scws 那条是唯一能被第三方插手的**：486KB 的 tarball 走明文 HTTP，中间任何一跳都能替换，
编出来的 `libscws.so` 会直接进生产镜像。

**★ 而这三个 CI 结构上都抓不到。** CI 只验"镜像能构建、能跑、ISA 是基线"，
**证明不了拉到的字节是不是官方那份**。上游 master 变了 → 要么构建失败（看得见），
要么安静地装进一份不同的代码、三个 run 全绿。
跟 pgvector SIGILL 那次一样：**「绿」是关于流程的陈述，不是关于产物的陈述**。

## 现在钉成什么样

- **scws** → HTTPS（已实测上游支持：`https://www.xunsearch.com/scws/down/scws-1.2.3.tar.bz2` 返回 200）；
- **zhparser** → commit `2e995c4df672563992b4d7a147b8fa2d0d4cda6c`（Dockerfile 里的 `ARG ZHPARSER_PIN`）；
- **三份都校验 sha256**，在**编译之前**校验（坏字节在花几分钟编译前就被拦下）。

**钉 sha 不改变产物**：实测 `refs/heads/master` 与上面那个 sha 是**同一个 commit**，
两份 tarball 解出来的源码 `diff -r` **逐字节一致**。所以这是一次纯粹的"把随机性去掉"。

## 怎么更新（例如升级版本）

1. 改 Dockerfile 里的 URL，**同时改 `sources.sha256` 的文件名与哈希**（文件名必须与 `wget -O` 的目标名逐字一致 ——
   `sha256sum -c` 是**按当前目录**找文件的）；
2. 哈希必须自己下下来算，**不许从别处抄**：
   ```sh
   curl -sSL -o <文件> <URL> && sha256sum <文件>
   ```
3. **反向验一次**：把哈希故意改掉一位，`docker build` 必须**红在校验这一步**。
   **不做第 3 步就等于没接上** —— "构建成功"只推得出"没拦住"，推不出"校验跑过"。

## ★ 两个已经踩过的坑

### 1. `sources.sha256` 里**不能写注释**

`busybox` 的 `sha256sum -c` **不跳过 `#` 注释行**（GNU coreutils 会跳过）。
第一版把整段说明写在 `sources.sha256` 里，构建实际表现是：

```
sha256sum: can't open ' 与 [[index digest]] 那次的坑同源。故 URL 改了必须重算…': No such file or directory
sha256sum: WARNING: 41 of 44 computed checksums did NOT match
```

—— 它把每一行中文说明**当成一个校验条目**去解析。**故本文件是纯条目，说明一律放这里。**

（这次的失败是**响亮**的，属于运气好；同样的"承重假设没验"换个形状就会变成静默失效。）

### 2. 哈希钉的是 **(URL, ref) 组合的字节**，不是"源码内容"

实测：`refs/heads/master` 与钉住的 sha 是同一个 commit、源码逐字节一致，
但两份 tarball 的 **sha256 不同**（`0ab8b596…` vs `ce5d5e21…`）——
GitHub 把 **ref 名编进了顶层目录名**（`zhparser-master` vs `zhparser-2e995c4d…`）。

⇒ **哈希不等 ≠ 内容不同**，与 [[index digest]] 那次的坑同源。
**URL 改了必须重算哈希，别拿"内容相等"去推"哈希相等"。**
