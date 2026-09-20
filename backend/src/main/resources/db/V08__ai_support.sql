-- ============================================
-- V8：人工客服（C4 转人工与坐席工作台）两张表
--   1) ds_ai_support_session  人工会话主表（转人工发起后才有行，起始即 pending_human）
--   2) ds_ai_support_message  人工消息表（转人工后消息一律落 PG，不依赖 Redis）
-- 前置：无（不依赖 pgvector；纯关系表）
-- 用途：AI 答不了/买家要真人 → 真实状态切换交给系统内 ADMIN 兼任坐席；
--      无人在线时诚实留言（message_left），AI 绝不谎报"已转真人"
-- 幂等：可重复执行（表 DO 守卫 / 索引 DROP IF EXISTS）
-- 权威：REQ-20260907-C4（§3 数据模型 / §5 状态机）；实施补充列/索引见 DECISION-20260908-C4
-- ============================================

-- 1. 人工会话主表
-- 注意：status 的 ai_active 是【隐含态、不落库】—— 本表只在转人工发起后才有行，起始即 pending_human；
--      禁止为 AI 会话写 ai_active 行（AI 上下文在 Redis，避免重复记账，REQ §3.1）。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'ds_ai_support_session') THEN
        CREATE TABLE ds_ai_support_session (
            id            BIGSERIAL PRIMARY KEY,
            user_id       BIGINT      NOT NULL,
            status        VARCHAR(20) NOT NULL,
            origin        VARCHAR(16) NOT NULL,
            requested_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
            active_at     TIMESTAMP,
            closed_at     TIMESTAMP,
            last_msg_at   TIMESTAMP,
            created_at    TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
            updated_at    TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
            deleted       SMALLINT    DEFAULT 0
        );

        COMMENT ON TABLE ds_ai_support_session IS '人工客服会话主表（C4）；仅转人工发起后才有行，起始即 pending_human';
        COMMENT ON COLUMN ds_ai_support_session.user_id IS '发起转人工的买家(R2)，服务端从 JWT 取，请求体不带';
        COMMENT ON COLUMN ds_ai_support_session.status IS '状态(小写蛇形):pending_human/human_active/message_left/closed；ai_active 为隐含态不落库';
        COMMENT ON COLUMN ds_ai_support_session.origin IS '触发途径(大写):USER_REQUEST(用户点按钮)/ANCHOR_HIT(锚点命中)/AI_SUGGEST(建议气泡点击)';
        COMMENT ON COLUMN ds_ai_support_session.requested_at IS '转人工发起时间';
        COMMENT ON COLUMN ds_ai_support_session.active_at IS '坐席接入时间(可空)';
        COMMENT ON COLUMN ds_ai_support_session.closed_at IS '结束时间(可空)；closed 仅由坐席显式结束触发，回复不自动关(P2)';
        COMMENT ON COLUMN ds_ai_support_session.last_msg_at IS '最近消息时间(工作台列表倒序排序用；应用层维护，无触发器)';
        COMMENT ON COLUMN ds_ai_support_session.deleted IS '软删除:0正常 1删除';
    END IF;
END $$;

-- 2. 人工消息表
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'ds_ai_support_message') THEN
        CREATE TABLE ds_ai_support_message (
            id             BIGSERIAL PRIMARY KEY,
            session_id     BIGINT      NOT NULL,
            sender         VARCHAR(8)  NOT NULL,
            content        TEXT        NOT NULL,
            read_by_agent  BOOLEAN     NOT NULL DEFAULT FALSE,
            created_at     TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
            updated_at     TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
            deleted        SMALLINT    DEFAULT 0
        );

        COMMENT ON TABLE ds_ai_support_message IS '人工客服消息表（C4）；人工态消息一律不经 LLM，AI 态消息不得写本表';
        COMMENT ON COLUMN ds_ai_support_message.session_id IS '所属人工会话 ds_ai_support_session.id';
        COMMENT ON COLUMN ds_ai_support_message.sender IS '发送方(大写):USER/AGENT/SYSTEM；SYSTEM 用于 30s 无人接等系统提示落库';
        COMMENT ON COLUMN ds_ai_support_message.content IS '消息正文：买家≤2000，坐席≤4000（应用层校验）';
        COMMENT ON COLUMN ds_ai_support_message.read_by_agent IS '坐席是否已读(工作台红点)；仅内部使用，不回推买家、不外露(P5/J 决议)';
        COMMENT ON COLUMN ds_ai_support_message.deleted IS '软删除:0正常 1删除';
    END IF;
END $$;

-- 3. 索引（幂等：先删后建）
-- 买家侧按 user 查活跃会话 / 回读最新会话
DROP INDEX IF EXISTS idx_ai_support_session_user;
CREATE INDEX idx_ai_support_session_user ON ds_ai_support_session (user_id);
-- 工作台三列表按 status 过滤
DROP INDEX IF EXISTS idx_ai_support_session_status;
CREATE INDEX idx_ai_support_session_status ON ds_ai_support_session (status);

-- 并发幂等 DB 兜底（实施补充，REQ 未指定）：同一买家至多 1 个"非终态"会话
-- （H7 重复 request 不新建；H12 closed 后可新建不受限）。应用层查-建之间仍有竞态，靠本索引兜底。
-- PG 部分唯一索引：deleted 为 SMALLINT，用 = 0（非 IS FALSE）。
DROP INDEX IF EXISTS uk_ai_support_session_active_user;
CREATE UNIQUE INDEX uk_ai_support_session_active_user
    ON ds_ai_support_session (user_id)
    WHERE deleted = 0 AND status IN ('pending_human', 'human_active', 'message_left');

-- 消息按会话正序回放（坐席接入前缓冲消息不丢，REQ §5）
DROP INDEX IF EXISTS idx_ai_support_message_session;
CREATE INDEX idx_ai_support_message_session ON ds_ai_support_message (session_id, id);
-- 工作台未读红点计数：sender=USER 且 read_by_agent=false
DROP INDEX IF EXISTS idx_ai_support_message_unread;
CREATE INDEX idx_ai_support_message_unread ON ds_ai_support_message (session_id, sender, read_by_agent);
