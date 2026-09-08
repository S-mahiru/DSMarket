-- ============================================
-- V5：商品全文检索升级（zhparser 中文分词 + GIN 索引）
-- 目标：把 ft_search 从 simple 分词改为 zhparser 中文分词，支持"苹果手机"按词切分检索
-- 前置：PG 镜像已集成 zhparser 插件（deploy/postgres/Dockerfile）
-- 注意：本脚本可重复执行（幂等），同时服务"已有库迁移"与"全新初始化"两个场景
-- ============================================

-- 1. 启用中文分词解析器（每库需执行一次）
CREATE EXTENSION IF NOT EXISTS zhparser;

-- 2. 创建中文分词配置（zhparser 扩展只提供 PARSER，必须建 config 才能用 to_tsvector）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'zhparser_config') THEN
        CREATE TEXT SEARCH CONFIGURATION zhparser_config (PARSER = zhparser);
        ALTER TEXT SEARCH CONFIGURATION zhparser_config ADD MAPPING FOR n,v,a,i,e,l WITH simple;
    END IF;
END $$;

-- 3. 重建全文索引：simple → zhparser（幂等：先删旧的）
DROP INDEX IF EXISTS ft_search;
CREATE INDEX ft_search ON dsm_product USING GIN (
    to_tsvector('zhparser_config',
        coalesce(name, '') || ' ' || coalesce(title, '') || ' ' || coalesce(brief, ''))
);
