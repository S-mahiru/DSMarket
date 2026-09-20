-- ============================================
-- V6：AI 客服知识库表 ds_ai_knowledge（C2 数据底座 / 主 REQ F6）
-- 前置：
--   1) 镜像须已集成 pgvector（deploy/postgres/Dockerfile 已含；升级后用 docker compose up -d --build postgres 重建，勿 -v）
--   2) zhparser 全文检索配置 zhparser_config 已由 V5 建立（先 V5 后 V6，init_db.sh 清单顺序保证）
-- 幂等：可重复执行（CREATE EXTENSION/REPLACE FUNCTION/表 DO 守卫/索引 DROP IF EXISTS）
-- 维度：embedding VECTOR(1024) —— A1 已拍板（DashScope text-embedding-v3 dim=1024 实测），见 DECISION-20260908-A1；勿改，换模型=改列+全量重算
-- ============================================

CREATE EXTENSION IF NOT EXISTS vector;

-- 辅助函数：keywords varchar[] → 空格拼接文本。
-- PG 把多态 array_to_string 保守标为 STABLE 无法进索引表达式；包一层显式 IMMUTABLE
-- （varchar 元素输出确定性，仅 PG 对多态版本保守，包装后语义安全），供 BM25 tsvector 索引使用。
-- 变更说明见 DECISION-20260908-C2。
CREATE OR REPLACE FUNCTION public.ai_keywords_text(kw varchar[])
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN array_to_string(kw, ' ');

-- 1. 知识表（列名与 MyBatis-Plus 下划线映射一致）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'ds_ai_knowledge') THEN
        CREATE TABLE ds_ai_knowledge (
            id          BIGSERIAL PRIMARY KEY,
            category    VARCHAR(32)     NOT NULL,
            question    TEXT            NOT NULL,
            answer      TEXT            NOT NULL,
            keywords    VARCHAR(32)[],
            faq         BOOLEAN         DEFAULT FALSE,
            status      VARCHAR(16)     NOT NULL DEFAULT 'draft',
            embedding   VECTOR(1024),
            created_at  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
            updated_at  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
            deleted     SMALLINT        DEFAULT 0
        );

        COMMENT ON TABLE ds_ai_knowledge IS 'AI 客服知识库（C2）';
        COMMENT ON COLUMN ds_ai_knowledge.category IS '类目:after_sale/shipping/payment/product_policy/account/other';
        COMMENT ON COLUMN ds_ai_knowledge.question IS '标准问题(检索对象之一,应用层校验≤500)';
        COMMENT ON COLUMN ds_ai_knowledge.answer IS '标准答案(应用层校验≤2000)';
        COMMENT ON COLUMN ds_ai_knowledge.keywords IS '可检索别名(varchar[])';
        COMMENT ON COLUMN ds_ai_knowledge.faq IS 'true=参与FAQ兜底精选';
        COMMENT ON COLUMN ds_ai_knowledge.status IS '状态:draft/published/disabled,检索只读published';
        COMMENT ON COLUMN ds_ai_knowledge.embedding IS 'question 向量(text-embedding-v3 dim=1024);仅published必须有值';
        COMMENT ON COLUMN ds_ai_knowledge.deleted IS '软删除:0正常 1删除';
    END IF;
END $$;

-- 2. 索引（幂等：先删后建，仿 V5 ft_search 惯例）
-- BM25 路：zhparser 中文全文检索（question+answer+keywords），GIN 索引
DROP INDEX IF EXISTS idx_ai_knowledge_fts;
CREATE INDEX idx_ai_knowledge_fts ON ds_ai_knowledge USING GIN (
    to_tsvector('zhparser_config',
        coalesce(question, '') || ' ' || coalesce(answer, '') || ' ' || coalesce(public.ai_keywords_text(keywords), ''))
);

-- Dense 路：余弦距离 HNSW 索引（C2 §9 索引行已拍板 HNSW；Dense 查询用 embedding <=> query_vec）
DROP INDEX IF EXISTS idx_ai_knowledge_embedding;
CREATE INDEX idx_ai_knowledge_embedding ON ds_ai_knowledge USING hnsw (embedding vector_cosine_ops);

-- published-only 硬规则 + FAQ/类目过滤的常规索引
DROP INDEX IF EXISTS idx_ai_knowledge_status;
CREATE INDEX idx_ai_knowledge_status ON ds_ai_knowledge (status);
DROP INDEX IF EXISTS idx_ai_knowledge_category;
CREATE INDEX idx_ai_knowledge_category ON ds_ai_knowledge (category);
