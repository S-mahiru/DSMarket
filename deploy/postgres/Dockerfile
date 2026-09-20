# DSMarket PostgreSQL 16 + zhparser（中文分词）+ pgvector（向量检索）
# 用途：①全文检索商品（name/title/brief）支持中文分词；②AI 知识库向量检索（C2，vector(1024)）。
# 构建：  docker build -t dsmarket-postgres:16-zhparser deploy/postgres
# 说明：
#   - zhparser 依赖 scws 分词库，二者均源码编译；
#   - pgvector 纯 C、走 PGXS（alpine 无 clang 本就不生成 LLVM bitcode），与 zhparser 同批编译；
#   - ★ pgvector 必须显式传 `OPTFLAGS=""`（勿删，勿改成别的值）：
#     其 Makefile:14-15 写着 `# To compile for portability, run: make OPTFLAGS=""` 与
#     默认值 `OPTFLAGS = -march=native`。**-march=native 按构建机的 CPU 出码**，
#     于是构建机上有的指令集（AVX-512）被编进 vector.so，换一台没有的机器执行
#     `CREATE EXTENSION vector` 就 SIGILL —— 2026-09-20 CI 实测 `signal 4: Illegal instruction`。
#     本机实测：zhparser.so / libscws.so 均为 0 条 AVX 指令，**只有 vector.so 中招**。
#   - 源码从 codeload.github.com 取 tarball（github.com 主域在部分网络被阻断，codeload CDN 可达；勿改回 git clone）；
#   - ★ 三个依赖的来源与字节**全部固定**（勿退回"跟分支走"或明文 HTTP）：
#     scws 走 HTTPS（原为明文 http，486KB 的 tarball 中间任何一跳都能替换）、
#     zhparser 钉到 commit（原为 `refs/heads/master` —— 移动引用，上游推一次
#     **本仓库零提交、镜像内容却会变**）、三份都校验 sha256（见 SOURCES.md）。
#     这与上面 pgvector 那条是**同一类缺陷的两个面**：那条是"按构建**机**出码"，
#     这条是"按构建**时刻**出码"。**CI 结构上抓不到两者** —— 它只验"能构建、能跑、
#     ISA 是基线"，证明不了拉到的字节是不是官方那份。
#   - 扩展现有数据卷不生效：升级后需对目标库手动 CREATE EXTENSION（V6 已含 vector），再 docker compose up -d postgres 重建容器（勿 -v，保留卷）。
FROM postgres:16-alpine

# ISA 守卫（见脚本头部注释）。放在**构建期**而不是 CI 里"构建后检查"，
# 是为了让本机 `docker compose build` 也受保护 —— 坏镜像根本生不出来。
# ★ 调用时必须写成 `sh /usr/local/bin/check-isa.sh`（带 sh），**别改成裸路径**：
#   本文件在 git 索引里是 **100644**（本机 core.filemode=false，加不上执行位），
#   于是 Linux runner 检出后 COPY 进镜像仍是 0644 —— 裸路径执行会 Permission denied
#   （exit 126），把「构建镜像」这一步弄红，而现象与真实原因（权限位）对不上。
#   显式 `sh` 让构建不依赖权限位；本机 Windows 与 CI Linux 两条路径因此一致。
COPY check-isa.sh /usr/local/bin/check-isa.sh

# 三份源码 tarball 的 sha256（为什么固定 / 怎么更新，见 SOURCES.md）。
# 下载后、**编译前**校验一次。放在构建期而不是 CI 里"构建后检查"，
# 理由同 ISA 守卫：让本机 docker build 也受保护。
# ★ sources.sha256 里**不能写注释** —— busybox 的 sha256sum -c 不跳过 `#` 行，
#   会把注释逐行当成校验条目。实测踩过，详见 SOURCES.md。
COPY sources.sha256 /tmp/sources.sha256

# zhparser 钉住的 commit。**这个值在构建命令里出现两次**（URL 与解压后的目录名），
# 所以抽成 ARG —— 改了 URL 忘了改 `cd` 会构建红（目录不存在），但让它不可能发生更好。
# 解压目录名由 codeload 按 ref 生成：钉 sha 时是 `zhparser-<完整sha>`，跟分支时才叫 `zhparser-master`。
# ★ 改这里必须同步改 sources.sha256 里的 zhparser 行，否则校验红。
ARG ZHPARSER_PIN=2e995c4df672563992b4d7a147b8fa2d0d4cda6c

# 编译依赖（构建完清理，避免体积膨胀）
# binutils 只服务于 ISA 守卫的 objdump，与其它构建依赖一起被 purge 掉。
RUN apk add --no-cache build-base postgresql-dev wget binutils \
    && cd /tmp \
    && wget -q https://www.xunsearch.com/scws/down/scws-1.2.3.tar.bz2 -O scws.tar.bz2 \
    && wget -q https://codeload.github.com/amutu/zhparser/tar.gz/${ZHPARSER_PIN} -O zhparser.tar.gz \
    && wget -q https://codeload.github.com/pgvector/pgvector/tar.gz/refs/tags/v0.8.0 -O pgvector.tar.gz \
    && sha256sum -c sources.sha256 \
    && mkdir -p /tmp/scws && cd /tmp/scws \
    && tar xjf /tmp/scws.tar.bz2 \
    && cd scws-1.2.3 \
    && ./configure --prefix=/usr/local \
    && make \
    && make install \
    && cd /tmp \
    && tar xzf zhparser.tar.gz \
    && cd zhparser-${ZHPARSER_PIN} \
    && make SCWS_HOME=/usr/local with_llvm=no \
    && make install SCWS_HOME=/usr/local with_llvm=no \
    && cd /tmp \
    && tar xzf pgvector.tar.gz \
    && cd pgvector-0.8.0 \
    && make OPTFLAGS="" with_llvm=no \
    && make install OPTFLAGS="" with_llvm=no \
    && cd / \
    && rm -rf /tmp/scws /tmp/scws.tar.bz2 /tmp/zhparser.tar.gz /tmp/zhparser-${ZHPARSER_PIN} /tmp/pgvector.tar.gz /tmp/pgvector-0.8.0 /tmp/sources.sha256 \
    && sh /usr/local/bin/check-isa.sh \
    && apk del --purge build-base wget postgresql-dev binutils \
    && rm -rf /var/cache/apk/* \
    && ls /usr/local/share/postgresql/extension/zhparser* /usr/local/lib/postgresql/zhparser.so \
    && ls /usr/local/share/postgresql/extension/vector* /usr/local/lib/postgresql/vector.so

# 使用（每库需执行一次，建议放入初始化脚本 V 文件最前）：
#   CREATE EXTENSION IF NOT EXISTS zhparser;
#   CREATE TEXT SEARCH CONFIGURATION zhparser_config (PARSER = zhparser);
#   ALTER TEXT SEARCH CONFIGURATION zhparser_config ADD MAPPING FOR n,v,a,i,e,l WITH simple;
# 之后即可：to_tsvector('zhparser_config', ...) 做中文全文检索。
