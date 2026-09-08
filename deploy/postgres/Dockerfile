# DSMarket PostgreSQL 16 + zhparser（中文分词）+ pgvector（向量检索）
# 用途：①全文检索商品（name/title/brief）支持中文分词；②AI 知识库向量检索（C2，vector(1024)）。
# 构建：  docker build -t dsmarket-postgres:16-zhparser deploy/postgres
# 说明：
#   - zhparser 依赖 scws 分词库，二者均源码编译；
#   - pgvector 纯 C、走 PGXS（alpine 无 clang 本就不生成 LLVM bitcode），与 zhparser 同批编译；
#   - 源码从 codeload.github.com 取 tarball（github.com 主域在部分网络被阻断，codeload CDN 可达；勿改回 git clone）；
#   - 扩展现有数据卷不生效：升级后需对目标库手动 CREATE EXTENSION（V6 已含 vector），再 docker compose up -d postgres 重建容器（勿 -v，保留卷）。
FROM postgres:16-alpine

# 编译依赖（构建完清理，避免体积膨胀）
RUN apk add --no-cache build-base postgresql-dev wget \
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
    && make with_llvm=no \
    && make install with_llvm=no \
    && cd / \
    && rm -rf /tmp/scws /tmp/zhparser.tar.gz /tmp/zhparser-master /tmp/pgvector.tar.gz /tmp/pgvector-0.8.0 \
    && apk del --purge build-base wget postgresql-dev \
    && rm -rf /var/cache/apk/* \
    && ls /usr/local/share/postgresql/extension/zhparser* /usr/local/lib/postgresql/zhparser.so \
    && ls /usr/local/share/postgresql/extension/vector* /usr/local/lib/postgresql/vector.so

# 使用（每库需执行一次，建议放入初始化脚本 V 文件最前）：
#   CREATE EXTENSION IF NOT EXISTS zhparser;
#   CREATE TEXT SEARCH CONFIGURATION zhparser_config (PARSER = zhparser);
#   ALTER TEXT SEARCH CONFIGURATION zhparser_config ADD MAPPING FOR n,v,a,i,e,l WITH simple;
# 之后即可：to_tsvector('zhparser_config', ...) 做中文全文检索。
