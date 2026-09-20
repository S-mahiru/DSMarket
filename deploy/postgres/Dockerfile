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

# 编译依赖（构建完清理，避免体积膨胀）
# binutils 只服务于 ISA 守卫的 objdump，与其它构建依赖一起被 purge 掉。
RUN apk add --no-cache build-base postgresql-dev wget binutils \
    && mkdir -p /tmp/scws && cd /tmp/scws \
    && wget -q http://www.xunsearch.com/scws/down/scws-1.2.3.tar.bz2 -O scws.tar.bz2 \
    && tar xjf scws.tar.bz2 \
    && cd scws-1.2.3 \
    && ./configure --prefix=/usr/local \
    && make \
    && make install \
    && cd /tmp \
    && wget -q https://codeload.github.com/amutu/zhparser/tar.gz/refs/heads/master -O zhparser.tar.gz \
    && tar xzf zhparser.tar.gz \
    && cd zhparser-master \
    && make SCWS_HOME=/usr/local with_llvm=no \
    && make install SCWS_HOME=/usr/local with_llvm=no \
    && cd /tmp \
    && wget -q https://codeload.github.com/pgvector/pgvector/tar.gz/refs/tags/v0.8.0 -O pgvector.tar.gz \
    && tar xzf pgvector.tar.gz \
    && cd pgvector-0.8.0 \
    && make OPTFLAGS="" with_llvm=no \
    && make install OPTFLAGS="" with_llvm=no \
    && cd / \
    && rm -rf /tmp/scws /tmp/zhparser.tar.gz /tmp/zhparser-master /tmp/pgvector.tar.gz /tmp/pgvector-0.8.0 \
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
