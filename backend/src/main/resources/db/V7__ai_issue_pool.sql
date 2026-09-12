-- ============================================
-- V7：AI 客服问题池表 ds_ai_issue_pool（C3 数据飞轮 / 主 REQ F7 问题池）
-- 前置：无（不依赖 pgvector；纯关系表）
-- 用途：沉淀客服答不好的问题（低置信 LOW_CONF 自动采集 + R2 点踩 DISLIKE 采集）
--      → R3 后台复核（pending→adopted/ignored）→ adopted 写回知识库并向量化 → 下次检索命中（自进化闭环）
-- 幂等：可重复执行（表 DO 守卫 / 索引 DROP IF EXISTS）
-- 权威：REQ-20260907-C3（数据模型）；实施补充列/索引见 DECISION-20260908-C3
-- ============================================

-- 1. 问题池表（列名与 MyBatis-Plus 下划线映射一致；id/created_at/updated_at/deleted 继承 BaseEntity）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'ds_ai_issue_pool') THEN
        CREATE TABLE ds_ai_issue_pool (
            id                    BIGSERIAL PRIMARY KEY,
            user_id               BIGINT,
            question              TEXT        NOT NULL,
            source                VARCHAR(16) NOT NULL,
            status                VARCHAR(16) NOT NULL DEFAULT 'pending',
            related_knowledge_id  BIGINT,
            reply_id              VARCHAR(64),
            retry_count           SMALLINT    NOT NULL DEFAULT 0,
            created_at            TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
            updated_at            TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
            deleted               SMALLINT    DEFAULT 0
        );

        COMMENT ON TABLE ds_ai_issue_pool IS 'AI 客服问题池（C3 数据飞轮）';
        COMMENT ON COLUMN ds_ai_issue_pool.user_id IS '提问人(可空；仅幂等去重与后台追溯用,列表仅 R3 可见,不落业务明细)';
        COMMENT ON COLUMN ds_ai_issue_pool.question IS '被采集问句(应用层校验≤500)';
        COMMENT ON COLUMN ds_ai_issue_pool.source IS '来源:LOW_CONF(低置信自动)/DISLIKE(点踩)';
        COMMENT ON COLUMN ds_ai_issue_pool.status IS '状态:pending/adopted/ignored,终态不可再流转';
        COMMENT ON COLUMN ds_ai_issue_pool.related_knowledge_id IS '采纳后关联知识 id(实施:先建即回填,供 F6 兜底重试定位 draft 行)';
        COMMENT ON COLUMN ds_ai_issue_pool.reply_id IS 'source=DISLIKE 时记录对应回复 replyId';
        COMMENT ON COLUMN ds_ai_issue_pool.retry_count IS '向量化兜底重试计数(实施补充列,超上限告警)';
        COMMENT ON COLUMN ds_ai_issue_pool.deleted IS '软删除:0正常 1删除';
    END IF;
END $$;

-- 2. 索引（幂等：先删后建）
-- 后台按状态/来源过滤列表
DROP INDEX IF EXISTS idx_ai_issue_pool_status;
CREATE INDEX idx_ai_issue_pool_status ON ds_ai_issue_pool (status);
DROP INDEX IF EXISTS idx_ai_issue_pool_source;
CREATE INDEX idx_ai_issue_pool_source ON ds_ai_issue_pool (source);

-- DISLIKE 幂等 DB 兜底：同 user+replyId 仅 1 条（partial：只对 DISLIKE 且未软删生效；
-- reply_id 为 NULL 的 LOW_CONF 行不受限，PG 唯一索引默认允许多个 NULL）
DROP INDEX IF EXISTS uk_ai_issue_pool_dislike_reply;
CREATE UNIQUE INDEX uk_ai_issue_pool_dislike_reply
    ON ds_ai_issue_pool (user_id, reply_id)
    WHERE source = 'DISLIKE' AND deleted = 0;
